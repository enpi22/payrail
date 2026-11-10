package com.payrail.payment.merchant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.hibernate.validator.constraints.URL;

public record CreateMerchantRequest(
        @NotBlank @Size(max = 200) String name,
        @URL(protocol = "https") String webhookUrl
) {
}
