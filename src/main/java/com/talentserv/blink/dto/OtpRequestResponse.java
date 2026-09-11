package com.talentserv.blink.dto;

public record OtpRequestResponse(
        String message,
        String deliveryMode,
        int expiresInSeconds,
        int resendAfterSeconds,
        String otp
) {
}
