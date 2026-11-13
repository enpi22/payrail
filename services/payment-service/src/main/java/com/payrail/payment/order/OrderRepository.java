package com.payrail.payment.order;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

/**
 * Deliberately extends Repository, not JpaRepository: only merchant-scoped lookups exist,
 * so an unscoped findById can't be called by accident.
 */
public interface OrderRepository extends Repository<Order, UUID> {

    Order save(Order order);
    Order saveAndFlush(Order order);

    Optional<Order> findByIdAndMerchantId(UUID id, UUID merchantId);
}