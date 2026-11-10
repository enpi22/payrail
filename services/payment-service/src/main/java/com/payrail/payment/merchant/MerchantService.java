package com.payrail.payment.merchant;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MerchantService {

    private final MerchantRepository merchantRepository;
    private final ApiKeyRepository apiKeyRepository;
    private final ApiKeyGenerator apiKeyGenerator;

    public MerchantService(MerchantRepository merchantRepository,
                           ApiKeyRepository apiKeyRepository,
                           ApiKeyGenerator apiKeyGenerator) {
        this.merchantRepository = merchantRepository;
        this.apiKeyRepository = apiKeyRepository;
        this.apiKeyGenerator = apiKeyGenerator;
    }

    /** Creates the merchant and its first API key in one transaction: both are saved, or neither. */
    @Transactional
    public MerchantCreatedResponse createMerchant(CreateMerchantRequest request) {
        Merchant merchant = merchantRepository.save(
                new Merchant(request.name(), request.webhookUrl(), apiKeyGenerator.generateWebhookSecret()));

        ApiKeyGenerator.GeneratedKey key = apiKeyGenerator.generateApiKey();
        apiKeyRepository.save(new ApiKey(merchant, key.prefix(), key.hash()));

        return new MerchantCreatedResponse(
                merchant.getId(), merchant.getName(), key.fullKey(), merchant.getWebhookSecret());
    }
}
