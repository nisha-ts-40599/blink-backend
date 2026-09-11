package com.talentserv.blink.dto;

import jakarta.validation.constraints.NotBlank;

public record OtpVerifyRequest(
        @NotBlank(message = "Email is required.") String email,
        @NotBlank(message = "Enter the one-time password sent to your email.") String otp,
        String accessCode
) {
}
