package com.talentserv.blink.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.DotEnvEnvironmentPostProcessor;
import com.talentserv.blink.dto.StoredFigmaDesign;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("nodb")
public class FileFigmaDesignStore implements FigmaDesignStore {

    private static final Logger log = LoggerFactory.getLogger(FileFigmaDesignStore.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final Path store;
    private final ConcurrentHashMap<String, Row> rows = new ConcurrentHashMap<>();

    public FileFigmaDesignStore() {
        Path envFile = DotEnvEnvironmentPostProcessor.resolveEnvFile();
        this.store = envFile != null
                ? envFile.getParent().resolve(".blink-figma-designs.json")
                : Path.of(System.getProperty("user.dir", ".")).resolve(".blink-figma-designs.json");
    }

    @PostConstruct
    void load() {
        if (!Files.isRegularFile(store)) {
            return;
        }
        try {
            Snapshot snapshot = MAPPER.readValue(store.toFile(), Snapshot.class);
            if (snapshot == null || snapshot.designs == null) {
                return;
            }
            for (Row row : snapshot.designs) {
                if (row != null && row.projectId != null && row.fileKey != null) {
                    rows.put(key(row.projectId, row.fileKey), row);
                }
            }
            log.info("Loaded {} Figma design binding(s) from {}", rows.size(), store.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Could not load Figma design bindings: {}", ex.toString());
        }
    }

    @Override
    public synchronized void upsert(StoredFigmaDesign design) {
        if (design == null || design.projectId() == null || design.fileKey() == null || design.fileKey().isBlank()) {
            return;
        }
        Row row = toRow(design);
        rows.put(key(row.projectId, row.fileKey), row);
        persist();
    }

    @Override
    public synchronized void deleteByProjectId(Long projectId) {
        if (projectId == null) {
            return;
        }
        rows.entrySet().removeIf(entry -> projectId.equals(entry.getValue().projectId));
        persist();
    }

    @Override
    public Optional<StoredFigmaDesign> find(Long projectId, String fileKey) {
        if (projectId == null || fileKey == null || fileKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(key(projectId, fileKey))).map(FileFigmaDesignStore::fromRow);
    }

    @Override
    public List<StoredFigmaDesign> findByProjectId(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (Row row : rows.values()) {
            if (projectId.equals(row.projectId)) {
                out.add(fromRow(row));
            }
        }
        return out;
    }

    @Override
    public List<StoredFigmaDesign> findByFileKey(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return List.of();
        }
        String wanted = fileKey.trim();
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (Row row : rows.values()) {
            if (wanted.equals(row.fileKey)) {
                out.add(fromRow(row));
            }
        }
        return out;
    }

    @Override
    public List<StoredFigmaDesign> findByPasscode(String passcode) {
        if (passcode == null || passcode.isBlank()) {
            return List.of();
        }
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (Row row : rows.values()) {
            if (passcode.equals(row.webhookPasscode)) {
                out.add(fromRow(row));
            }
        }
        return out;
    }

    private void persist() {
        try {
            Snapshot snapshot = new Snapshot();
            snapshot.designs = new ArrayList<>(rows.values());
            Files.writeString(store, MAPPER.writeValueAsString(snapshot));
        } catch (Exception ex) {
            log.warn("Could not write Figma design bindings: {}", ex.toString());
        }
    }

    private static String key(Long projectId, String fileKey) {
        return projectId + ":" + fileKey.trim();
    }

    private static Row toRow(StoredFigmaDesign design) {
        Row row = new Row();
        row.projectId = design.projectId();
        row.fileKey = design.fileKey();
        row.fileName = design.fileName();
        row.fileUrl = design.fileUrl();
        row.figmaProjectId = design.figmaProjectId();
        row.teamId = design.teamId();
        row.syncJira = design.syncJira();
        row.webhookId = design.webhookId();
        row.webhookPasscode = design.webhookPasscode();
        row.fileVersion = design.fileVersion();
        row.lastSyncedAt = design.lastSyncedAt() == null ? null : design.lastSyncedAt().toString();
        row.lastSyncSummary = design.lastSyncSummary();
        row.webhookStatus = design.webhookStatus();
        row.snapshotJson = design.snapshotJson();
        return row;
    }

    private static StoredFigmaDesign fromRow(Row row) {
        Instant synced = null;
        if (row.lastSyncedAt != null && !row.lastSyncedAt.isBlank()) {
            try {
                synced = Instant.parse(row.lastSyncedAt);
            } catch (Exception ignored) {
            }
        }
        return new StoredFigmaDesign(
                row.projectId,
                row.fileKey,
                row.fileName,
                row.fileUrl,
                row.figmaProjectId,
                row.teamId,
                row.syncJira,
                row.webhookId,
                row.webhookPasscode,
                row.fileVersion,
                synced,
                row.lastSyncSummary,
                row.webhookStatus,
                row.snapshotJson
        );
    }

    static class Snapshot {
        public List<Row> designs = List.of();
    }

    static class Row {
        public Long projectId;
        public String fileKey;
        public String fileName;
        public String fileUrl;
        public String figmaProjectId;
        public String teamId;
        public boolean syncJira = true;
        public String webhookId;
        public String webhookPasscode;
        public String fileVersion;
        public String lastSyncedAt;
        public String lastSyncSummary;
        public String webhookStatus;
        public String snapshotJson;
    }
}
