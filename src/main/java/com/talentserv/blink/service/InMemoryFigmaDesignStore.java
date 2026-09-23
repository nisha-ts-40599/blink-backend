package com.talentserv.blink.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import com.talentserv.blink.dto.StoredFigmaDesign;

public class InMemoryFigmaDesignStore implements FigmaDesignStore {

    private final ConcurrentHashMap<String, StoredFigmaDesign> rows = new ConcurrentHashMap<>();

    @Override
    public void upsert(StoredFigmaDesign design) {
        if (design == null || design.projectId() == null || design.fileKey() == null || design.fileKey().isBlank()) {
            return;
        }
        rows.put(key(design.projectId(), design.fileKey()), design);
    }

    @Override
    public void deleteByProjectId(Long projectId) {
        if (projectId == null) {
            return;
        }
        rows.entrySet().removeIf(entry -> projectId.equals(entry.getValue().projectId()));
    }

    @Override
    public Optional<StoredFigmaDesign> find(Long projectId, String fileKey) {
        if (projectId == null || fileKey == null || fileKey.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(rows.get(key(projectId, fileKey)));
    }

    @Override
    public List<StoredFigmaDesign> findByProjectId(Long projectId) {
        if (projectId == null) {
            return List.of();
        }
        List<StoredFigmaDesign> out = new ArrayList<>();
        for (StoredFigmaDesign row : rows.values()) {
            if (projectId.equals(row.projectId())) {
                out.add(row);
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
        for (StoredFigmaDesign row : rows.values()) {
            if (wanted.equals(row.fileKey())) {
                out.add(row);
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
        for (StoredFigmaDesign row : rows.values()) {
            if (passcode.equals(row.webhookPasscode())) {
                out.add(row);
            }
        }
        return out;
    }

    private static String key(Long projectId, String fileKey) {
        return projectId + ":" + fileKey.trim();
    }
}
