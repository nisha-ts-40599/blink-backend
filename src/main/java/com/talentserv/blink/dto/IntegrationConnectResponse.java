package com.talentserv.blink.dto;

import java.util.List;

public record IntegrationConnectResponse(
        boolean connected,
        String provider,
        String account,
        String detail,
        String projectKey,
        String projectName,
        String baseUrl,
        String cloudId,
        String authType,
        String token,
        List<JiraProjectDto> projects
) {
    public IntegrationConnectResponse(boolean connected, String provider, String account, String detail) {
        this(connected, provider, account, detail, null, null, null, null, null, null, List.of());
    }
}
