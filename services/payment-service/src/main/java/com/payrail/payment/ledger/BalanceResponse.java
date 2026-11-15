package com.payrail.payment.ledger;

public record BalanceResponse(long pendingSettlement, String currency) {
}