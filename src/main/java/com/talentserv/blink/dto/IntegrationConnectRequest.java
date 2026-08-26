package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;

public record IntegrationConnectRequest(
        @NotBlank(message = "Provider is required") String provider,
        String baseUrl,
        String token,
        String username,
        String email,
        String organization,
        String workspace,
        String projectKey,
        String spaceKey
) {
}
