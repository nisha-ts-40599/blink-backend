package com.talentserv.blink.dto;

import java.util.List;

import tools.jackson.databind.JsonNode;

public record PlanProductScopeResponse(
        String status,
        String message,
        String nextCommand,
        String proposalDigest,
        List<String> epicIds,
        List<String> storyIds,
        JsonNode productScope,
        List<String> errors
) {
    public PlanProductScopeResponse {
        epicIds = epicIds == null ? List.of() : List.copyOf(epicIds);
        storyIds = storyIds == null ? List.of() : List.copyOf(storyIds);
        errors = errors == null ? List.of() : List.copyOf(errors);
    }
}
