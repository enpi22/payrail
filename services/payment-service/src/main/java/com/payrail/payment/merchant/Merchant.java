package com.payrail.payment.merchant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "merchants")
public class Merchant {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private String name;

    private String webhookUrl;

    @Column(nullable = false)
    private String webhookSecret;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    protected Merchant() {
        // required by JPA
    }

    public Merchant(String name, String webhookUrl, String webhookSecret) {
        this.name = name;
        this.webhookUrl = webhookUrl;
        this.webhookSecret = webhookSecret;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getWebhookUrl() { return webhookUrl; }
    public String getWebhookSecret() { return webhookSecret; }
    public Instant getCreatedAt() { return createdAt; }
}
