package com.payrail.payment.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

@Configuration
public class WebhookKafkaConfig {

    /**
     * Malformed events (poison pills) are logged and skipped: retrying can never fix them.
     * Every other failure, like the database being down, retries forever with backoff capped at 30s:
     * consumption pauses until the problem is fixed, and no event is ever dropped.
     */
    @Bean
    DefaultErrorHandler kafkaErrorHandler() {
        ExponentialBackOff backOff = new ExponentialBackOff(1_000L, 2.0);
        backOff.setMaxInterval(30_000L);
        DefaultErrorHandler handler = new DefaultErrorHandler(backOff);
        handler.addNotRetryableExceptions(MalformedEventException.class);
        return handler;
    }
}