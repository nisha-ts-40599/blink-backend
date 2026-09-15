package com.talentserv.blink.dto;

import java.util.List;

/**
 * Result of deleting Blink-marked Jira issues (stories first, then epics).
 */
public record BlinkJiraIssueDeleteResponse(
        int deleted,
        int skipped,
        List<String> deletedKeys,
        List<String> skippedKeys,
        List<String> errors
) {
}
