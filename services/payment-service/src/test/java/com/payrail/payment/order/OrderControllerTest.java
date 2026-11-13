package com.payrail.payment.order;

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
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.HttpHeaders.LOCATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.http.MediaType.TEXT_PLAIN;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    // ───────────── helpers ─────────────

    private String newMerchantKey() {
        return merchantService.createMerchant(new CreateMerchantRequest("Test Store", null)).apiKey();
    }

    private static String orderJson(long amount, String receipt) {
        return """
                {"amount": %d, "receipt": "%s"}
                """.formatted(amount, receipt);
    }

    private ResultActions postOrder(String apiKey, String json) throws Exception {
        return mockMvc.perform(post("/v1/orders")
                .header(AUTHORIZATION, "Bearer " + apiKey)
                .contentType(APPLICATION_JSON)
                .content(json));
    }

    private String createOrderAndGetId(String apiKey, String receipt) throws Exception {
        String body = postOrder(apiKey, orderJson(100_000, receipt))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).get("id").asText();
    }

    // ───────────── creating orders ─────────────

    @Test
    void createReturns201WithOrderBodyAndLocationHeader() throws Exception {
        MvcResult result = postOrder(newMerchantKey(), orderJson(100_000, "SHOE-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(100_000))
                .andExpect(jsonPath("$.currency").value("INR"))
                .andExpect(jsonPath("$.receipt").value("SHOE-1"))
                .andExpect(jsonPath("$.status").value("CREATED"))
                .andReturn();

        String id = objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
        assertThat(result.getResponse().getHeader(LOCATION)).endsWith("/v1/orders/" + id);
    }

    @Test
    void statusInRequestBodyIsIgnored() throws Exception {
        postOrder(newMerchantKey(), """
                {"amount": 100000, "receipt": "SHOE-1", "status": "PAID"}
                """)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CREATED"));
    }

    @Test
    void validationErrorNamesTheFieldInSnakeCase() throws Exception {
        postOrder(newMerchantKey(), """
                {"amount": 100000}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_failed"))
                .andExpect(jsonPath("$.error.field_errors[0].field").value("receipt"));
    }

    @Test
    void decimalAmountIsRejectedNotTruncated() throws Exception {
        postOrder(newMerchantKey(), """
                {"amount": 100000.5, "receipt": "SHOE-1"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
    }

    @Test
    void stringAmountIsRejectedNotCoerced() throws Exception {
        postOrder(newMerchantKey(), """
                {"amount": "100000", "receipt": "SHOE-1"}
                """)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("malformed_request"));
    }

    @Test
    void wrongContentTypeIs415NotA500() throws Exception {
        mockMvc.perform(post("/v1/orders")
                        .header(AUTHORIZATION, "Bearer " + newMerchantKey())
                        .contentType(TEXT_PLAIN)
                        .content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.error.code").value("unsupported_media_type"));
    }

    @Test
    void duplicateReceiptIs409() throws Exception {
        String key = newMerchantKey();
        createOrderAndGetId(key, "SHOE-1");

        postOrder(key, orderJson(50_000, "SHOE-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate_receipt"));
    }

    @Test
    void creatingAnOrderRequiresAnApiKey() throws Exception {
        mockMvc.perform(post("/v1/orders").contentType(APPLICATION_JSON).content(orderJson(100_000, "SHOE-1")))
                .andExpect(status().isUnauthorized());
    }

    // ───────────── fetching orders ─────────────

    @Test
    void merchantCanFetchItsOwnOrder() throws Exception {
        String key = newMerchantKey();
        String id = createOrderAndGetId(key, "SHOE-1");

        mockMvc.perform(get("/v1/orders/" + id).header(AUTHORIZATION, "Bearer " + key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.receipt").value("SHOE-1"));
    }

    @Test
    void anotherMerchantsOrderIsIndistinguishableFromAMissingOne() throws Exception {
        String id = createOrderAndGetId(newMerchantKey(), "SHOE-1");
        String otherKey = newMerchantKey();

        String otherMerchantsOrder = mockMvc.perform(get("/v1/orders/" + id).header(AUTHORIZATION, "Bearer " + otherKey))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String missingOrder = mockMvc.perform(get("/v1/orders/" + java.util.UUID.randomUUID())
                        .header(AUTHORIZATION, "Bearer " + otherKey))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();

        // Same error code either way: the response reveals nothing about whether the order exists.
        assertThat(objectMapper.readTree(otherMerchantsOrder).at("/error/code").asText())
                .isEqualTo(objectMapper.readTree(missingOrder).at("/error/code").asText())
                .isEqualTo("not_found");
    }

    @Test
    void invalidOrderIdIs400() throws Exception {
        mockMvc.perform(get("/v1/orders/not-a-uuid").header(AUTHORIZATION, "Bearer " + newMerchantKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_parameter"));
    }

    // ───────────── concurrency ─────────────

    @Test
    void concurrentRequestsWithSameReceiptCreateExactlyOneOrder() throws Exception {
        String key = newMerchantKey();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch allReady = new CountDownLatch(threads);
        CountDownLatch startSignal = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                allReady.countDown();   // "I'm ready"
                startSignal.await();    // wait until everyone is ready, then go together
                return postOrder(key, orderJson(100_000, "RACE-1")).andReturn().getResponse().getStatus();
            }));
        }
        allReady.await();
        startSignal.countDown();        // fire all 10 requests at once

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        assertThat(statuses).filteredOn(s -> s == 201).hasSize(1);
        assertThat(statuses).filteredOn(s -> s == 409).hasSize(threads - 1);

        Integer rows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE receipt = 'RACE-1'", Integer.class);
        assertThat(rows).isEqualTo(1);
    }
}