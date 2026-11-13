package com.payrail.payment.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "orders") // "order" is a reserved SQL word
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID merchantId; // reference by ID, not a @ManyToOne

    @Column(nullable = false, updatable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(nullable = false, updatable = false)
    private String currency;

    @Column(nullable = false, updatable = false)
    private String receipt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Order() {
        // required by JPA
    }

    public Order(UUID merchantId, long amountMinor, String currency, String receipt) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Order amount must be positive");
        }
        this.merchantId = Objects.requireNonNull(merchantId, "merchantId");
        this.amountMinor = amountMinor;
        this.currency = Objects.requireNonNull(currency, "currency");
        this.receipt = Objects.requireNonNull(receipt, "receipt");
        this.status = OrderStatus.CREATED;

    }
    /** A payment attempt has started. */
    public void markAttempted() {
        if (status == OrderStatus.PAID) {
            throw new IllegalStateException("Order " + id + " is already paid");
        }
        status = OrderStatus.ATTEMPTED;
    }

    /** A payment for this order succeeded. */
    public void markPaid() {
        if (status == OrderStatus.PAID) {
            throw new IllegalStateException("Order " + id + " is already paid");
        }
        status = OrderStatus.PAID;
    }

    public UUID getId() { return id; }
    public UUID getMerchantId() { return merchantId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public String getReceipt() { return receipt; }
    public OrderStatus getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
}