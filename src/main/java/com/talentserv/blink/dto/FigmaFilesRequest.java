package com.talentserv.blink.dto;

public record FigmaFilesRequest(
        String projectId,
        String figmaProjectId,
        String token
) {
}
