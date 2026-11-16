package com.payrail.payment.webhook;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;

/** Delivers due webhooks. No database transaction is ever held while waiting on a merchant's server. */
@Component
public class WebhookSender {

    private static final Logger log = LoggerFactory.getLogger(WebhookSender.class);

    private final WebhookDeliveryStore store;
    private final WebhookTargetGuard guard;
    private final HttpClient http;
    private final int batchSize;
    private final int leaseSeconds;
    private final Duration requestTimeout;
    private final long[] retryDelaysSeconds;

    public WebhookSender(WebhookDeliveryStore store, WebhookTargetGuard guard,
                         @Value("${payrail.webhooks.batch-size:50}") int batchSize,
                         @Value("${payrail.webhooks.lease-seconds:60}") int leaseSeconds,
                         @Value("${payrail.webhooks.request-timeout-ms:10000}") long requestTimeoutMs,
                         @Value("${payrail.webhooks.retry-delays-seconds:60,300,1800,7200,21600}") long[] retryDelaysSeconds) {
        this.store = store;
        this.guard = guard;
        this.batchSize = batchSize;
        this.leaseSeconds = leaseSeconds;
        this.requestTimeout = Duration.ofMillis(requestTimeoutMs);
        this.retryDelaysSeconds = retryDelaysSeconds;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER) // a public URL must not redirect us into a private network
                .build();
    }

    @Scheduled(fixedDelayString = "${payrail.webhooks.poll-interval-ms:1000}")
    public void dispatchDue() {
        List<WebhookDeliveryStore.ClaimedDelivery> batch = store.claimDue(batchSize, leaseSeconds);
        if (batch.isEmpty()) {
            return;
        }
        // One virtual thread per delivery: a slow merchant only slows down their own webhook.
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (WebhookDeliveryStore.ClaimedDelivery delivery : batch) {
                pool.submit(() -> deliver(delivery));
            }
        } // close() waits for every delivery in the batch to finish
    }

    void deliver(WebhookDeliveryStore.ClaimedDelivery delivery) {
        try {
            if (delivery.webhookUrl() == null) {
                store.markDead(delivery.id(), null, "Merchant has no webhook URL");
                return;
            }
            URI target = URI.create(delivery.webhookUrl());
            guard.check(target);

            long now = Instant.now().getEpochSecond();
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(requestTimeout)
                    .header("Content-Type", "application/json")
                    .header("User-Agent", "Payrail-Webhooks/1.0")
                    .header(WebhookSigner.SIGNATURE_HEADER, WebhookSigner.sign(delivery.webhookSecret(), now, delivery.payload()))
                    .header("Payrail-Event-Id", delivery.eventId().toString())
                    .header("Payrail-Event-Type", delivery.eventType())
                    .POST(HttpRequest.BodyPublishers.ofString(delivery.payload()))
                    .build();

            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                store.markDelivered(delivery.id(), status);
                log.info("Webhook delivered eventId={} attempt={} status={}", delivery.eventId(), delivery.attempts(), status);
            } else {
                handleFailure(delivery, status, "HTTP " + status);
            }
        } catch (WebhookTargetGuard.UnsafeTargetException e) {
            store.markDead(delivery.id(), null, e.getMessage());
            log.warn("Webhook blocked as unsafe eventId={}: {}", delivery.eventId(), e.getMessage());
        } catch (IllegalArgumentException e) {
            store.markDead(delivery.id(), null, "Invalid webhook URL");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); // the lease expires and the delivery is retried later
        } catch (IOException e) {
            // Timeouts, refused connections, DNS failures: worth retrying.
            handleFailure(delivery, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Unexpected error delivering webhook eventId={}", delivery.eventId(), e);
        }
    }

    private void handleFailure(WebhookDeliveryStore.ClaimedDelivery delivery, Integer status, String error) {
        int maxAttempts = retryDelaysSeconds.length + 1;
        if (delivery.attempts() >= maxAttempts) {
            store.markDead(delivery.id(), status, error);
            log.warn("Webhook dead after {} attempts eventId={} lastError={}", delivery.attempts(), delivery.eventId(), error);
            return;
        }
        long baseMillis = retryDelaysSeconds[delivery.attempts() - 1] * 1000;
        // ±20% jitter, so a recovering merchant isn't hit by every retry in the same instant.
        long delayMillis = Math.round(baseMillis * (0.8 + 0.4 * ThreadLocalRandom.current().nextDouble()));
        store.scheduleRetry(delivery.id(), status, error, delayMillis);
        log.info("Webhook failed, retrying in {}ms eventId={} attempt={} error={}",
                delayMillis, delivery.eventId(), delivery.attempts(), error);
    }
}