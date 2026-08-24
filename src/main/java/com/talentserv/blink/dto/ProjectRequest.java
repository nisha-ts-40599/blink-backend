package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record ProjectRequest(
        @NotBlank(message = "Project type is required") String projectType,
        @NotBlank(message = "Project name is required") @Size(max = 255) String projectName,
        @Size(max = 8000) String description,
        @NotEmpty(message = "Add at least one stakeholder") List<@Valid StakeholderRequest> stakeholders
) {
}
