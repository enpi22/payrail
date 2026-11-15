package com.payrail.payment.ledger;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Records money movements as double-entry postings. Debits are positive, credits negative,
 * and PostgreSQL rejects any transaction whose entries don't sum to zero (see V1 migration).
 */
@Service
public class LedgerService {

    static final String FEE_REVENUE_ACCOUNT = "fee_revenue";

    private final JdbcTemplate jdbc;
    private final long feeBasisPoints;

    public LedgerService(JdbcTemplate jdbc, @Value("${payrail.fees.basis-points:200}") long feeBasisPoints) {
        this.jdbc = jdbc;
        this.feeBasisPoints = feeBasisPoints;
    }

    /**
     * Posts a captured payment. MANDATORY: must join the caller's transaction, so the postings
     * commit atomically with the payment becoming SUCCEEDED. Calling it without one is a bug.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordPaymentCaptured(UUID paymentId, UUID merchantId, String provider, long amountMinor) {
        long fee = feeFor(amountMinor, feeBasisPoints);
        UUID transactionId = UUID.randomUUID();

        // Unique (reference_type, reference_id, description): the same payment can never be posted twice.
        jdbc.update("""
                INSERT INTO ledger_transactions (id, reference_type, reference_id, description)
                VALUES (?, 'payment', ?, 'payment.captured')
                """, transactionId, paymentId);

        insertEntry(transactionId, account("provider_receivable:" + provider, "ASSET", null), amountMinor);
        insertEntry(transactionId, account("merchant_payable:" + merchantId, "LIABILITY", merchantId),
                -(amountMinor - fee));
        if (fee > 0) {
            insertEntry(transactionId, account(FEE_REVENUE_ACCOUNT, "REVENUE", null), -fee);
        }
    }

    /** What Payrail owes the merchant, pending settlement. Liabilities are credit-normal, hence the sign flip. */
    @Transactional(readOnly = true)
    public long merchantPayableBalance(UUID merchantId) {
        Long sum = jdbc.queryForObject("""
                SELECT COALESCE(SUM(e.amount_minor), 0)
                FROM ledger_entries e
                JOIN ledger_accounts a ON a.id = e.account_id
                WHERE a.code = ?
                """, Long.class, "merchant_payable:" + merchantId);
        return -sum;
    }

    /** Fee in paise, rounded half-up using integer arithmetic only (no floating point near money). */
    static long feeFor(long amountMinor, long basisPoints) {
        return (amountMinor * basisPoints + 5_000) / 10_000;
    }

    /** Get-or-create an account. ON CONFLICT makes concurrent first-time creation safe. */
    private UUID account(String code, String type, UUID merchantId) {
        jdbc.update("""
                INSERT INTO ledger_accounts (code, type, merchant_id)
                VALUES (?, ?, ?)
                ON CONFLICT (code) DO NOTHING
                """, code, type, merchantId);
        return jdbc.queryForObject("SELECT id FROM ledger_accounts WHERE code = ?", UUID.class, code);
    }

    private void insertEntry(UUID transactionId, UUID accountId, long amountMinor) {
        jdbc.update("INSERT INTO ledger_entries (transaction_id, account_id, amount_minor) VALUES (?, ?, ?)",
                transactionId, accountId, amountMinor);
    }
}