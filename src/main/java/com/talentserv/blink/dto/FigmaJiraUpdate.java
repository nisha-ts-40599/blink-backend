package com.talentserv.blink.dto;

public record FigmaJiraUpdate(
        String issueKey,
        String status,
        String commentId,
        String message
) {
}
