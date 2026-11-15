package com.payrail.payment.ledger;

import com.payrail.payment.auth.AuthenticatedMerchant;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BalanceController {

    private final LedgerService ledgerService;

    public BalanceController(LedgerService ledgerService) {
        this.ledgerService = ledgerService;
    }

    /** How much Payrail owes the calling merchant, computed from the ledger. */
    @GetMapping("/v1/balance")
    public BalanceResponse getBalance(@AuthenticationPrincipal AuthenticatedMerchant merchant) {
        return new BalanceResponse(ledgerService.merchantPayableBalance(merchant.merchantId()), "INR");
    }
}