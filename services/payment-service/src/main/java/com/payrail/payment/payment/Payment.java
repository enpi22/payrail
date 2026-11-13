package com.payrail.payment.payment;

import com.payrail.payment.order.Order;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID orderId;

    @Column(nullable = false, updatable = false)
    private UUID merchantId;

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    private String provider;
    private String providerRef;
    private String failureCode;

    @Version
    private int version; // optimistic locking: concurrent updates can't silently overwrite each other

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(nullable = false)
    private Instant updatedAt;

    protected Payment() {
        // required by JPA
    }

    /** The provider is chosen up front (routing happens before the charge). */
    public Payment(Order order, PaymentMethod method, String provider) {
        this.orderId = order.getId();
        this.merchantId = order.getMerchantId();
        this.amountMinor = order.getAmountMinor(); // snapshot of what is being charged
        this.currency = order.getCurrency();
        this.method = Objects.requireNonNull(method, "method");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.status = PaymentStatus.CREATED;
    }

    /** The provider accepted the charge, or timed out. Either way the outcome is not known yet. */
    public void markProcessing(String providerRef) {
        requireStatus(PaymentStatus.CREATED);
        this.providerRef = providerRef; // null after a timeout
        this.status = PaymentStatus.PROCESSING;
    }

    public void markSucceeded() {
        requireStatus(PaymentStatus.CREATED, PaymentStatus.PROCESSING);
        this.status = PaymentStatus.SUCCEEDED;
    }

    public void markFailed(String failureCode) {
        requireStatus(PaymentStatus.CREATED, PaymentStatus.PROCESSING);
        this.failureCode = failureCode;
        this.status = PaymentStatus.FAILED;
    }

    /** Records the provider's transaction id; a different id for the same payment is a serious inconsistency. */
    public void attachProviderRef(String ref) {
        if (ref == null) {
            return;
        }
        if (providerRef == null) {
            providerRef = ref;
        } else if (!providerRef.equals(ref)) {
            throw new IllegalStateException("Provider reference mismatch for payment " + id);
        }
    }

    public boolean isTerminal() {
        return status == PaymentStatus.SUCCEEDED || status == PaymentStatus.FAILED;
    }

    private void requireStatus(PaymentStatus... allowed) {
        for (PaymentStatus candidate : allowed) {
            if (status == candidate) {
                return;
            }
        }
        throw new IllegalStateException("Payment " + id + " cannot change state from " + status);
    }

    public UUID getId() { return id; }
    public UUID getOrderId() { return orderId; }
    public UUID getMerchantId() { return merchantId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentMethod getMethod() { return method; }
    public PaymentStatus getStatus() { return status; }
    public String getProvider() { return provider; }
    public String getProviderRef() { return providerRef; }
    public String getFailureCode() { return failureCode; }
    public Instant getCreatedAt() { return createdAt; }
}