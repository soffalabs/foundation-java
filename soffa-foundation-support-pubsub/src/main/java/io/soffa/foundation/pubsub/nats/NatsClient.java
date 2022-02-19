package io.soffa.foundation.pubsub.nats;

import io.nats.client.*;
import io.nats.client.api.PublishAck;
import io.nats.client.api.StreamConfiguration;
import io.soffa.foundation.commons.IdGenerator;
import io.soffa.foundation.commons.Logger;
import io.soffa.foundation.errors.TechnicalException;
import io.soffa.foundation.model.Message;
import io.soffa.foundation.pubsub.AbstractPubSubClient;
import io.soffa.foundation.pubsub.MessageHandler;
import io.soffa.foundation.pubsub.PubSubClient;
import io.soffa.foundation.pubsub.config.PubSubClientConfig;
import lombok.SneakyThrows;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.jetbrains.annotations.NotNull;

import javax.annotation.PreDestroy;
import java.util.concurrent.CompletableFuture;


public class NatsClient extends AbstractPubSubClient implements PubSubClient {

    private static final Logger LOG = Logger.get(NatsClient.class);
    private final PubSubClientConfig config;

    private Connection connection;
    private JetStream stream;


    public NatsClient(String applicationName, PubSubClientConfig config, String broadcasting) {
        super(applicationName, config, broadcasting);
        this.config = config;
        configure();
    }


    @SneakyThrows
    @Override
    public void subscribe(@NonNull String subject, boolean broadcast, MessageHandler messageHandler) {
        // String subject, boolean broadcast, io.soffa.foundation.pubsub.MessageHandler handler
        LOG.info("Configuring subscription to %s", subject);

        NatsMessageHandler h = new NatsMessageHandler(connection, messageHandler);
        @SuppressWarnings("PMD")
        Dispatcher dispatcher = connection.createDispatcher();

        if (!broadcast) {
            dispatcher.subscribe(subject, subject + "-group", h);
        } else {
            configureStream(subject);
            stream.subscribe(subject, dispatcher, h, true);
        }
    }

    private void configure() {
        try {
            String[] addresses = config.getAddresses().split(",");
            Options o = new Options.Builder().servers(addresses)
                .connectionName(IdGenerator.shortUUID(applicationName))
                .maxReconnects(-1).build();
            connection = Nats.connect(o);
            JetStreamOptions jso = JetStreamOptions.defaultOptions();
            this.stream = connection.jetStream(jso);
            LOG.info("Connected to NATS servers: %s", config.getAddresses());
        } catch (Exception e) {
            NatsUtil.close(connection);
            throw new TechnicalException(e, "Unable to connect to NATS @ %s", config.getAddresses());
        }
    }

    @SneakyThrows
    private void configureStream(@NonNull String subject) {
        StreamConfiguration.Builder scBuilder = StreamConfiguration.builder()
            .name(subject);
        try {
            Subscription sub = stream.subscribe(subject);
            sub.unsubscribe();
        } catch (IllegalStateException e) {
            LOG.warn(e.getMessage());
            scBuilder.addSubjects(subject);
        }
        JetStreamManagement jsm = connection.jetStreamManagement();
        try {
            jsm.addStream(scBuilder.build());
        } catch (JetStreamApiException ignore) {
            LOG.warn("Stream %s already configured", subject);
        }
    }

    @Override
    public CompletableFuture<byte[]> internalRequest(@NonNull String subject, Message message) {
        return connection.request(NatsUtil.createNatsMessage(subject, message)).thenApply(io.nats.client.Message::getData);
    }

    @Override
    public void publish(@NonNull String target, @NotNull Message message) {
        connection.publish(NatsUtil.createNatsMessage(target, message));
    }

    @SneakyThrows
    @Override
    public void broadcast(@NonNull String target, @NotNull Message message) {
        String sub = resolveBroadcast(target);
        PublishAck ack = stream.publish(NatsUtil.createNatsMessage(sub, message));
        if (ack.hasError()) {
            throw new TechnicalException(ack.getError());
        }
    }

    @PreDestroy
    @SuppressWarnings("PMD")
    protected void cleanup() {
        NatsUtil.close(connection);
    }

}
