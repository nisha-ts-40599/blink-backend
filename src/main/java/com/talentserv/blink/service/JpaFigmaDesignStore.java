package com.talentserv.blink.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talentserv.blink.domain.FigmaDesignBinding;
import com.talentserv.blink.dto.StoredFigmaDesign;
import com.talentserv.blink.repo.FigmaDesignBindingRepository;

@Service
@Profile("!nodb")
public class JpaFigmaDesignStore implements FigmaDesignStore {

    private final FigmaDesignBindingRepository repository;

    public JpaFigmaDesignStore(FigmaDesignBindingRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void upsert(StoredFigmaDesign design) {
        if (design == null || design.projectId() == null || design.fileKey() == null || design.fileKey().isBlank()) {
            return;
        }
        FigmaDesignBinding row = repository.findByProjectIdAndFileKey(design.projectId(), design.fileKey())
                .orElseGet(FigmaDesignBinding::new);
        row.setProjectId(design.projectId());
        row.setFileKey(design.fileKey());
        row.setFileName(design.fileName());
        row.setFileUrl(design.fileUrl());
        row.setFigmaProjectId(design.figmaProjectId());
        row.setTeamId(design.teamId());
        row.setSyncJira(design.syncJira());
        row.setWebhookId(design.webhookId());
        row.setWebhookPasscode(design.webhookPasscode());
        row.setFileVersion(design.fileVersion());
        row.setLastSyncedAt(design.lastSyncedAt() == null ? null : design.lastSyncedAt().toString());
        row.setLastSyncSummary(design.lastSyncSummary());
        row.setWebhookStatus(design.webhookStatus());
        row.setSnapshotJson(design.snapshotJson());
        repository.save(row);
    }

    @Override
    @Transactional
    public void deleteByProjectId(Long projectId) {
        if (projectId == null) {
            return;
        }
        repository.deleteByProjectId(projectId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<StoredFigmaDesign> find(Long projectId, String fileKey) {
        if (projectId == null || fileKey == null || fileKey.isBlank()) {
            return Optional.empty();
        }
        return repository.findByProjectIdAndFileKey(projectId, fileKey).map(JpaFigmaDesignStore::fromRow);
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFigmaDesign> findByProjectId(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (FigmaDesignBinding row : repository.findByProjectId(projectId)) {
            out.add(fromRow(row));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFigmaDesign> findByFileKey(String fileKey) {
        if (fileKey == null || fileKey.isBlank()) {
            return List.of();
        }
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (FigmaDesignBinding row : repository.findByFileKey(fileKey.trim())) {
            out.add(fromRow(row));
        }
        return out;
    }

    @Override
    @Transactional(readOnly = true)
    public List<StoredFigmaDesign> findByPasscode(String passcode) {
        if (passcode == null || passcode.isBlank()) {
            return List.of();
        }
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (FigmaDesignBinding row : repository.findByWebhookPasscode(passcode)) {
            out.add(fromRow(row));
        }
        return out;
    }

    private static StoredFigmaDesign fromRow(FigmaDesignBinding row) {
        Instant synced = null;
        if (row.getLastSyncedAt() != null && !row.getLastSyncedAt().isBlank()) {
            try {
                synced = Instant.parse(row.getLastSyncedAt());
            } catch (Exception ignored) {
            }
        }
        return new StoredFigmaDesign(
                row.getProjectId(),
                row.getFileKey(),
                row.getFileName(),
                row.getFileUrl(),
                row.getFigmaProjectId(),
                row.getTeamId(),
                row.isSyncJira(),
                row.getWebhookId(),
                row.getWebhookPasscode(),
                row.getFileVersion(),
                synced,
                row.getLastSyncSummary(),
                row.getWebhookStatus(),
                row.getSnapshotJson()
        );
    }
}
