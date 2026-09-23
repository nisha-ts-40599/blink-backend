package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;

public record JiraIssueTransitionRequest(
        String projectId,
        @NotBlank(message = "Jira issue key is required") String issueKey,
        @NotBlank(message = "Choose done or closed") String target
) {
}
