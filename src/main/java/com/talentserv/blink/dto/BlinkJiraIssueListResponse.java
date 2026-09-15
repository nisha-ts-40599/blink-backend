package com.talentserv.blink.dto;

import java.util.List;

/**
 * Developer-panel listing of Blink-marked Jira issues for one Blink project binding.
 */
public record BlinkJiraIssueListResponse(
        boolean connected,
        Long blinkProjectId,
        String jiraProjectKey,
        String browseBase,
        List<BlinkJiraIssueResponse> issues,
        String message
) {
}
