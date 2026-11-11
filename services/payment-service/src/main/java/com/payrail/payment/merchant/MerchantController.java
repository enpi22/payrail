package com.payrail.payment.merchant;

import com.payrail.payment.auth.AuthenticatedMerchant;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/v1/merchants")
public class MerchantController {

    private final MerchantService merchantService;

    public MerchantController(MerchantService merchantService) {
        this.merchantService = merchantService;
    }

    // In a real system this is an internal/admin action. We'll protect it in a later step.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public MerchantCreatedResponse createMerchant(@Valid @RequestBody CreateMerchantRequest request) {
        return merchantService.createMerchant(request);
    }
    @GetMapping("/me")
    public MerchantResponse getCurrentMerchant(@AuthenticationPrincipal AuthenticatedMerchant merchant) {
        return merchantService.getMerchant(merchant.merchantId());
    }

}
