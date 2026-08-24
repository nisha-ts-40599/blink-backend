package com.talentserv.blink.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record StakeholderRequest(
        @NotBlank(message = "Stakeholder role is required") String roleCode,
        @NotBlank(message = "Stakeholder name is required") @Size(max = 255) String name,
        @NotBlank(message = "Stakeholder email is required") @Email @Size(max = 255) String email
) {
}
