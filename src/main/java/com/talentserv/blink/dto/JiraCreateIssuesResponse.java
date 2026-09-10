package com.talentserv.blink.dto;

import java.util.List;

public record JiraCreateIssuesResponse(
        String status,
        String message,
        List<JiraCreatedIssue> issues,
        List<String> errors
) {
}
