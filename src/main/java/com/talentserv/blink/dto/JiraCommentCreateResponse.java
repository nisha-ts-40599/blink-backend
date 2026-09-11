package com.talentserv.blink.dto;

public record JiraCommentCreateResponse(
        String status,
        String message,
        String issueKey,
        String commentId,
        String blinkQuestionId
) {
}
