package com.payrail.payment.order;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.payment.TestcontainersConfiguration;
import com.payrail.payment.merchant.CreateMerchantRequest;
import com.payrail.payment.merchant.MerchantCreatedResponse;
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
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class OrderIdempotencyTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private MerchantService merchantService;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;

    private MerchantCreatedResponse newMerchant() {
        return merchantService.createMerchant(new CreateMerchantRequest("Test Store", null));
    }

    private ResultActions postOrder(String apiKey, String idempotencyKey, long amount, String receipt) throws Exception {
        return mockMvc.perform(post("/v1/orders")
                .header(AUTHORIZATION, "Bearer " + apiKey)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(APPLICATION_JSON)
                .content("""
                        {"amount": %d, "receipt": "%s"}
                        """.formatted(amount, receipt)));
    }

    private String idOf(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asText();
    }

    private int ordersWithReceipt(UUID merchantId, String receipt) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE merchant_id = ? AND receipt = ?", Integer.class, merchantId, receipt);
    }

    @Test
    void retryWithSameKeyReturnsTheOriginalOrder() throws Exception {
        MerchantCreatedResponse merchant = newMerchant();

        MvcResult first = postOrder(merchant.apiKey(), "key-1", 100_000, "SHOE-1")
                .andExpect(status().isCreated()).andReturn();
        MvcResult retry = postOrder(merchant.apiKey(), "key-1", 100_000, "SHOE-1")
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andReturn();

        assertThat(idOf(retry)).isEqualTo(idOf(first));
        assertThat(ordersWithReceipt(merchant.merchantId(), "SHOE-1")).isEqualTo(1);
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() throws Exception {
        MerchantCreatedResponse merchant = newMerchant();
        postOrder(merchant.apiKey(), "key-1", 100_000, "SHOE-1").andExpect(status().isCreated());

        postOrder(merchant.apiKey(), "key-1", 500_000, "SHOE-1")
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("idempotency_key_reused"));
    }

    @Test
    void keysAreScopedPerMerchant() throws Exception {
        MvcResult a = postOrder(newMerchant().apiKey(), "shared-key", 100_000, "SHOE-1")
                .andExpect(status().isCreated()).andReturn();
        MvcResult b = postOrder(newMerchant().apiKey(), "shared-key", 100_000, "SHOE-1")
                .andExpect(status().isCreated()).andReturn();

        assertThat(idOf(a)).isNotEqualTo(idOf(b));
    }

    @Test
    void businessErrorsAreReplayedToo() throws Exception {
        MerchantCreatedResponse merchant = newMerchant();
        postOrder(merchant.apiKey(), "key-original", 100_000, "SHOE-1").andExpect(status().isCreated());

        postOrder(merchant.apiKey(), "key-dup", 100_000, "SHOE-1")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("duplicate_receipt"));
        postOrder(merchant.apiKey(), "key-dup", 100_000, "SHOE-1")
                .andExpect(status().isConflict())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.error.code").value("duplicate_receipt"));
    }

    @Test
    void overlongKeyIs400() throws Exception {
        postOrder(newMerchant().apiKey(), "k".repeat(256), 100_000, "SHOE-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("invalid_idempotency_key"));
    }

    @Test
    void concurrentRetriesWithSameKeyCreateExactlyOneOrder() throws Exception {
        MerchantCreatedResponse merchant = newMerchant();
        int threads = 10;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);

        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return postOrder(merchant.apiKey(), "race-key", 100_000, "RACE-1")
                        .andReturn().getResponse().getStatus();
            }));
        }
        ready.await();
        go.countDown();

        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();

        // Every response is either the order (original or replayed) or "still in progress, retry".
        assertThat(statuses).allMatch(s -> s == 201 || s == 409);
        assertThat(statuses).contains(201);
        assertThat(ordersWithReceipt(merchant.merchantId(), "RACE-1")).isEqualTo(1);
    }
}