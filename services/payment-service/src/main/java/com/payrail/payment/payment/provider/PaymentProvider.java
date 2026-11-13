package com.payrail.payment.payment.provider;

import com.payrail.payment.payment.PaymentService;
import com.payrail.payment.payment.ProviderCallbackEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** A payment provider (bank or processor). Real adapters, like Razorpay, would implement this too. */
public interface PaymentProvider {

    String name();

    ChargeResult charge(ChargeRequest request);

    @Component
    class ProviderCallbackListener {

        private static final Logger log = LoggerFactory.getLogger(ProviderCallbackListener.class);

        private final PaymentService paymentService;

        ProviderCallbackListener(PaymentService paymentService) {
            this.paymentService = paymentService;
        }

        @EventListener
        public void onCallback(ProviderCallbackEvent event) {
            try {
                paymentService.handleCallback(event.provider(), event.callback());
            } catch (RuntimeException ex) {
                log.error("Failed to process provider callback paymentId={}", event.callback().paymentId(), ex);
            }
        }
    }
}