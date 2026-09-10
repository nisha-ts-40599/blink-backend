package com.talentserv.blink.repo;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talentserv.blink.domain.ProjectIntegration;

public interface ProjectIntegrationRepository extends JpaRepository<ProjectIntegration, Long> {

    Optional<ProjectIntegration> findByProjectIdAndProvider(Long projectId, String provider);
}
