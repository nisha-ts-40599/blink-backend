package com.talentserv.blink.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talentserv.blink.domain.FigmaDesignBinding;

public interface FigmaDesignBindingRepository extends JpaRepository<FigmaDesignBinding, Long> {

    Optional<FigmaDesignBinding> findByProjectIdAndFileKey(Long projectId, String fileKey);

    List<FigmaDesignBinding> findByProjectId(Long projectId);

    List<FigmaDesignBinding> findByFileKey(String fileKey);

    List<FigmaDesignBinding> findByWebhookPasscode(String webhookPasscode);

    void deleteByProjectId(Long projectId);
}
