package com.talentserv.blink.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.SetupAgentRequest;
import com.talentserv.blink.dto.SetupAgentResponse;
import com.talentserv.blink.error.ApiException;

@Service
public class SetupAgentService {

    private static final Logger log = LoggerFactory.getLogger(SetupAgentService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectService projectService;
    private final AgentRuntimeService agentRuntimeService;

    public SetupAgentService(ProjectService projectService, AgentRuntimeService agentRuntimeService) {
        this.projectService = projectService;
        this.agentRuntimeService = agentRuntimeService;
    }

    public SetupAgentResponse start(Long projectId, SetupAgentRequest request) {
        Project project = projectService.requireProject(projectId);
        String mode = request != null && request.mode() != null && !request.mode().isBlank()
                ? request.mode().trim().toLowerCase(Locale.ROOT)
                : "apply";
        if (!"apply".equals(mode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Only setup-new-workspace apply is hosted.");
        }
        String requirementText = request != null ? trimToNull(request.requirementText()) : null;
        return parse(apply(project, requirementText));
    }

    public JsonNode apply(Project project, String requirementText) {
        String requirement = trimToNull(requirementText);
        String description = project.getDescription();
        return agentRuntimeService.invokeSetupApply(project.getProjectName(), requirement, description);
    }

    /** Zip download still succeeds if the hosted setup command is unavailable. */
    public JsonNode applyBestEffort(Project project, String requirementText) {
        long started = System.currentTimeMillis();
        try {
            log.info("Download overlay calling setup-new-workspace project={}", project.getProjectName());
            JsonNode result = agentRuntimeService.invokeSetupApply(
                    project.getProjectName(),
                    trimToNull(requirementText),
                    project.getDescription(),
                    Timeout.ofSeconds(20)
            );
            log.info(
                    "Download overlay ready status={} ms={}",
                    result.path("status").asText(""),
                    System.currentTimeMillis() - started
            );
            return result;
        } catch (RuntimeException ex) {
            log.warn(
                    "Setup overlay skipped after {}ms: {}",
                    System.currentTimeMillis() - started,
                    ex.getMessage()
            );
            ObjectNode node = MAPPER.createObjectNode();
            node.put("status", "skipped");
            node.put("message", ex.getMessage() == null ? "Setup overlay was skipped." : ex.getMessage());
            node.putArray("overlayFiles");
            return node;
        }
    }

    public static List<ZipPackageService.OverlayFile> overlayFiles(JsonNode root) {
        List<ZipPackageService.OverlayFile> files = new ArrayList<>();
        if (root == null) {
            return files;
        }
        for (JsonNode item : root.path("overlayFiles")) {
            String path = ZipPackageService.sanitizeOverlayPath(item.path("path").asText(""));
            String content = item.path("content").asText("");
            if (path != null) {
                files.add(new ZipPackageService.OverlayFile(path, content));
            }
        }
        return files;
    }

    public static List<ZipPackageService.OverlayFile> overlayFilesFromResponse(SetupAgentResponse response) {
        List<ZipPackageService.OverlayFile> files = new ArrayList<>();
        if (response == null || response.overlayFiles() == null) {
            return files;
        }
        for (SetupAgentResponse.OverlayFile item : response.overlayFiles()) {
            String path = ZipPackageService.sanitizeOverlayPath(item.path());
            if (path != null) {
                files.add(new ZipPackageService.OverlayFile(path, item.content() == null ? "" : item.content()));
            }
        }
        return files;
    }

    static SetupAgentResponse parse(JsonNode root) {
        if (root == null || !root.isObject()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Setup agent returned invalid JSON.");
        }
        List<SetupAgentResponse.OverlayFile> overlayFiles = new ArrayList<>();
        for (JsonNode item : root.path("overlayFiles")) {
            String path = ZipPackageService.sanitizeOverlayPath(item.path("path").asText(""));
            if (path != null) {
                overlayFiles.add(new SetupAgentResponse.OverlayFile(path, item.path("content").asText("")));
            }
        }
        List<String> errors = new ArrayList<>();
        for (JsonNode item : root.path("errors")) {
            if (item.isTextual()) {
                errors.add(item.asText());
            }
        }
        return new SetupAgentResponse(
                textOrNull(root.get("runId")),
                textOrNull(root.get("command")),
                textOrNull(root.get("status")),
                boolOrNull(root.get("contextReady")),
                boolOrNull(root.get("deliveryReady")),
                boolOrNull(root.get("gitWritten")),
                textOrNull(root.get("identitySource")),
                overlayFiles,
                textOrNull(root.get("nextCommand")),
                textOrNull(root.get("message")),
                errors
        );
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Boolean boolOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isBoolean()) {
            return null;
        }
        return node.asBoolean();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
