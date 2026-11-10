package com.payrail.payment.merchant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.payrail.payment.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@AutoConfigureMockMvc
class MerchantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void createsMerchantAndStoresOnlyTheKeyHash() throws Exception {
        String body = """
                {"name": "Shoe Store", "webhook_url": "https://shoes.example.com/webhooks"}
                """;

        String response = mockMvc.perform(post("/v1/merchants").contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.api_key").value(startsWith("pr_test_")))
                .andExpect(jsonPath("$.webhook_secret").value(startsWith("whsec_")))
                .andReturn().getResponse().getContentAsString();

        String apiKey = objectMapper.readTree(response).get("api_key").asText();
        String prefix = apiKey.substring(0, ApiKeyGenerator.PREFIX_LENGTH);

        ApiKey stored = apiKeyRepository.findByKeyPrefix(prefix).orElseThrow();
        assertThat(stored.getKeyHash())
                .isNotEqualTo(apiKey)                          // the raw key is never stored
                .isEqualTo(ApiKeyGenerator.sha256Hex(apiKey)); // only its hash
    }

    @Test
    void rejectsBlankName() throws Exception {
        mockMvc.perform(post("/v1/merchants").contentType(APPLICATION_JSON).content("""
                        {"name": "  "}
                        """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void rejectsNonHttpsWebhookUrl() throws Exception {
        mockMvc.perform(post("/v1/merchants").contentType(APPLICATION_JSON).content("""
                        {"name": "Shoe Store", "webhook_url": "http://insecure.example.com"}
                        """))
                .andExpect(status().isBadRequest());
    }
}