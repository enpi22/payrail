package com.payrail.payment.order;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class CreateOrderRequestTest {

    private static Validator validator;

    @BeforeAll
    static void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    private Set<String> invalidFields(CreateOrderRequest request) {
        return validator.validate(request).stream()
                .map(violation -> violation.getPropertyPath().toString())
                .collect(Collectors.toSet());
    }

    @Test
    void validRequestPasses() {
        assertThat(invalidFields(new CreateOrderRequest(100_000L, "INR", "SHOE-1"))).isEmpty();
    }

    @Test
    void currencyIsOptionalAndDefaultsToInr() {
        CreateOrderRequest request = new CreateOrderRequest(100_000L, null, "SHOE-1");

        assertThat(invalidFields(request)).isEmpty();
        assertThat(request.currencyOrDefault()).isEqualTo("INR");
    }

    @Test
    void rejectsMissingAmount() {
        assertThat(invalidFields(new CreateOrderRequest(null, "INR", "SHOE-1"))).containsExactly("amount");
    }

    @ParameterizedTest
    @ValueSource(longs = {CreateOrderRequest.MIN_AMOUNT, CreateOrderRequest.MAX_AMOUNT})
    void acceptsAmountsExactlyAtTheLimits(long amount) {
        assertThat(invalidFields(new CreateOrderRequest(amount, "INR", "SHOE-1"))).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, 99, 100_000_001})
    void rejectsAmountsOutsideTheLimits(long amount) {
        assertThat(invalidFields(new CreateOrderRequest(amount, "INR", "SHOE-1"))).containsExactly("amount");
    }

    @ParameterizedTest
    @ValueSource(strings = {"USD", "inr", "IN", ""})
    void rejectsUnsupportedCurrencies(String currency) {
        assertThat(invalidFields(new CreateOrderRequest(100_000L, currency, "SHOE-1"))).containsExactly("currency");
    }
    @ParameterizedTest
    @ValueSource(strings = {"SHOE 5521", "SHOE\n5521", "<script>", "SHOE🙂", "a,b", "=1+1"})
    void rejectsReceiptsWithDisallowedCharacters(String receipt) {
        assertThat(invalidFields(new CreateOrderRequest(100_000L, "INR", receipt))).containsExactly("receipt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"SHOE-5521", "INV/2026/0042", "order_77.a", "#1001"})
    void acceptsReceiptsWithAllowedCharacters(String receipt) {
        assertThat(invalidFields(new CreateOrderRequest(100_000L, "INR", receipt))).isEmpty();
    }

    @Test
    void rejectsBlankReceipt() {
        assertThat(invalidFields(new CreateOrderRequest(100_000L, "INR", "  "))).containsExactly("receipt");
    }

    @Test
    void rejectsReceiptLongerThan40Characters() {
        assertThat(invalidFields(new CreateOrderRequest(100_000L, "INR", "R".repeat(41)))).containsExactly("receipt");
    }
}