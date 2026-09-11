package com.talentserv.blink.dto;

import java.util.List;

public record JiraCommentPollResponse(
        String status,
        String message,
        List<Reply> replies
) {
    public record Reply(
            String blinkQuestionId,
            String issueKey,
            String commentId,
            String author,
            String body,
            String created
    ) {
    }
}
