package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record GroomClarifyRequest(
        @NotBlank(message = "Paste a short description of what you want to build first.")
        String requirementText,
        @Size(max = 255) String projectName,
        @Size(max = 64) String projectId,
        @Size(max = 60) List<@Valid GroomAnswerRequest> answers
) {
}
