package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import tools.jackson.databind.JsonNode;

public record ProjectRequest(
        @NotBlank(message = "Project type is required") String projectType,
        @NotBlank(message = "Project name is required") @Size(max = 255) String projectName,
        @NotBlank(message = "Project description is required") @Size(max = 8000) String description,
        @NotEmpty(message = "Add at least one stakeholder") List<@Valid StakeholderRequest> stakeholders,
        String wizardStep,
        Integer wizardCompletedThrough,
        JsonNode wizardState
) {
    public ProjectRequest(
            String projectType,
            String projectName,
            String description,
            List<StakeholderRequest> stakeholders
    ) {
        this(projectType, projectName, description, stakeholders, null, null, null);
    }
}
