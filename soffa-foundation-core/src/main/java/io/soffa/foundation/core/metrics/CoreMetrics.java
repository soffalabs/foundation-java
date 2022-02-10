package io.soffa.foundation.core.metrics;

public interface CoreMetrics {

    String AMQP_INVALID_MESSAGE = "app_amqp_invalid_message";
    String AMQP_INVALID_PAYLOAD = "app_amqp_invalid_payload";
    String AMQP_UNSUPPORTED_ACTION = "app_amqp_unsupported_action";
    String AMQP_EVENT_PROCESSING_FAILED = "app_amqp_event_processing_failed";
    String AMQP_EVENT_PROCESSED = "app_amqp_event_processed";

    String NATS_EVENT_PROCESSED = "app_nats_event_processed";
    String NATS_EVENT_SKIPPED = "app_nats_event_skipped";
    String NATS_EVENT_PROCESSING_FAILED = "app_nats_event_failed";
    String NATS_REQUEST = "app_nats_request";


    String HTTP_REQUEST = "app_http_request";

    String NATS_PUBLISH = "app_nats_publish";
    String NATS_BROADCAST = "app_nats_broadcast";

    String INVALID_ACTION = "app_actions_invalid";
    String ACTION_HANDLE = "app_actions_handle";
}