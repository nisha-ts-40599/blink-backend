package com.talentserv.blink.dto;

public record FigmaWebhookResult(
        String status,
        String eventType,
        String fileKey,
        String message,
        FigmaDesignBindingResponse sync
) {
}
