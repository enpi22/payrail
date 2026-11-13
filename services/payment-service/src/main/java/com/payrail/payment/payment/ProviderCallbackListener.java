package com.payrail.payment.payment;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

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