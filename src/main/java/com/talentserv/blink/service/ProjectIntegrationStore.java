package com.talentserv.blink.service;

import java.util.Optional;

import com.talentserv.blink.dto.StoredIntegration;

public interface ProjectIntegrationStore {

    void upsert(StoredIntegration integration);

    Optional<StoredIntegration> find(Long projectId, String provider);

    static ProjectIntegrationStore noop() {
        return new ProjectIntegrationStore() {
            @Override
            public void upsert(StoredIntegration integration) {
            }

            @Override
            public Optional<StoredIntegration> find(Long projectId, String provider) {
                return Optional.empty();
            }
        };
    }
}
