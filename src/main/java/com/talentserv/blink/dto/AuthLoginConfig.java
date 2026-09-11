package com.talentserv.blink.dto;

public record AuthLoginConfig(
        String allowedDomain,
        boolean gateRequired,
        boolean otpReveal
) {
}
