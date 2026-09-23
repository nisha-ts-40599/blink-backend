package com.talentserv.blink.dto;

import java.util.List;

public record FigmaDesignBindingResponse(
        boolean bound,
        String projectId,
        String fileKey,
        String fileName,
        String fileUrl,
        String fileVersion,
        boolean syncJira,
        String webhookId,
        String webhookStatus,
        String lastSyncedAt,
        String lastSyncSummary,
        String markdown,
        List<FigmaScreenBinding> screens,
        List<FigmaDesignChange> changes,
        List<FigmaJiraUpdate> jiraUpdates
) {
}
