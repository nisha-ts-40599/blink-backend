package com.talentserv.blink.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ConfigureStakeholdersResponse;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.dto.StakeholderResponse;
import com.talentserv.blink.service.AgentRuntimeService;
import com.talentserv.blink.service.ProjectService;
import com.talentserv.blink.service.RequirementMarkdownService;
import com.talentserv.blink.service.S3WorkspaceService;
import com.talentserv.blink.service.SetupAgentService;
import com.talentserv.blink.service.ZipPackageService;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class ProjectControllerStakeholdersTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private ProjectService projectService;
    @Mock
    private RequirementMarkdownService requirementMarkdownService;
    @Mock
    private ZipPackageService zipPackageService;
    @Mock
    private SetupAgentService setupAgentService;
    @Mock
    private S3WorkspaceService s3WorkspaceService;
    @Mock
    private AgentRuntimeService agentRuntimeService;

    private BlinkProperties properties;
    private ProjectController controller;

    @BeforeEach
    void setUp() {
        properties = new BlinkProperties();
        controller = new ProjectController(
                projectService,
                requirementMarkdownService,
                zipPackageService,
                setupAgentService,
                s3WorkspaceService,
                agentRuntimeService,
                properties
        );
    }

    @Test
    void createAttachesGovernanceAndSodWarnings() {
        List<StakeholderRequest> stakeholders = List.of(
                new StakeholderRequest("product_owner", "Sarah Miller", "sarah@example.com"),
                new StakeholderRequest("qa_lead", "Sarah Miller", "sarah@example.com")
        );
        ProjectRequest request = new ProjectRequest("Fitoyo", "Health tracker", "new", stakeholders);

        ProjectResponse createdResponse = new ProjectResponse(
                10L,
                "Fitoyo",
                "FTO",
                "Health tracker",
                "active",
                "new",
                List.of(
                        new StakeholderResponse(1L, "product_owner", "Product Owner", "Sarah Miller", "sarah@example.com"),
                        new StakeholderResponse(2L, "qa_lead", "QA Lead", "Sarah Miller", "sarah@example.com")
                ),
                null,
                null,
                null
        );
        when(projectService.create(any())).thenReturn(createdResponse);

        ObjectNode agentResponse = MAPPER.createObjectNode();
        agentResponse.put("status", "warning");
        agentResponse.put("command", "configure-stakeholders");
        agentResponse.put("nextCommand", "/plan-product-scope");
        ArrayNode warnings = agentResponse.putArray("sodWarnings");
        ObjectNode warning = warnings.addObject();
        warning.put("code", "SOD_CONFLICT");
        warning.put("reason", "Grooming owner should not be sole QA gate approver when both are required");

        when(agentRuntimeService.invokeConfigureStakeholders(eq("Fitoyo"), eq("10"), eq(stakeholders), eq("apply")))
                .thenReturn(agentResponse);

        ProjectResponse result = controller.create(request);

        assertThat(result.id()).isEqualTo(10L);
        assertThat(result.nextCommand()).isEqualTo("/plan-product-scope");
        assertThat(result.sodWarnings()).hasSize(1);
        assertThat(result.sodWarnings().getFirst())
                .isEqualTo("Grooming owner should not be sole QA gate approver when both are required");
    }

    @Test
    void configureStakeholdersEndpointExecutesSuccessfully() {
        Project project = new Project();
        project.setId(15L);
        project.setProjectName("Fitoyo");
        when(projectService.requireProject(15L)).thenReturn(project);

        List<StakeholderRequest> stakeholders = List.of(
                new StakeholderRequest("product_owner", "Sarah Miller", "sarah@example.com")
        );

        ObjectNode agentResponse = MAPPER.createObjectNode();
        agentResponse.put("status", "ok");
        agentResponse.put("command", "configure-stakeholders");
        agentResponse.put("nextCommand", "/plan-product-scope");
        agentResponse.put("acceptedFileCount", 2);

        when(agentRuntimeService.invokeConfigureStakeholders(eq("Fitoyo"), eq("15"), eq(stakeholders), eq("apply")))
                .thenReturn(agentResponse);

        ConfigureStakeholdersResponse res = controller.configureStakeholders(15L, stakeholders);

        assertThat(res.status()).isEqualTo("ok");
        assertThat(res.nextCommand()).isEqualTo("/plan-product-scope");
        assertThat(res.rolesConfigured()).isEqualTo(2);
    }
}
