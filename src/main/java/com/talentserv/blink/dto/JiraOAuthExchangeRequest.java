package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;

public record JiraOAuthExchangeRequest(
        @NotBlank(message = "Authorization code is required") String code,
        String redirectUri,
        String projectId
) {
}
