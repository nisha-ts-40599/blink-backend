package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;

public record JiraCommentCreateRequest(
        String projectId,
        String baseUrl,
        String email,
        String token,
        String cloudId,
        String accessToken,
        @NotBlank String issueKey,
        @NotBlank String body,
        String blinkQuestionId
) {
}
