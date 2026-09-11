package com.talentserv.blink.dto;

public record FigmaOAuthUrlResponse(
        boolean configured,
        String url,
        String clientId,
        String redirectUri,
        String message
) {
}
