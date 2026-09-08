package com.talentserv.blink.dto;

import java.util.List;

public record ConfigureStakeholdersResponse(
        String status,
        String message,
        String nextCommand,
        List<String> sodWarnings,
        List<String> errors,
        int rolesConfigured
) {
    public ConfigureStakeholdersResponse {
        sodWarnings = sodWarnings == null ? List.of() : List.copyOf(sodWarnings);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
