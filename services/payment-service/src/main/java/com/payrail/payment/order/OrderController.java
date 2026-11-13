package com.payrail.payment.order;

import com.payrail.payment.auth.AuthenticatedMerchant;
import com.payrail.payment.common.IdempotencyService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.util.UUID;
import java.util.function.Supplier;

@RestController
@RequestMapping("/v1/orders")
public class OrderController {

    private final OrderService orderService;
    private final IdempotencyService idempotencyService;

    public OrderController(OrderService orderService, IdempotencyService idempotencyService) {
        this.orderService = orderService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping
    public ResponseEntity<?> createOrder(@AuthenticationPrincipal AuthenticatedMerchant merchant,
                                         @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
                                         @Valid @RequestBody CreateOrderRequest request) {
        Supplier<ResponseEntity<?>> action = () -> {
            OrderResponse order = orderService.createOrder(merchant.merchantId(), request);
            URI location = ServletUriComponentsBuilder.fromCurrentRequest()
                    .path("/{id}").buildAndExpand(order.id()).toUri();
            return ResponseEntity.created(location).body(order);
        };

        if (idempotencyKey == null) {
            return action.get(); // the key is optional, like Stripe
        }
        return idempotencyService.execute(merchant.merchantId(), idempotencyKey, "POST /v1/orders", request, action);
    }

    @GetMapping("/{id}")
    public OrderResponse getOrder(@AuthenticationPrincipal AuthenticatedMerchant merchant,
                                  @PathVariable UUID id) {
        return orderService.getOrder(merchant.merchantId(), id);
    }
}