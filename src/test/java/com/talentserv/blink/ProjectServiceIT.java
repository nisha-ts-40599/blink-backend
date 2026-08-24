package com.talentserv.blink;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.service.ProjectService;

@SpringBootTest
@Transactional
class ProjectServiceIT {

    @Autowired
    private ProjectService projectService;

    @Test
    void saveAndContinuePersistsProjectAndStakeholders() {
        ProjectResponse created = projectService.create(new ProjectRequest(
                "new",
                "Banking Application",
                "Retail banking workspace",
                List.of(new StakeholderRequest("po", "Priya Mehta", "priya.mehta@example.com"))
        ));

        assertThat(created.id()).isNotNull();
        assertThat(created.projectCode()).isEqualTo("BANKING-APPLICATION");
        assertThat(created.projectType()).isEqualTo("new");
        assertThat(created.stakeholders()).hasSize(1);
        assertThat(created.stakeholders().getFirst().roleCode()).isEqualTo("po");

        ProjectResponse loaded = projectService.get(created.id());
        assertThat(loaded.projectName()).isEqualTo("Banking Application");
        assertThat(loaded.stakeholders().getFirst().email()).isEqualTo("priya.mehta@example.com");
    }
}
