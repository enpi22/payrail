package com.payrail.payment.payment;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** What a provider tells us after the fact: the final outcome of a payment. */
public record ProviderCallback(
        @NotNull UUID paymentId,
        String providerRef,
        @NotNull Status status,
        String failureCode
) {
    public enum Status {
        SUCCEEDED,
        FAILED
    }
}