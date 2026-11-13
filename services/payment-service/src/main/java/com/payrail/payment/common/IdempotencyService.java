package com.payrail.payment.common;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.payment.merchant.ApiKeyGenerator;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Makes a write endpoint safe to retry: the same Idempotency-Key always gets the same response,
 * and the action runs at most once.
 */
@Service
public class IdempotencyService {

    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_KEY_LENGTH = 255;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;
    private final ObjectMapper objectMapper;

    public IdempotencyService(JdbcTemplate jdbc, TransactionTemplate transactionTemplate, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.objectMapper = objectMapper;
    }

    /** For single-transaction actions: the action and "mark completed" commit together. */
    public ResponseEntity<?> execute(UUID merchantId, String key, String endpoint, Object request,
                                     Supplier<ResponseEntity<?>> action) {
        return run(merchantId, key, endpoint, request, action, true);
    }

    /**
     * For multi-step actions that manage their own transactions, such as calling an external provider.
     * Trade-off: a crash between the action finishing and "mark completed" leaves the key IN_PROGRESS.
     */
    public ResponseEntity<?> executeOutsideTransaction(UUID merchantId, String key, String endpoint, Object request,
                                                       Supplier<ResponseEntity<?>> action) {
        return run(merchantId, key, endpoint, request, action, false);
    }

    private ResponseEntity<?> run(UUID merchantId, String key, String endpoint, Object request,
                                  Supplier<ResponseEntity<?>> action, boolean inTransaction) {
        validateKey(key);
        String requestHash = ApiKeyGenerator.sha256Hex(endpoint + "\n" + toJson(request));

        if (!tryAcquire(merchantId, key, requestHash)) {
            return replayOrReject(merchantId, key, requestHash);
        }

        try {
            if (!inTransaction) {
                ResponseEntity<?> response = action.get();
                complete(merchantId, key, response.getStatusCode().value(), toJson(response.getBody()));
                return response;
            }
            return transactionTemplate.execute(status -> {
                ResponseEntity<?> response = action.get();
                complete(merchantId, key, response.getStatusCode().value(), toJson(response.getBody()));
                return response;
            });
        } catch (ApiException ex) {
            complete(merchantId, key, ex.getStatus().value(),
                    toJson(ApiErrorResponse.of(ex.getCode(), ex.getMessage())));
            throw ex;
        } catch (RuntimeException ex) {
            release(merchantId, key);
            throw ex;
        }
    }

    /** The INSERT is the lock: the primary key lets exactly one request with this key win. */
    private boolean tryAcquire(UUID merchantId, String key, String requestHash) {
        int inserted = jdbc.update("""
                INSERT INTO idempotency_keys (merchant_id, key, request_hash, status)
                VALUES (?, ?, ?, 'IN_PROGRESS')
                ON CONFLICT (merchant_id, key) DO NOTHING
                """, merchantId, key, requestHash);
        return inserted == 1;
    }

    private ResponseEntity<?> replayOrReject(UUID merchantId, String key, String requestHash) {
        Map<String, Object> row;
        try {
            row = jdbc.queryForMap("""
                    SELECT request_hash, status, response_code, response_body::text AS response_body
                    FROM idempotency_keys WHERE merchant_id = ? AND key = ?
                    """, merchantId, key);
        } catch (EmptyResultDataAccessException ex) {
            throw inProgress(); // the other request just failed and released the key; client should retry
        }

        if (!requestHash.equals(row.get("request_hash"))) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "idempotency_key_reused",
                    "This Idempotency-Key was already used with a different request");
        }
        if ("IN_PROGRESS".equals(row.get("status"))) {
            throw inProgress();
        }
        return ResponseEntity.status((Integer) row.get("response_code"))
                .contentType(MediaType.APPLICATION_JSON)
                .header(REPLAYED_HEADER, "true")
                .body((String) row.get("response_body"));
    }

    private void complete(UUID merchantId, String key, int responseCode, String responseBody) {
        jdbc.update("""
                UPDATE idempotency_keys
                SET status = 'COMPLETED', response_code = ?, response_body = ?::jsonb
                WHERE merchant_id = ? AND key = ?
                """, responseCode, responseBody, merchantId, key);
    }

    private void release(UUID merchantId, String key) {
        jdbc.update("DELETE FROM idempotency_keys WHERE merchant_id = ? AND key = ? AND status = 'IN_PROGRESS'",
                merchantId, key);
    }

    private static void validateKey(String key) {
        if (key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_idempotency_key",
                    "Idempotency-Key must be 1 to " + MAX_KEY_LENGTH + " characters");
        }
    }

    private static ApiException inProgress() {
        return new ApiException(HttpStatus.CONFLICT, "idempotency_request_in_progress",
                "A request with this Idempotency-Key is still being processed. Retry shortly.");
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize to JSON", e);
        }
    }
}