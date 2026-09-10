package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;

public record IntegrationBindingRequest(
        @NotBlank(message = "Project id is required") String projectId,
        @NotBlank(message = "Provider is required") String provider,
        String projectKey,
        String projectName,
        String spaceKey
) {
}
