package com.talentserv.blink.repo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import com.talentserv.blink.domain.Stakeholder;

public interface StakeholderRepository extends JpaRepository<Stakeholder, Long> {

    List<Stakeholder> findByProjectIdOrderByPersonNameAsc(Long projectId);

    @Transactional
    void deleteByProjectId(Long projectId);

    @Query(value = "select category from stakeholder where category is not null limit 1", nativeQuery = true)
    Optional<String> findAnyCategory();
}
