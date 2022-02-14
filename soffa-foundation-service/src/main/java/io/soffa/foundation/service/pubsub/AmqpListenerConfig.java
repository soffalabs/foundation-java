package io.soffa.foundation.service.pubsub;

import com.rabbitmq.client.Channel;
import io.soffa.foundation.commons.JsonUtil;
import io.soffa.foundation.commons.Logger;
import io.soffa.foundation.context.RequestContextHolder;
import io.soffa.foundation.messages.Message;
import io.soffa.foundation.messages.MessageHandler;
import io.soffa.foundation.metrics.CoreMetrics;
import io.soffa.foundation.metrics.MetricsRegistry;
import io.soffa.foundation.service.state.DatabasePlane;
import lombok.SneakyThrows;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;

@Configuration
@ConditionalOnProperty(value = "app.amqp.enabled", havingValue = "true")
public class AmqpListenerConfig {

    private static final Logger LOG = Logger.get(AmqpListenerConfig.class);
    private final MessageHandler handler;
    private final MetricsRegistry metrics;
    private final DatabasePlane dbPlane;

    public AmqpListenerConfig(MessageHandler handler, MetricsRegistry metrics, DatabasePlane dbPlane) {
        this.handler = handler;
        this.metrics = metrics;
        this.dbPlane = dbPlane;
    }

    @SneakyThrows
    @RabbitListener(queues = {"${spring.application.name}"}, ackMode = "MANUAL")
    public void listen(org.springframework.amqp.core.Message message, Channel channel) {

        final long tag = message.getMessageProperties().getDeliveryTag();

        if (!dbPlane.isReady()) {
            LOG.info("Requeueing message because database plane is not ready");
            channel.basicNack(tag, false, true);
            return;
        }

        String rawString = new String(message.getBody(), StandardCharsets.UTF_8);
        Message msg;
        try {
            msg = JsonUtil.deserialize(rawString, Message.class);
        } catch (Exception e) {
            metrics.increment(CoreMetrics.AMQP_INVALID_MESSAGE);
            LOG.error("[amqp] Invalid Message received", e);
            channel.basicNack(tag, false, false);
            return;
        }
        if (msg == null) {
            LOG.error("[amqp] null event definition received");
            metrics.increment(CoreMetrics.AMQP_INVALID_PAYLOAD);
            return;
        }
        RequestContextHolder.set(msg.getContext());
        try {
            if (!handler.accept(msg.getOperation())) {
                LOG.error("[amqp] unsupported event %s), skipping.", msg.getOperation());
                channel.basicNack(tag, false, false);
                metrics.increment(CoreMetrics.AMQP_UNSUPPORTED_OPERATION);
                return;
            }

            handler.handle(msg);
            channel.basicAck(tag, false);
            metrics.increment(CoreMetrics.AMQP_EVENT_PROCESSED);
        } catch (Exception e) {
            LOG.error("[amqp] failed to process event %s (%s) -- %s", msg.getOperation(), msg.getId());
            metrics.increment(CoreMetrics.AMQP_EVENT_PROCESSING_FAILED);
            channel.basicNack(tag, false, true);
        } finally {
            RequestContextHolder.clear();
        }
    }

}
