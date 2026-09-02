package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.http.HttpStatus;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.SetupAgentRequest;
import com.talentserv.blink.error.ApiException;

@ExtendWith(MockitoExtension.class)
class SetupAgentServiceTest {

    @Mock
    private ProjectService projectService;

    @Mock
    private AgentRuntimeService agentRuntimeService;

    private SetupAgentService service;

    @BeforeEach
    void setUp() {
        service = new SetupAgentService(projectService, agentRuntimeService);
    }

    @Test
    void startUsesRequirementThenCallsWorkerApply() throws Exception {
        Project project = new Project();
        project.setId(42L);
        project.setProjectName("Food Delivery");
        project.setDescription("Fallback description");
        when(projectService.requireProject(42L)).thenReturn(project);

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("runId", "setup-1");
        body.put("command", "setup-new-workspace");
        body.put("status", "overlay_ready");
        body.put("identitySource", "requirement");
        body.put("nextCommand", "configure-stakeholders");
        body.put("message", "Ready");
        body.putArray("overlayFiles").addObject()
                .put("path", ".cursor/ai-sdlc/workspace-context.md")
                .put("content", "# Food Delivery");
        when(agentRuntimeService.invokeSetupApply(eq("Food Delivery"), eq("Users order food"), eq("Fallback description")))
                .thenReturn(body);

        var result = service.start(42L, new SetupAgentRequest("Users order food", "apply"));

        assertThat(result.status()).isEqualTo("overlay_ready");
        assertThat(result.identitySource()).isEqualTo("requirement");
        assertThat(result.overlayFiles()).hasSize(1);
        assertThat(result.overlayFiles().getFirst().path()).isEqualTo(".cursor/ai-sdlc/workspace-context.md");
        verify(agentRuntimeService).invokeSetupApply("Food Delivery", "Users order food", "Fallback description");
    }

    @Test
    void startRejectsDiscoveryMode() {
        Project project = new Project();
        project.setId(1L);
        project.setProjectName("Demo");
        when(projectService.requireProject(1L)).thenReturn(project);

        assertThatThrownBy(() -> service.start(1L, new SetupAgentRequest(null, "discovery")))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("apply");
    }

    @Test
    void applyPassesNullRequirementSoWorkerCanUseDescription() {
        Project project = new Project();
        project.setProjectName("Food Delivery");
        project.setDescription("A marketplace for nearby restaurants.");
        when(agentRuntimeService.invokeSetupApply(any(), any(), any())).thenReturn(new ObjectMapper().createObjectNode());

        service.apply(project, "  ");

        ArgumentCaptor<String> requirement = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> description = ArgumentCaptor.forClass(String.class);
        verify(agentRuntimeService).invokeSetupApply(eq("Food Delivery"), requirement.capture(), description.capture());
        assertThat(requirement.getValue()).isNull();
        assertThat(description.getValue()).isEqualTo("A marketplace for nearby restaurants.");
    }

    @Test
    void applyBestEffortReturnsSkippedWhenWorkerFails() {
        Project project = new Project();
        project.setProjectName("Food Delivery");
        when(agentRuntimeService.invokeSetupApply(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Agent runtime token is not configured."));

        var node = service.applyBestEffort(project, "Users order food");

        assertThat(node.path("status").asText()).isEqualTo("skipped");
        assertThat(node.path("overlayFiles")).isEmpty();
        assertThat(node.path("message").asText()).contains("token");
    }

    @Test
    void overlayFilesKeepOnlyAiSdlcPaths() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        var files = body.putArray("overlayFiles");
        files.addObject().put("path", ".cursor/ai-sdlc/workspace-context.md").put("content", "ok");
        files.addObject().put("path", ".cursor/commands/hack.md").put("content", "nope");
        files.addObject().put("path", "../secret.txt").put("content", "nope");

        var overlay = SetupAgentService.overlayFiles(body);

        assertThat(overlay).hasSize(1);
        assertThat(overlay.getFirst().path()).isEqualTo(".cursor/ai-sdlc/workspace-context.md");
    }
}
