package com.talentserv.blink.dto;

public record JiraOAuthUrlResponse(
        boolean configured,
        String url,
        String clientId,
        String redirectUri,
        String message
) {
}
