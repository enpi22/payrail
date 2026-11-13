package com.payrail.payment.payment;

import com.payrail.payment.auth.AuthenticatedMerchant;
import com.payrail.payment.common.ApiException;
import com.payrail.payment.common.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/v1/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final IdempotencyService idempotencyService;

    public PaymentController(PaymentService paymentService, IdempotencyService idempotencyService) {
        this.paymentService = paymentService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> createPayment(@AuthenticationPrincipal AuthenticatedMerchant merchant,
                                           @RequestHeader(value = "Idempotency-Key", required = false) String key,
                                           @Valid @RequestBody CreatePaymentRequest request) {
        if (key == null) {
            // Unlike orders, a retried payment without a key could charge a customer twice.
            throw new ApiException(HttpStatus.BAD_REQUEST, "idempotency_key_required",
                    "Idempotency-Key header is required for payments");
        }
        return idempotencyService.executeOutsideTransaction(merchant.merchantId(), key, "POST /v1/payments", request,
                () -> {
                    PaymentResponse payment = paymentService.createPayment(merchant.merchantId(), request);
                    URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                            .path("/{id}").buildAndExpand(payment.id()).toUri();
                    return ResponseEntity.created(location).body(payment);
                });
    }

    @GetMapping("/{id}")
    public PaymentResponse getPayment(@AuthenticationPrincipal AuthenticatedMerchant merchant,
                                      @PathVariable UUID id) {
        return paymentService.getPayment(merchant.merchantId(), id);
    }
}