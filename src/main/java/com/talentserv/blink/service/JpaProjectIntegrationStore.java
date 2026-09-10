package com.talentserv.blink.service;

import java.util.Locale;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talentserv.blink.domain.ProjectIntegration;
import com.talentserv.blink.dto.StoredIntegration;
import com.talentserv.blink.repo.ProjectIntegrationRepository;

@Service
@Profile("!nodb")
public class JpaProjectIntegrationStore implements ProjectIntegrationStore {

    private final ProjectIntegrationRepository repository;
    private final SecretEncryptionService encryption;

    public JpaProjectIntegrationStore(ProjectIntegrationRepository repository, SecretEncryptionService encryption) {
        this.repository = repository;
        this.encryption = encryption;
    }

    @Override
    @Transactional
    public void upsert(StoredIntegration integration) {
        if (integration == null || integration.projectId() == null || integration.provider() == null) {
            return;
        }
        String provider = integration.provider().trim().toLowerCase(Locale.ROOT);
        ProjectIntegration row = repository.findByProjectIdAndProvider(integration.projectId(), provider)
                .orElseGet(ProjectIntegration::new);
        row.setProjectId(integration.projectId());
        row.setProvider(provider);
        row.setAccount(integration.account());
        row.setBaseUrl(integration.baseUrl());
        row.setEmail(integration.email());
        row.setUsername(integration.username());
        row.setOrganization(integration.organization());
        row.setWorkspace(integration.workspace());
        row.setProjectKey(integration.projectKey());
        row.setProjectName(integration.projectName());
        row.setSpaceKey(integration.spaceKey());
        row.setCloudId(integration.cloudId());
        row.setAuthType(integration.authType());
        if (integration.accessToken() != null && !integration.accessToken().isBlank()) {
            row.setAccessTokenEnc(encryption.encrypt(integration.accessToken()));
        }
        if (integration.refreshToken() != null && !integration.refreshToken().isBlank()) {
            row.setRefreshTokenEnc(encryption.encrypt(integration.refreshToken()));
        }
        row.setExpiresAt(integration.expiresAt());
        repository.save(row);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredIntegration> find(Long projectId, String provider) {
        if (projectId == null || provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        return repository.findByProjectIdAndProvider(projectId, provider.trim().toLowerCase(Locale.ROOT))
                .map(row -> new StoredIntegration(
                        row.getProjectId(),
                        row.getProvider(),
                        row.getAccount(),
                        row.getBaseUrl(),
                        row.getEmail(),
                        row.getUsername(),
                        row.getOrganization(),
                        row.getWorkspace(),
                        row.getProjectKey(),
                        row.getProjectName(),
                        row.getSpaceKey(),
                        row.getCloudId(),
                        row.getAuthType(),
                        encryption.decrypt(row.getAccessTokenEnc()),
                        encryption.decrypt(row.getRefreshTokenEnc()),
                        row.getExpiresAt()
                ));
    }
}
