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
        List<JiraProjectDto> projects,
        String organization,
        List<GithubOrgDto> organizations
) {
    public IntegrationConnectResponse(boolean connected, String provider, String account, String detail) {
        this(connected, provider, account, detail, null, null, null, null, null, null, List.of(), null, List.of());
    }

    public IntegrationConnectResponse(
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
        this(
                connected,
                provider,
                account,
                detail,
                projectKey,
                projectName,
                baseUrl,
                cloudId,
                authType,
                token,
                projects == null ? List.of() : projects,
                null,
                List.of()
        );
    }
}
