package com.payrail.payment.outbox;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicsConfig {

    public static final String PAYMENT_EVENTS_TOPIC = "payrail.payment-events";

    /** Created at startup if missing (auto-creation is disabled on the broker, on purpose). */
    @Bean
    NewTopic paymentEventsTopic() {
        return TopicBuilder.name(PAYMENT_EVENTS_TOPIC).partitions(3).replicas(1).build();
    }
}