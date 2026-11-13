package com.payrail.payment.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.payment.TestcontainersConfiguration;
import com.payrail.payment.merchant.CreateMerchantRequest;
import com.payrail.payment.merchant.MerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Auto-callbacks are off: each test sends the provider callback itself, so timing can't cause flakiness. */
@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = "payrail.providers.mock.auto-callback=false")
@AutoConfigureMockMvc
class PaymentFlowTest {

    private static final String CALLBACK_SECRET = "mock-secret-change-me";

    @Autowired private MockMvc mockMvc;
    @Autowired private MerchantService merchantService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    // ───────────── helpers ─────────────

    private String newMerchantKey() {
        return merchantService.createMerchant(new CreateMerchantRequest("Test Store", null)).apiKey();
    }

    private String createOrder(String apiKey, long amount) throws Exception {
        String receipt = "R-" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/v1/orders")
                        .header(AUTHORIZATION, "Bearer " + apiKey)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"amount": %d, "receipt": "%s"}
                                """.formatted(amount, receipt)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private ResultActions pay(String apiKey, String orderId) throws Exception {
        return mockMvc.perform(post("/v1/payments")
                .header(AUTHORIZATION, "Bearer " + apiKey)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(APPLICATION_JSON)
                .content("""
                        {"order_id": "%s", "method": "UPI"}
                        """.formatted(orderId)));
    }

    private String payAndGetId(String apiKey, String orderId) throws Exception {
        String body = pay(apiKey, orderId).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    private String providerRefOf(String paymentId) {
        return jdbc.queryForObject("SELECT provider_ref FROM payments WHERE id = ?",
                String.class, UUID.fromString(paymentId));
    }

    private ResultActions callback(String paymentId, String providerRef, String status) throws Exception {
        String ref = providerRef == null ? "null" : "\"" + providerRef + "\"";
        return mockMvc.perform(post("/v1/provider-callbacks/mock")
                .header("X-Provider-Secret", CALLBACK_SECRET)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"payment_id": "%s", "provider_ref": %s, "status": "%s"}
                        """.formatted(paymentId, ref, status)));
    }

    private ResultActions getPayment(String apiKey, String paymentId) throws Exception {
        return mockMvc.perform(get("/v1/payments/" + paymentId).header(AUTHORIZATION, "Bearer " + apiKey));
    }

    private ResultActions getOrder(String apiKey, String orderId) throws Exception {
        return mockMvc.perform(get("/v1/orders/" + orderId).header(AUTHORIZATION, "Bearer " + apiKey));
    }

    // ───────────── happy path ─────────────

    @Test
    void acceptedPaymentSucceedsOnCallbackAndOrderBecomesPaid() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_000);

        String paymentId = payAndGetId(key, orderId);
        getPayment(key, paymentId).andExpect(jsonPath("$.status").value("PROCESSING"));
        getOrder(key, orderId).andExpect(jsonPath("$.status").value("ATTEMPTED"));

        callback(paymentId, providerRefOf(paymentId), "SUCCEEDED").andExpect(status().isNoContent());

        getPayment(key, paymentId).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        getOrder(key, orderId).andExpect(jsonPath("$.status").value("PAID"));
    }

    // ───────────── provider outcomes ─────────────

    @Test
    void immediateDeclineFailsThePayment() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_001); // ends in 01 → declined

        pay(key, orderId)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.failure_code").value("card_declined"));
        getOrder(key, orderId).andExpect(jsonPath("$.status").value("ATTEMPTED"));
    }

    @Test
    void timeoutIsNotAFailureThePaymentWaitsForTheCallback() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_002); // ends in 02 → times out

        String paymentId = payAndGetId(key, orderId);
        getPayment(key, paymentId).andExpect(jsonPath("$.status").value("PROCESSING"));
        assertThat(providerRefOf(paymentId)).isNull();

        callback(paymentId, "mock_late_ref", "SUCCEEDED").andExpect(status().isNoContent());

        getPayment(key, paymentId).andExpect(jsonPath("$.status").value("SUCCEEDED"));
        assertThat(providerRefOf(paymentId)).isEqualTo("mock_late_ref");
        getOrder(key, orderId).andExpect(jsonPath("$.status").value("PAID"));
    }

    // ───────────── callbacks ─────────────

    @Test
    void duplicateCallbackIsHarmless() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndGetId(key, createOrder(key, 100_000));
        String ref = providerRefOf(paymentId);

        callback(paymentId, ref, "SUCCEEDED").andExpect(status().isNoContent());
        callback(paymentId, ref, "SUCCEEDED").andExpect(status().isNoContent());

        getPayment(key, paymentId).andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void conflictingCallbackNeverChangesAFinalState() throws Exception {
        String key = newMerchantKey();
        String paymentId = payAndGetId(key, createOrder(key, 100_000));
        String ref = providerRefOf(paymentId);

        callback(paymentId, ref, "SUCCEEDED").andExpect(status().isNoContent());
        callback(paymentId, ref, "FAILED")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("conflicting_callback"));

        getPayment(key, paymentId).andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void callbackWithWrongSecretIsRejected() throws Exception {
        mockMvc.perform(post("/v1/provider-callbacks/mock")
                        .header("X-Provider-Secret", "wrong")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"payment_id": "%s", "status": "SUCCEEDED"}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isUnauthorized());
    }

    // ───────────── double-charge protection ─────────────

    @Test
    void secondPaymentWhileOneIsInFlightIsRejected() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_000);
        payAndGetId(key, orderId); // stays PROCESSING (no callback yet)

        pay(key, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("payment_in_progress"));
    }

    @Test
    void paidOrderCannotBePaidAgain() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_000);
        String paymentId = payAndGetId(key, orderId);
        callback(paymentId, providerRefOf(paymentId), "SUCCEEDED");

        pay(key, orderId)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("order_already_paid"));
    }

    @Test
    void customerCanRetryAfterADecline() throws Exception {
        String key = newMerchantKey();
        String orderId = createOrder(key, 100_001);
        pay(key, orderId).andExpect(jsonPath("$.status").value("FAILED"));

        // A failed payment isn't "active", so a new attempt is allowed.
        pay(key, orderId).andExpect(status().isCreated());
    }

    // ───────────── API rules ─────────────

    @Test
    void paymentsRequireAnIdempotencyKey() throws Exception {
        String key = newMerchantKey();
        mockMvc.perform(post("/v1/payments")
                        .header(AUTHORIZATION, "Bearer " + key)
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"order_id": "%s", "method": "UPI"}
                                """.formatted(createOrder(key, 100_000))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_required"));
    }

    @Test
    void merchantCannotPayAnotherMerchantsOrder() throws Exception {
        String orderId = createOrder(newMerchantKey(), 100_000);

        pay(newMerchantKey(), orderId)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }
}