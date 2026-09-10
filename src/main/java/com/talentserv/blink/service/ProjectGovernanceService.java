package com.talentserv.blink.service;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ConfigureStakeholdersResponse;
import com.talentserv.blink.dto.PlanProductScopeRequest;
import com.talentserv.blink.dto.PlanProductScopeResponse;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderRequest;

/**
 * Service orchestrating project governance and product-scope operations.
 * Decouples agent invocation, S3 overlay writes, SoD processing, and response parsing
 * from Spring MVC controllers.
 */
@Service
public class ProjectGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(ProjectGovernanceService.class);

    private final AgentRuntimeService agentRuntimeService;
    private final S3WorkspaceService s3WorkspaceService;
    private final ProjectService projectService;
    private final java.util.concurrent.ConcurrentHashMap<String, CachedGovernance> governanceCache = new java.util.concurrent.ConcurrentHashMap<>();

    private record CachedGovernance(List<String> warnings, String nextCommand) {}

    public ProjectGovernanceService(
            AgentRuntimeService agentRuntimeService,
            S3WorkspaceService s3WorkspaceService,
            ProjectService projectService
    ) {
        this.agentRuntimeService = agentRuntimeService;
        this.s3WorkspaceService = s3WorkspaceService;
        this.projectService = projectService;
    }

    private String computeCacheKey(Long projectId, List<StakeholderRequest> stakeholders) {
        if (projectId == null || stakeholders == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(String.valueOf(projectId)).append(":");
        for (StakeholderRequest s : stakeholders) {
            if (s != null) {
                sb.append(s.roleCode()).append("=").append(s.name()).append("<").append(s.email()).append(">;");
            }
        }
        return sb.toString();
    }

    public ProjectResponse applyStakeholderGovernance(ProjectResponse project, List<StakeholderRequest> stakeholders) {
        if (stakeholders == null || stakeholders.isEmpty()) {
            return project;
        }
        String cacheKey = computeCacheKey(project.id(), stakeholders);
        CachedGovernance cached = governanceCache.get(cacheKey);
        if (cached != null) {
            log.info("Reusing cached stakeholder governance for projectId={} (no remote agent call needed)", project.id());
            return project.withGovernance(cached.warnings(), cached.nextCommand());
        }
        try {
            JsonNode result = agentRuntimeService.invokeConfigureStakeholders(
                    project.projectName(),
                    String.valueOf(project.id()),
                    stakeholders,
                    "apply"
            );
            if (result != null) {
                List<String> warnings = extractSodWarnings(result);
                String nextCommand = result.path("nextCommand").asText("/plan-product-scope");
                governanceCache.put(cacheKey, new CachedGovernance(warnings, nextCommand));
                persistOverlayFiles(project.projectName(), project.id(), result, "configure-stakeholders");
                return project.withGovernance(warnings, nextCommand);
            }
        } catch (Exception ex) {
            log.warn("Agent configure-stakeholders invocation failed: {}", ex.getMessage());
        }
        return project;
    }

    public ConfigureStakeholdersResponse configureStakeholders(Long id, List<StakeholderRequest> stakeholders) {
        Project project = projectService.requireProject(id);
        List<StakeholderRequest> toApply = stakeholders;
        if (toApply == null || toApply.isEmpty()) {
            ProjectResponse pr = projectService.get(id);
            toApply = pr.stakeholders().stream()
                    .map(s -> new StakeholderRequest(s.roleCode(), s.name(), s.email()))
                    .toList();
        }
        String cacheKey = computeCacheKey(id, toApply);
        CachedGovernance cached = governanceCache.get(cacheKey);
        if (cached != null) {
            log.info("Reusing cached stakeholder governance for projectId={} (no remote agent call needed)", id);
            return new ConfigureStakeholdersResponse(
                    "ok",
                    "Stakeholders already configured.",
                    cached.nextCommand(),
                    cached.warnings(),
                    List.of(),
                    toApply.size()
            );
        }
        try {
            JsonNode result = agentRuntimeService.invokeConfigureStakeholders(
                    project.getProjectName(),
                    String.valueOf(id),
                    toApply,
                    "apply"
            );
            if (result != null) {
                List<String> warnings = extractSodWarnings(result);
                List<String> errors = extractErrors(result);
                String nextCommand = result.path("nextCommand").asText("/plan-product-scope");
                String status = result.path("status").asText("ok");
                String message = result.path("message").asText("Stakeholders configured successfully.");
                int configured = result.path("acceptedFileCount").asInt(toApply.size());

                governanceCache.put(cacheKey, new CachedGovernance(warnings, nextCommand));
                persistOverlayFiles(project.getProjectName(), id, result, "configure-stakeholders");
                return new ConfigureStakeholdersResponse(status, message, nextCommand, warnings, errors, configured);
            }
        } catch (Exception ex) {
            log.warn("Agent configure-stakeholders invocation failed: {}", ex.getMessage());
            return new ConfigureStakeholdersResponse(
                    "error",
                    "Agent runtime call failed: " + ex.getMessage(),
                    "/plan-product-scope",
                    List.of(),
                    List.of(ex.getMessage()),
                    0
            );
        }
        return new ConfigureStakeholdersResponse("ok", "Stakeholders configured.", "/plan-product-scope", List.of(), List.of(), toApply.size());
    }

    public PlanProductScopeResponse planProductScope(Long id, PlanProductScopeRequest request) {
        Project project = projectService.requireProject(id);
        String reqText = (request != null && request.requirementText() != null && !request.requirementText().isBlank())
                ? request.requirementText()
                : joinProjectDetails(project.getProjectName(), project.getDescription());
        String actor = (request != null && request.actor() != null) ? request.actor() : "operator";
        String projectIdentifier = ProjectCodes.slug(project.getProjectName());
        if (projectIdentifier.isBlank()) {
            projectIdentifier = "PROJECT";
        }
        return executePlanProductScope(project.getProjectName(), projectIdentifier, id, reqText, actor);
    }

    public PlanProductScopeResponse planProductScopeStandalone(PlanProductScopeRequest request) {
        String projectName = request.projectName() != null && !request.projectName().isBlank() ? request.projectName() : "project";
        Long projectId = null;
        if (request.projectId() != null && !request.projectId().isBlank()) {
            try {
                projectId = Long.parseLong(request.projectId());
            } catch (NumberFormatException ignored) {
            }
        }
        String projectIdentifier = request.projectId();
        if (projectIdentifier == null || projectIdentifier.isBlank() || projectIdentifier.matches("\\d+")) {
            projectIdentifier = ProjectCodes.slug(projectName);
            if (projectIdentifier.isBlank()) {
                projectIdentifier = "PROJECT";
            }
        }
        return executePlanProductScope(projectName, projectIdentifier, projectId, request.requirementText(), request.actor());
    }

    private PlanProductScopeResponse executePlanProductScope(
            String projectName,
            String projectIdStr,
            Long numericProjectId,
            String requirementText,
            String actor
    ) {
        try {
            JsonNode result = agentRuntimeService.invokePlanProductScope(
                    projectName,
                    projectIdStr,
                    requirementText,
                    actor
            );
            if (result != null) {
                String status = result.path("status").asText("ok");
                String message = result.path("message").asText("Product scope planned successfully.");
                String nextCommand = result.path("nextCommand").asText("/confirm-product-scope");
                String proposalDigest = result.path("proposalDigest").asText(null);
                List<String> epicIds = extractStringList(result.path("epicIds"));
                List<String> storyIds = extractStringList(result.path("storyIds"));
                JsonNode productScope = result.path("productScope");
                List<String> errors = extractErrors(result);

                if (numericProjectId != null) {
                    persistOverlayFiles(projectName, numericProjectId, result, "plan-product-scope");
                }

                return new PlanProductScopeResponse(
                        status,
                        message,
                        nextCommand,
                        proposalDigest,
                        epicIds,
                        storyIds,
                        productScope,
                        errors
                );
            }
        } catch (Exception ex) {
            log.warn("Agent plan-product-scope invocation failed: {}", ex.getMessage());
            return new PlanProductScopeResponse(
                    "error",
                    "Agent runtime call failed: " + ex.getMessage(),
                    "/confirm-product-scope",
                    null,
                    List.of(),
                    List.of(),
                    null,
                    List.of(ex.getMessage())
            );
        }
        return new PlanProductScopeResponse(
                "error",
                "No response from agent runtime.",
                "/confirm-product-scope",
                null,
                List.of(),
                List.of(),
                null,
                List.of("NO_RESPONSE")
        );
    }

    private void persistOverlayFiles(String projectName, Long projectId, JsonNode result, String context) {
        if (!s3WorkspaceService.enabled() || result == null || !result.has("overlayFiles") || projectId == null) {
            return;
        }
        List<ZipPackageService.OverlayFile> overlayFiles = new ArrayList<>();
        for (JsonNode item : result.path("overlayFiles")) {
            String path = ZipPackageService.sanitizeOverlayPath(item.path("path").asText(""));
            if (path != null) {
                overlayFiles.add(new ZipPackageService.OverlayFile(path, item.path("content").asText("")));
            }
        }
        if (!overlayFiles.isEmpty()) {
            s3WorkspaceService.putCursorOverlayAsync(projectName, projectId, overlayFiles);
        }
    }

    public static List<String> extractSodWarnings(JsonNode node) {
        List<String> warnings = new ArrayList<>();
        JsonNode list = node.path("sodWarnings");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if (item.isTextual()) {
                    warnings.add(item.asText());
                } else if (item.isObject()) {
                    String reason = item.path("reason").asText("");
                    String code = item.path("code").asText("");
                    if (!reason.isBlank()) {
                        warnings.add(reason);
                    } else if (!code.isBlank()) {
                        warnings.add(code);
                    }
                }
            }
        }
        return warnings;
    }

    private static String joinProjectDetails(String projectName, String description) {
        String name = projectName == null ? "" : projectName.trim();
        String details = description == null ? "" : description.trim();
        if (!name.isBlank() && !details.isBlank()) {
            return "# " + name + "\n\n" + details;
        }
        return details.isBlank() ? name : details;
    }

    public static List<String> extractStringList(JsonNode node) {
        List<String> list = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    list.add(item.asText());
                }
            }
        }
        return list;
    }

    public static List<String> extractErrors(JsonNode node) {
        List<String> errors = new ArrayList<>();
        JsonNode list = node.path("errors");
        if (list.isArray()) {
            for (JsonNode item : list) {
                if (item.isTextual()) {
                    errors.add(item.asText());
                }
            }
        }
        return errors;
    }
}
