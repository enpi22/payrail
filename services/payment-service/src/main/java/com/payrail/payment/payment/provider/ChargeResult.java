package com.payrail.payment.payment.provider;

/** Every possible immediate answer from a provider. Sealed: the compiler knows these are the only three. */
public sealed interface ChargeResult {

    /** Accepted; the final outcome arrives later by callback. */
    record Accepted(String providerRef) implements ChargeResult {
    }

    /** Declined immediately; no money moved. */
    record Declined(String providerRef, String failureCode) implements ChargeResult {
    }

    /** No answer in time. The outcome is UNKNOWN; money may have moved. Never treat as a failure. */
    record TimedOut() implements ChargeResult {
    }
}