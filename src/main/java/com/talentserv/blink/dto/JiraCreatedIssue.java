package com.talentserv.blink.dto;

public record JiraCreatedIssue(
        String sourceId,
        String jiraKey,
        String jiraUrl,
        String type,
        String status,
        String message
) {
}
