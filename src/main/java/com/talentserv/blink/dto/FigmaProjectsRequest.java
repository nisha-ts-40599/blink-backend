package com.talentserv.blink.dto;

public record FigmaProjectsRequest(
        String projectId,
        String token,
        String organization
) {
}
