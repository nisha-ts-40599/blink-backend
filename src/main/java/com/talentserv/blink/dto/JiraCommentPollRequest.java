package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record JiraCommentPollRequest(
        String projectId,
        String baseUrl,
        String email,
        String token,
        String cloudId,
        String accessToken,
        @NotEmpty List<@Valid PollItem> items
) {
    public record PollItem(
            @NotBlank String issueKey,
            @NotBlank String blinkQuestionId
    ) {
    }
}
