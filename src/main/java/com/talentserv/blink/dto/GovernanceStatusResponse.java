package com.talentserv.blink.dto;

import java.util.List;

public record GovernanceStatusResponse(
        String status,
        List<String> sodWarnings,
        String nextCommand,
        String message
) {
    public static GovernanceStatusResponse idle() {
        return new GovernanceStatusResponse("idle", List.of(), "/plan-product-scope", "");
    }
}
