package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.StakeholderRequest;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class WizardProgressTest {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    @Test
    void applyStoresOwnerAndWizardSnapshot() {
        Project project = new Project();
        ObjectNode state = MAPPER.createObjectNode();
        state.put("projectName", "Fitoyo");
        state.put("projectId", "10");
        ProjectRequest request = new ProjectRequest(
                "new",
                "Fitoyo",
                "Health tracker",
                List.of(new StakeholderRequest("po", "Ada", "ada@talentserv.co.in")),
                "requirements",
                3,
                state
        );

        WizardProgress.applyOwner(project, " Ada@TalentServ.co.in ");
        WizardProgress.apply(project, request);

        assertThat(project.getOwnerEmail()).isEqualTo("ada@talentserv.co.in");
        assertThat(project.getWizardStep()).isEqualTo("requirements");
        assertThat(project.getWizardCompletedThrough()).isEqualTo(3);
        assertThat(project.getWizardStateJson()).contains("Fitoyo");
        assertThat(WizardProgress.parseState(project.getWizardStateJson()).path("projectName").asText())
                .isEqualTo("Fitoyo");
    }

    @Test
    void applyIgnoresBlankWizardFields() {
        Project project = new Project();
        project.setWizardStep("integrations");
        project.setWizardCompletedThrough(2);
        project.setWizardStateJson("{\"keep\":true}");

        ProjectRequest request = new ProjectRequest(
                "new",
                "Fitoyo",
                "Health tracker",
                List.of(new StakeholderRequest("po", "Ada", "ada@talentserv.co.in"))
        );
        WizardProgress.apply(project, request);

        assertThat(project.getWizardStep()).isEqualTo("integrations");
        assertThat(project.getWizardCompletedThrough()).isEqualTo(2);
        assertThat(project.getWizardStateJson()).isEqualTo("{\"keep\":true}");
    }
}
