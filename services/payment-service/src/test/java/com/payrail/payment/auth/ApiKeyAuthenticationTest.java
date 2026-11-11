package com.payrail.payment.auth;

import com.payrail.payment.TestcontainersConfiguration;
import com.payrail.payment.merchant.ApiKey;
import com.payrail.payment.merchant.ApiKeyGenerator;
import com.payrail.payment.merchant.ApiKeyRepository;
import com.payrail.payment.merchant.CreateMerchantRequest;
import com.payrail.payment.merchant.MerchantService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.http.HttpHeaders.AUTHORIZATION;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class ApiKeyAuthenticationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private MerchantService merchantService;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    private String createMerchantAndGetKey(String name) {
        return merchantService.createMerchant(new CreateMerchantRequest(name, null)).apiKey();
    }

    @Test
    void validKeyIdentifiesTheCallingMerchant() throws Exception {
        String apiKey = createMerchantAndGetKey("Shoe Store");

        mockMvc.perform(get("/v1/merchants/me").header(AUTHORIZATION, "Bearer " + apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Shoe Store"))
                .andExpect(jsonPath("$.webhook_secret").doesNotExist()); // secrets never exposed
    }

    @Test
    void missingKeyIsRejected() throws Exception {
        mockMvc.perform(get("/v1/merchants/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("unauthorized"));
    }

    @Test
    void realPrefixWithWrongSecretIsRejected() throws Exception {
        String apiKey = createMerchantAndGetKey("Shoe Store");
        String forged = apiKey.substring(0, ApiKeyGenerator.PREFIX_LENGTH) + "_not-the-real-secret";

        mockMvc.perform(get("/v1/merchants/me").header(AUTHORIZATION, "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void revokedKeyIsRejected() throws Exception {
        String apiKey = createMerchantAndGetKey("Shoe Store");
        ApiKey stored = apiKeyRepository.findByKeyPrefix(apiKey.substring(0, ApiKeyGenerator.PREFIX_LENGTH))
                .orElseThrow();
        stored.revoke();
        apiKeyRepository.save(stored);

        mockMvc.perform(get("/v1/merchants/me").header(AUTHORIZATION, "Bearer " + apiKey))
                .andExpect(status().isUnauthorized());
    }
}