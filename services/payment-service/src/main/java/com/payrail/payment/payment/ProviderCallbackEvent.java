package com.payrail.payment.payment;

/** Published by the in-process mock provider to simulate its asynchronous callback. */
public record ProviderCallbackEvent(String provider, ProviderCallback callback) {
}