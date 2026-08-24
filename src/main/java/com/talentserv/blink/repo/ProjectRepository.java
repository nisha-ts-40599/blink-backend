package com.talentserv.blink.repo;

import org.springframework.data.jpa.repository.JpaRepository;

import com.talentserv.blink.domain.Project;

public interface ProjectRepository extends JpaRepository<Project, Long> {
}
