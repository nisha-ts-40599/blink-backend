package com.talentserv.blink.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talentserv.blink.domain.Project;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findFirstByOwnerEmailIgnoreCaseOrderByUpdatedAtDesc(String ownerEmail);
}
