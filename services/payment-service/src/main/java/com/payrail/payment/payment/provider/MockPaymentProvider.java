package com.payrail.payment.payment.provider;

import com.payrail.payment.payment.ProviderCallback;
import com.payrail.payment.payment.ProviderCallbackEvent;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A simulated provider. Outcomes are deterministic by the last two digits of the amount:
 *   01 → declined immediately          02 → times out, but the charge went through (SUCCEEDED callback)
 *   03 → accepted, then FAILED callback  other → accepted, then SUCCEEDED callback
 */
@Component
public class MockPaymentProvider implements PaymentProvider {

    public static final String NAME = "mock";

    private final ApplicationEventPublisher events;
    private final boolean autoCallback;
    private final long callbackDelayMs;
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public MockPaymentProvider(ApplicationEventPublisher events,
                               @Value("${payrail.providers.mock.auto-callback:true}") boolean autoCallback,
                               @Value("${payrail.providers.mock.callback-delay-ms:2000}") long callbackDelayMs) {
        this.events = events;
        this.autoCallback = autoCallback;
        this.callbackDelayMs = callbackDelayMs;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ChargeResult charge(ChargeRequest request) {
        String providerRef = "mock_" + UUID.randomUUID();
        return switch ((int) (request.amountMinor() % 100)) {
            case 1 -> new ChargeResult.Declined(providerRef, "card_declined");
            case 2 -> {
                // The response is "lost", but the charge actually happened. The callback tells the truth later.
                scheduleCallback(request.paymentId(), providerRef, ProviderCallback.Status.SUCCEEDED, null);
                yield new ChargeResult.TimedOut();
            }
            case 3 -> {
                scheduleCallback(request.paymentId(), providerRef, ProviderCallback.Status.FAILED, "bank_declined");
                yield new ChargeResult.Accepted(providerRef);
            }
            default -> {
                scheduleCallback(request.paymentId(), providerRef, ProviderCallback.Status.SUCCEEDED, null);
                yield new ChargeResult.Accepted(providerRef);
            }
        };
    }

    private void scheduleCallback(UUID paymentId, String providerRef, ProviderCallback.Status status, String failureCode) {
        if (!autoCallback) {
            return; // tests send callbacks explicitly, so timing never makes them flaky
        }
        ProviderCallback callback = new ProviderCallback(paymentId, providerRef, status, failureCode);
        scheduler.schedule(() -> events.publishEvent(new ProviderCallbackEvent(NAME, callback)),
                callbackDelayMs, TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    void shutdown() {
        scheduler.shutdownNow();
    }
}