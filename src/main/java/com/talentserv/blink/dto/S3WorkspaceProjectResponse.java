package com.talentserv.blink.dto;

/**
 * One Blink workspace folder under the configured S3 bucket ({@code *_workspace}).
 */
public record S3WorkspaceProjectResponse(
        String folder,
        Long projectId,
        String url,
        long objectCount,
        long totalBytes,
        boolean kitComplete
) {
}
