package com.payrail.payment.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Request to create an order. Amounts are in paise: ₹10.50 is 1050.
 */
public record CreateOrderRequest(

        @NotNull
        @Min(value = MIN_AMOUNT, message = "must be at least 100 paise (₹1)")
        @Max(value = MAX_AMOUNT, message = "must be at most 100000000 paise (₹10,00,000)")
        Long amount,

        @Pattern(regexp = "INR", message = "only INR is currently supported")
        String currency,

        @NotBlank
        @Size(max = 40)
        @Pattern(regexp = "^[A-Za-z0-9._/#-]+$",
                message = "may only contain letters, digits, and . _ / # -")
        String receipt
) {
    public static final long MIN_AMOUNT = 100L;
    public static final long MAX_AMOUNT = 100_000_000L;
    public static final String DEFAULT_CURRENCY = "INR";

    /** Currency is optional in the request and defaults to INR. */
    public String currencyOrDefault() {
        return currency == null ? DEFAULT_CURRENCY : currency;
    }
}