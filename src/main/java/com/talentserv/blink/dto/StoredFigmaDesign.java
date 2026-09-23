package com.talentserv.blink.dto;

import java.time.Instant;

public record StoredFigmaDesign(
        Long projectId,
        String fileKey,
        String fileName,
        String fileUrl,
        String figmaProjectId,
        String teamId,
        boolean syncJira,
        String webhookId,
        String webhookPasscode,
        String fileVersion,
        Instant lastSyncedAt,
        String lastSyncSummary,
        String webhookStatus,
        String snapshotJson
) {
    public StoredFigmaDesign withSync(
            String fileVersion,
            Instant lastSyncedAt,
            String lastSyncSummary,
            String snapshotJson
    ) {
        return new StoredFigmaDesign(
                projectId, fileKey, fileName, fileUrl, figmaProjectId, teamId, syncJira,
                webhookId, webhookPasscode, fileVersion, lastSyncedAt, lastSyncSummary,
                webhookStatus, snapshotJson
        );
    }

    public StoredFigmaDesign withWebhook(String webhookId, String webhookPasscode, String webhookStatus) {
        return new StoredFigmaDesign(
                projectId, fileKey, fileName, fileUrl, figmaProjectId, teamId, syncJira,
                webhookId, webhookPasscode, fileVersion, lastSyncedAt, lastSyncSummary,
                webhookStatus, snapshotJson
        );
    }

    public StoredFigmaDesign withMeta(String fileName, String fileUrl, String figmaProjectId, String teamId, boolean syncJira) {
        return new StoredFigmaDesign(
                projectId, fileKey, fileName, fileUrl, figmaProjectId, teamId, syncJira,
                webhookId, webhookPasscode, fileVersion, lastSyncedAt, lastSyncSummary,
                webhookStatus, snapshotJson
        );
    }
}
