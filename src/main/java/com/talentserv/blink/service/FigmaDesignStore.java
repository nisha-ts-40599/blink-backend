package com.talentserv.blink.service;

import java.util.List;
import java.util.Optional;

import com.talentserv.blink.dto.StoredFigmaDesign;

public interface FigmaDesignStore {

    void upsert(StoredFigmaDesign design);

    void deleteByProjectId(Long projectId);

    Optional<StoredFigmaDesign> find(Long projectId, String fileKey);

    List<StoredFigmaDesign> findByProjectId(Long projectId);

    List<StoredFigmaDesign> findByFileKey(String fileKey);

    List<StoredFigmaDesign> findByPasscode(String passcode);
}
