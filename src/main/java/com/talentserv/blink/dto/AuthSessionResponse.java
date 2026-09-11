package com.talentserv.blink.dto;

public record AuthSessionResponse(
        String token,
        String email,
        String expiresAt
) {
}
