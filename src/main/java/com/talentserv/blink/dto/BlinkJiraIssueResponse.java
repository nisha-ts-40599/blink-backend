package com.talentserv.blink.dto;

/**
 * One Jira issue that Blink identified as its own via a Source epic/story marker.
 */
public record BlinkJiraIssueResponse(
        String key,
        String issueType,
        String summary,
        String sourceKind,
        String sourceId,
        String url
) {
}
