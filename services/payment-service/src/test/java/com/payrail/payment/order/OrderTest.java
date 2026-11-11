package com.payrail.payment.order;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void newOrderStartsInCreatedStatus() {
        Order order = new Order(UUID.randomUUID(), 100_000, "INR", "SHOE-1");
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void rejectsNonPositiveAmount() {
        assertThatThrownBy(() -> new Order(UUID.randomUUID(), 0, "INR", "SHOE-1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Order(UUID.randomUUID(), -500, "INR", "SHOE-1"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}