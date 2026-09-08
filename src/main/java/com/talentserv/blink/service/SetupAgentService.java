package com.talentserv.blink.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.SetupAgentRequest;
import com.talentserv.blink.dto.SetupAgentResponse;
import com.talentserv.blink.error.ApiException;

@Service
public class SetupAgentService {

    private final ProjectService projectService;
    private final CanonicalSetupService canonicalSetupService;

    public SetupAgentService(ProjectService projectService, CanonicalSetupService canonicalSetupService) {
        this.projectService = projectService;
        this.canonicalSetupService = canonicalSetupService;
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
        return canonicalSetupService.apply(project, trimToNull(requirementText), null);
    }

    /**
     * Full setup is fail-closed. A zip that claims a canonical overlay but
     * contains the former partial Worker result is not a valid delivery.
     */
    public JsonNode applyBestEffort(Project project, String requirementText) {
        return applyBestEffort(project, requirementText, null);
    }

    public JsonNode applyBestEffort(Project project, String requirementText, JsonNode blinkContext) {
        return canonicalSetupService.apply(project, trimToNull(requirementText), blinkContext);
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
                root.path("acceptedFileCount").isInt() ? root.path("acceptedFileCount").asInt() : overlayFiles.size(),
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
