package com.payrail.payment.payment;

import com.payrail.payment.common.ApiException;
import com.payrail.payment.common.ConstraintViolations;
import com.payrail.payment.order.Order;
import com.payrail.payment.order.OrderRepository;
import com.payrail.payment.order.OrderStatus;
import com.payrail.payment.payment.provider.ChargeRequest;
import com.payrail.payment.payment.provider.ChargeResult;
import com.payrail.payment.payment.provider.PaymentProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);
    static final String ONE_ACTIVE_PAYMENT_CONSTRAINT = "uq_payments_one_active_per_order";

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentProvider provider;
    private final TransactionTemplate transactionTemplate;

    public PaymentService(PaymentRepository paymentRepository, OrderRepository orderRepository,
                          PaymentProvider provider, TransactionTemplate transactionTemplate) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.provider = provider;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Deliberately NOT @Transactional: the provider call happens between two short transactions.
     * Never hold a database connection open across a network call.
     */
    public PaymentResponse createPayment(UUID merchantId, CreatePaymentRequest request) {
        // 1. Validate the order and record the payment as CREATED.
        Payment payment = transactionTemplate.execute(status -> createPendingPayment(merchantId, request));

        // 2. Call the provider, outside any transaction.
        ChargeResult result = provider.charge(new ChargeRequest(
                payment.getId(), payment.getAmountMinor(), payment.getCurrency(), payment.getMethod()));

        // 3. Record what the provider said.
        return transactionTemplate.execute(status -> applyChargeResult(payment.getId(), result));
    }

    @Transactional(readOnly = true)
    public PaymentResponse getPayment(UUID merchantId, UUID paymentId) {
        return paymentRepository.findByIdAndMerchantId(paymentId, merchantId)
                .map(PaymentResponse::from)
                .orElseThrow(() -> ApiException.notFound("No payment found with id " + paymentId));
    }

    /** The provider's final word. Safe to receive twice; a final state never changes. */
    @Transactional
    public void handleCallback(String providerName, ProviderCallback callback) {
        Payment payment = paymentRepository.findById(callback.paymentId())
                .filter(p -> providerName.equals(p.getProvider()))
                .orElseThrow(() -> ApiException.notFound("Unknown payment " + callback.paymentId()));

        PaymentStatus target = callback.status() == ProviderCallback.Status.SUCCEEDED
                ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED;

        if (payment.isTerminal()) {
            if (payment.getStatus() == target) {
                log.info("Duplicate provider callback ignored paymentId={}", payment.getId());
                return;
            }
            log.error("Conflicting provider callback paymentId={} current={} callback={}",
                    payment.getId(), payment.getStatus(), target);
            throw ApiException.conflict("conflicting_callback", "Payment is already " + payment.getStatus());
        }

        payment.attachProviderRef(callback.providerRef());
        switch (callback.status()) {
            case SUCCEEDED -> {
                payment.markSucceeded();
                orderOf(payment).markPaid();
                log.info("Payment succeeded paymentId={} orderId={}", payment.getId(), payment.getOrderId());
            }
            case FAILED -> {
                payment.markFailed(callback.failureCode() != null ? callback.failureCode() : "provider_failed");
                log.info("Payment failed paymentId={} reason={}", payment.getId(), payment.getFailureCode());
            }
        }
        // Saturday: ledger postings and the outbox event are added here, in this same transaction.
    }

    private Payment createPendingPayment(UUID merchantId, CreatePaymentRequest request) {
        Order order = orderRepository.findByIdAndMerchantId(request.orderId(), merchantId)
                .orElseThrow(() -> ApiException.notFound("No order found with id " + request.orderId()));
        if (order.getStatus() == OrderStatus.PAID) {
            throw ApiException.conflict("order_already_paid", "Order " + order.getId() + " is already paid");
        }
        order.markAttempted();
        try {
            return paymentRepository.saveAndFlush(new Payment(order, request.method(), provider.name()));
        } catch (DataIntegrityViolationException ex) {
            if (ConstraintViolations.isViolationOf(ex, ONE_ACTIVE_PAYMENT_CONSTRAINT)) {
                throw ApiException.conflict("payment_in_progress",
                        "Another payment for this order is still in progress");
            }
            throw ex;
        }
    }

    private PaymentResponse applyChargeResult(UUID paymentId, ChargeResult result) {
        Payment payment = paymentRepository.findById(paymentId).orElseThrow();
        if (payment.isTerminal()) {
            // A fast callback already decided the outcome; it has the final word.
            return PaymentResponse.from(payment);
        }
        switch (result) {
            case ChargeResult.Accepted accepted -> payment.markProcessing(accepted.providerRef());
            case ChargeResult.Declined declined -> {
                payment.markProcessing(declined.providerRef());
                payment.markFailed(declined.failureCode());
            }
            case ChargeResult.TimedOut timedOut -> {
                log.warn("Provider timed out; outcome unknown, awaiting callback paymentId={}", paymentId);
                payment.markProcessing(null);
            }
        }
        return PaymentResponse.from(payment);
    }

    private Order orderOf(Payment payment) {
        return orderRepository.findByIdAndMerchantId(payment.getOrderId(), payment.getMerchantId())
                .orElseThrow(() -> new IllegalStateException("Order missing for payment " + payment.getId()));
    }
}