package com.payrail.payment.order;

import com.payrail.payment.common.ApiException;
import com.payrail.payment.common.ConstraintViolations;
import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    static final String RECEIPT_UNIQUE_CONSTRAINT = "uq_orders_merchant_receipt";

    private final OrderRepository orderRepository;

    public OrderService(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @Transactional
    public OrderResponse createOrder(UUID merchantId, CreateOrderRequest request) {
        Order order = new Order(merchantId, request.amount(), request.currencyOrDefault(), request.receipt());
        try {
            // Flush now: the database's unique constraint is the source of truth for duplicates.
            Order saved = orderRepository.saveAndFlush(order);
            log.info("Order created orderId={} merchantId={}", saved.getId(), merchantId);
            return OrderResponse.from(saved);
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, RECEIPT_UNIQUE_CONSTRAINT)) {
                throw ApiException.conflict("duplicate_receipt",
                        "An order with receipt '" + request.receipt() + "' already exists");
            }
            throw ex; // a different integrity problem is a real bug, so let it surface as a 500
        }
    }

    @Transactional(readOnly = true)
    public OrderResponse getOrder(UUID merchantId, UUID orderId) {
        return orderRepository.findByIdAndMerchantId(orderId, merchantId)
                .map(OrderResponse::from)
                // Same message whether the order doesn't exist or belongs to someone else.
                .orElseThrow(() -> ApiException.notFound("No order found with id " + orderId));
    }

    private static boolean isViolationOf(DataIntegrityViolationException ex, String constraintName) {
        for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
            if (cause instanceof ConstraintViolationException violation) {
                return constraintName.equalsIgnoreCase(violation.getConstraintName());
            }
        }
        return false;
    }
}