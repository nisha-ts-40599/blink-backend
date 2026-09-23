package com.talentserv.blink.dto;

/**
 * Live Jira workflow status for one issue.
 * {@code category} is {@code todo}, {@code in-progress}, {@code done}, {@code missing}, or {@code unknown}.
 */
public record JiraIssueStatusItem(
        String key,
        String name,
        String category
) {
}
