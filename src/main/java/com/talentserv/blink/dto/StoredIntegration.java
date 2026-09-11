package com.talentserv.blink.dto;

import java.time.Instant;

/**
 * In-memory integration connection. Tokens are plaintext here; the store encrypts at rest.
 */
public record StoredIntegration(
        Long projectId,
        String provider,
        String account,
        String baseUrl,
        String email,
        String username,
        String organization,
        String workspace,
        String projectKey,
        String projectName,
        String spaceKey,
        String cloudId,
        String authType,
        String accessToken,
        String refreshToken,
        Instant expiresAt
) {
    public StoredIntegration withProjectKey(String key, String name) {
        return new StoredIntegration(
                projectId, provider, account, baseUrl, email, username, organization, workspace,
                key, name, spaceKey, cloudId, authType, accessToken, refreshToken, expiresAt
        );
    }

    public StoredIntegration withOrganization(String organization) {
        return new StoredIntegration(
                projectId, provider, account, baseUrl, email, username, organization, workspace,
                projectKey, projectName, spaceKey, cloudId, authType, accessToken, refreshToken, expiresAt
        );
    }
}
