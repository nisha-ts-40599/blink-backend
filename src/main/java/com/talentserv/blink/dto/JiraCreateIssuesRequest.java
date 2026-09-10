package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;

public record JiraCreateIssuesRequest(
        String projectId,
        String baseUrl,
        String email,
        String token,
        String cloudId,
        String accessToken,
        @NotBlank(message = "Jira project key is required") String projectKey,
        List<JiraEpicSpec> epics,
        List<JiraStorySpec> stories
) {
}
