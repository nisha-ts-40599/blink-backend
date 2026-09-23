package com.talentserv.blink.dto;

public record FigmaFramesRequest(
        String projectId,
        String fileKey,
        String fileUrl,
        String token
) {
}
