package com.talentserv.blink.dto;

public record GithubOAuthUrlResponse(
        boolean configured,
        String url,
        String clientId,
        String redirectUri,
        String message
) {
}
