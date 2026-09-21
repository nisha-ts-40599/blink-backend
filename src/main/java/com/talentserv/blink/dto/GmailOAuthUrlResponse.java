package com.talentserv.blink.dto;

public record GmailOAuthUrlResponse(
        boolean clientConfigured,
        boolean connected,
        String url,
        String redirectUri,
        String from,
        String message
) {
}
