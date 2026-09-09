package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PlanProductScopeRequest(
        @Size(max = 255) String projectName,
        @Size(max = 64) String projectId,
        @NotBlank(message = "Requirement text is required to plan product scope.")
        @Size(max = 64000) String requirementText,
        @Size(max = 64) String actor
) {
}
