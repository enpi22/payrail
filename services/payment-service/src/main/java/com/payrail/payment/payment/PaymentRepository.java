package com.payrail.payment.payment;

import org.springframework.data.repository.Repository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends Repository<Payment, UUID> {

    Payment saveAndFlush(Payment payment);

    /** Merchant-facing lookups must use this. */
    Optional<Payment> findByIdAndMerchantId(UUID id, UUID merchantId);

    /** Internal use only (provider callbacks, our own follow-up steps). Never call from merchant-facing code. */
    Optional<Payment> findById(UUID id);
}