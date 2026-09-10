package com.talentserv.blink.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.DotEnvEnvironmentPostProcessor;
import com.talentserv.blink.dto.StoredIntegration;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("nodb")
public class FileProjectIntegrationStore implements ProjectIntegrationStore {

    private static final Logger log = LoggerFactory.getLogger(FileProjectIntegrationStore.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final SecretEncryptionService encryption;
    private final Path store;
    private final ConcurrentHashMap<String, EncryptedRow> rows = new ConcurrentHashMap<>();

    public FileProjectIntegrationStore(SecretEncryptionService encryption) {
        this.encryption = encryption;
        Path envFile = DotEnvEnvironmentPostProcessor.resolveEnvFile();
        this.store = envFile != null
                ? envFile.getParent().resolve(".blink-integrations.json")
                : Path.of(System.getProperty("user.dir", ".")).resolve(".blink-integrations.json");
    }

    @PostConstruct
    void load() {
        if (!Files.isRegularFile(store)) {
            return;
        }
        try {
            Snapshot snapshot = MAPPER.readValue(store.toFile(), Snapshot.class);
            if (snapshot == null || snapshot.integrations == null) {
                return;
            }
            for (EncryptedRow row : snapshot.integrations) {
                if (row != null && row.projectId != null && row.provider != null) {
                    rows.put(key(row.projectId, row.provider), row);
                }
            }
            log.info("Loaded {} local integration(s) from {}", rows.size(), store.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Could not load local integrations: {}", ex.toString());
        }
    }

    @Override
    public synchronized void upsert(StoredIntegration integration) {
        if (integration == null || integration.projectId() == null || integration.provider() == null) {
            return;
        }
        String provider = integration.provider().trim().toLowerCase(Locale.ROOT);
        EncryptedRow existing = rows.get(key(integration.projectId(), provider));
        EncryptedRow row = existing == null ? new EncryptedRow() : existing;
        row.projectId = integration.projectId();
        row.provider = provider;
        row.account = integration.account();
        row.baseUrl = integration.baseUrl();
        row.email = integration.email();
        row.username = integration.username();
        row.organization = integration.organization();
        row.workspace = integration.workspace();
        row.projectKey = integration.projectKey();
        row.projectName = integration.projectName();
        row.spaceKey = integration.spaceKey();
        row.cloudId = integration.cloudId();
        row.authType = integration.authType();
        if (integration.accessToken() != null && !integration.accessToken().isBlank()) {
            row.accessTokenEnc = encryption.encrypt(integration.accessToken());
        }
        if (integration.refreshToken() != null && !integration.refreshToken().isBlank()) {
            row.refreshTokenEnc = encryption.encrypt(integration.refreshToken());
        }
        row.expiresAt = integration.expiresAt() == null ? null : integration.expiresAt().toString();
        rows.put(key(row.projectId, provider), row);
        persist();
    }

    @Override
    public Optional<StoredIntegration> find(Long projectId, String provider) {
        if (projectId == null || provider == null || provider.isBlank()) {
            return Optional.empty();
        }
        EncryptedRow row = rows.get(key(projectId, provider.trim().toLowerCase(Locale.ROOT)));
        if (row == null) {
            return Optional.empty();
        }
        Instant expires = null;
        if (row.expiresAt != null && !row.expiresAt.isBlank()) {
            try {
                expires = Instant.parse(row.expiresAt);
            } catch (Exception ignored) {
            }
        }
        return Optional.of(new StoredIntegration(
                row.projectId,
                row.provider,
                row.account,
                row.baseUrl,
                row.email,
                row.username,
                row.organization,
                row.workspace,
                row.projectKey,
                row.projectName,
                row.spaceKey,
                row.cloudId,
                row.authType,
                encryption.decrypt(row.accessTokenEnc),
                encryption.decrypt(row.refreshTokenEnc),
                expires
        ));
    }

    private void persist() {
        try {
            Snapshot snapshot = new Snapshot();
            snapshot.integrations = new ArrayList<>(rows.values());
            Files.writeString(store, MAPPER.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.warn("Could not write local integrations: {}", ex.toString());
        }
    }

    private static String key(Long projectId, String provider) {
        return projectId + ":" + provider.trim().toLowerCase(Locale.ROOT);
    }

    static class Snapshot {
        public List<EncryptedRow> integrations = List.of();
    }

    static class EncryptedRow {
        public Long projectId;
        public String provider;
        public String account;
        public String baseUrl;
        public String email;
        public String username;
        public String organization;
        public String workspace;
        public String projectKey;
        public String projectName;
        public String spaceKey;
        public String cloudId;
        public String authType;
        public String accessTokenEnc;
        public String refreshTokenEnc;
        public String expiresAt;
    }
}
