package com.payrail.payment.webhook;

/** An event that can never be processed (bad JSON, missing fields). Skipped instead of retried forever. */
public class MalformedEventException extends RuntimeException {

    public MalformedEventException(String message) {
        super(message);
    }

    public MalformedEventException(String message, Throwable cause) {
        super(message, cause);
    }
}