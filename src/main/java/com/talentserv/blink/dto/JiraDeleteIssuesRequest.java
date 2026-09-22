package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.constraints.NotEmpty;

public record JiraDeleteIssuesRequest(
        String projectId,
        @NotEmpty(message = "Add at least one Jira issue key to delete") List<String> issueKeys
) {
}
