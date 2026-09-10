package com.talentserv.blink.dto;

public record JiraProjectsRequest(
        String projectId,
        String baseUrl,
        String email,
        String token,
        String cloudId,
        String accessToken
) {
}
