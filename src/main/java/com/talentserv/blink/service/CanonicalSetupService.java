package com.talentserv.blink.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.error.ApiException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Runs the framework's deterministic Python projector for a hosted workspace.
 *
 * <p>The Worker is deliberately not used as an overlay-file writer here. The
 * framework projector remains the authority for canonical setup YAML and
 * reports; this service only supplies Blink's already-authorized project data
 * and accepts a bounded, safe manifest back.</p>
 */
@Service
public class CanonicalSetupService {

    private static final Logger log = LoggerFactory.getLogger(CanonicalSetupService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_FILES = 100;
    private static final int MAX_FILE_BYTES = 100_000;
    private static final int MAX_TOTAL_BYTES = 2_000_000;
    private static final Set<String> REQUIRED_CANONICAL_FILES = Set.of(
            ".cursor/ai-sdlc/setup/project-initialisation-input.yaml",
            ".cursor/ai-sdlc/setup/setup-result.yaml",
            ".cursor/ai-sdlc/setup/setup-response-contract.yaml",
            ".cursor/ai-sdlc/governance/role-registry.yaml",
            ".cursor/ai-sdlc/greenfield-project-spec.yaml",
            ".cursor/ai-sdlc/intake/requirements/requirement-source-history.yaml"
    );

    private final BlinkProperties properties;
    private final ZipPackageService zipPackageService;
    private final AgentRuntimeService agentRuntimeService;

    public CanonicalSetupService(
            BlinkProperties properties,
            ZipPackageService zipPackageService,
            AgentRuntimeService agentRuntimeService
    ) {
        this.properties = properties;
        this.zipPackageService = zipPackageService;
        this.agentRuntimeService = agentRuntimeService;
    }

    /**
     * Generates a complete pending-topology overlay and normalizes the result
     * to the setup response contract already consumed by Java and the UI.
     * Prefers the HTTP Python Agent Runtime service if reachable, otherwise
     * falls back to the local Python script projector.
     */
    public JsonNode apply(Project project, String requirementText, JsonNode blinkContext) {
        if (project == null || project.getProjectName() == null || project.getProjectName().isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Project name is required for canonical setup.");
        }

        ObjectNode requestPayload = requestPayload(project, requirementText, blinkContext);

        // Try Python Agent Runtime over HTTP first (AWS Lambda / local FastAPI)
        try {
            ObjectNode agentRequest = requestPayload.deepCopy();
            agentRequest.put("command", "setup-new-workspace");
            agentRequest.put("mode", "apply");
            JsonNode agentResponse = agentRuntimeService.invoke(agentRequest);
            if (agentResponse != null && ("ok".equals(agentResponse.path("status").asText("")) || "overlay_ready".equals(agentResponse.path("status").asText("")))) {
                return normalizeManifest(agentResponse);
            }
        } catch (Exception ex) {
            log.info("Agent runtime HTTP setup unavailable ({}), attempting local projector...", ex.getMessage());
        }

        Path runRoot = null;
        Path clonedFramework = null;
        try {
            runRoot = Files.createTempDirectory("blink-canonical-setup-");
            Path framework = zipPackageService.resolveAutomationSdlc();
            if (framework == null) {
                clonedFramework = cloneFramework(runRoot.resolve("automation_sdlc"));
                framework = clonedFramework;
            }
            Path script = framework.resolve("ai-sdlc").resolve("tools").resolve("setup").resolve("run_hosted_greenfield_apply.py");
            if (!Files.isRegularFile(script)) {
                throw new ApiException(
                        HttpStatus.SERVICE_UNAVAILABLE,
                        "Canonical workspace setup is unavailable because the framework projector is missing."
                );
            }

            Path workspace = runRoot.resolve("workspace");
            Path input = runRoot.resolve("input.json");
            Path output = runRoot.resolve("manifest.json");
            Files.createDirectories(workspace);
            Files.writeString(input, requestPayload(project, requirementText, blinkContext).toString(), StandardCharsets.UTF_8);

            runProjector(script, input, output, workspace, framework);
            if (!Files.isRegularFile(output)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup did not return a manifest.");
            }
            JsonNode manifest = MAPPER.readTree(Files.readString(output, StandardCharsets.UTF_8));
            return normalizeManifest(manifest);
        } catch (ApiException ex) {
            throw ex;
        } catch (IOException ex) {
            log.warn("Canonical setup failed: {}", ex.toString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not build the canonical workspace setup.");
        } finally {
            deleteTree(runRoot);
        }
    }

    private ObjectNode requestPayload(Project project, String requirementText, JsonNode blinkContext) {
        String requirement = trimToNull(requirementText);
        String description = trimToNull(project.getDescription());
        if (requirement == null && description == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Add a requirement or project description before setup.");
        }
        ObjectNode payload = MAPPER.createObjectNode();
        payload.put("projectId", project.getId() == null ? "" : String.valueOf(project.getId()));
        payload.put("projectName", project.getProjectName().trim());
        if (requirement != null) {
            payload.put("requirementText", requirement);
        }
        if (description != null) {
            payload.put("projectDescription", description);
            payload.put("description", description);
        }
        if (blinkContext != null && blinkContext.isObject()) {
            copyBlinkContext(payload, blinkContext);
        }
        return payload;
    }

    /**
     * Converts UI-shaped, non-secret wizard data to the narrow portable input
     * accepted by the framework projector. Tokens are intentionally omitted by
     * the caller and never copied here.
     */
    private static void copyBlinkContext(ObjectNode payload, JsonNode context) {
        ObjectNode stakeholders = payload.putObject("stakeholders");
        for (JsonNode assignment : context.path("stakeholderAssignments")) {
            String role = text(assignment, "roleId", "");
            String name = text(assignment, "personName", "");
            String email = text(assignment, "personEmail", "");
            if (!role.isBlank() && !name.isBlank()) {
                if (!email.isBlank()) {
                    stakeholders.put(role, name + " <" + email + ">");
                } else {
                    stakeholders.put(role, name);
                }
            }
        }

        ObjectNode topology = payload.putObject("topology");
        String repositoryModel = text(context, "repositoryModel", "");
        if (!repositoryModel.isBlank()) {
            topology.put("structure", repositoryModel);
            topology.put("repository_model", repositoryModel);
        }
        String topologyDescription = text(context, "topology", "");
        if (!topologyDescription.isBlank()) {
            topology.put("description", topologyDescription);
        }

        ArrayNode repositories = payload.putArray("repositories");
        for (JsonNode repository : context.path("repositories")) {
            String name = text(repository, "name", "");
            if (name.isBlank()) {
                continue;
            }
            String id = slug(name);
            if (id.isBlank()) {
                continue;
            }
            ObjectNode row = repositories.addObject();
            row.put("repo_id", id);
            row.put("name", name);
            String purpose = text(repository, "purpose", "");
            if (!purpose.isBlank()) {
                row.put("role", purpose);
            }
        }

        ArrayNode integrations = payload.putArray("integration_handles");
        for (JsonNode integration : context.path("integrations")) {
            String provider = text(integration, "provider", "");
            String category = integrationCategory(provider);
            if (category == null) {
                continue;
            }
            ObjectNode row = integrations.addObject();
            row.put("integration_id", provider);
            row.put("category", category);
            row.put("user_choice", "connect_now");
            String handle = firstText(
                    integration,
                    "organization",
                    "projectKey",
                    "baseUrl",
                    "workspace",
                    "account"
            );
            if (!handle.isBlank()) {
                row.put("handle", handle);
            }
        }
    }

    private static String integrationCategory(String provider) {
        return switch (provider) {
            case "github", "bitbucket" -> "source_control";
            case "jira" -> "issue_tracking";
            case "confluence" -> "documentation";
            default -> null;
        };
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = text(node, field, "");
            if (!value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String slug(String value) {
        return value.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    private void runProjector(Path script, Path input, Path output, Path workspace, Path framework) throws IOException {
        List<String> command = List.of(
                configuredPython(framework),
                script.toString(),
                "--input", input.toString(),
                "--output", output.toString(),
                "--workspace-root", workspace.toString()
        );
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start();
        try {
            if (!process.waitFor(properties.getCanonicalSetupTimeout().toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "Canonical workspace setup timed out.");
            }
            if (process.exitValue() != 0) {
                log.warn("Canonical setup projector failed exitCode={}", process.exitValue());
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not build the canonical workspace setup.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Canonical workspace setup was interrupted.");
        }
    }

    private JsonNode normalizeManifest(JsonNode manifest) {
        if (manifest == null || !manifest.isObject()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup returned an invalid manifest.");
        }
        String status = manifest.path("status").asText("");
        if (!"ok".equals(status) && !"overlay_ready".equals(status)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup did not validate.");
        }

        JsonNode sourceFiles = manifest.has("overlayFiles") ? manifest.path("overlayFiles") : manifest.path("files");
        if (!sourceFiles.isArray()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup did not return overlay files.");
        }
        ArrayNode accepted = MAPPER.createArrayNode();
        Set<String> paths = new HashSet<>();
        int totalBytes = 0;
        for (JsonNode file : sourceFiles) {
            if (accepted.size() >= MAX_FILES || !file.isObject()) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup returned too many files.");
            }
            String path = ZipPackageService.sanitizeOverlayPath(file.path("path").asText(""));
            String content = file.path("content").asText(null);
            if (path == null || content == null || content.indexOf('\u0000') >= 0) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup returned an unsafe file.");
            }
            int bytes = content.getBytes(StandardCharsets.UTF_8).length;
            totalBytes += bytes;
            if (bytes > MAX_FILE_BYTES || totalBytes > MAX_TOTAL_BYTES) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup is too large.");
            }
            ObjectNode row = accepted.addObject();
            row.put("path", path);
            row.put("content", content);
            paths.add(path);
        }
        if (accepted.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup returned no overlay files.");
        }
        if (!paths.containsAll(REQUIRED_CANONICAL_FILES)) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Canonical workspace setup is incomplete.");
        }

        ObjectNode normalized = MAPPER.createObjectNode();
        normalized.put("runId", text(manifest, "runId", UUID.randomUUID().toString()));
        normalized.put("command", "setup-new-workspace");
        normalized.put("status", "overlay_ready");
        normalized.put("contextReady", manifest.path("contextReady").asBoolean(false));
        normalized.put("deliveryReady", manifest.path("deliveryReady").asBoolean(false));
        normalized.put("gitWritten", false);
        normalized.put("identitySource", text(manifest, "identitySource", "requirement"));
        normalized.set("overlayFiles", accepted);
        normalized.put("nextCommand", nextAction(manifest));
        normalized.put("message", text(manifest, "message", "Canonical workspace setup is ready."));
        normalized.set("errors", manifest.path("errors").isArray() ? manifest.path("errors") : MAPPER.createArrayNode());
        normalized.set("setupResult", manifest.path("setupResult").isObject() ? manifest.path("setupResult") : MAPPER.createObjectNode());
        normalized.put("acceptedFileCount", accepted.size());
        return normalized;
    }

    private Path cloneFramework(Path destination) throws IOException {
        String gitUrl = trimToNull(properties.getAutomationSdlcGitUrl());
        if (gitUrl == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not find the automation_sdlc framework.");
        }
        Process process = new ProcessBuilder("git", "clone", "--depth", "1", gitUrl, destination.toString())
                .redirectErrorStream(true)
                .start();
        try {
            if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
                process.destroyForcibly();
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Could not clone automation_sdlc for canonical setup.");
            }
            return destination;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Could not clone automation_sdlc for canonical setup.");
        }
    }

    private String configuredPython(Path framework) {
        String python = trimToNull(properties.getCanonicalSetupPython());
        if (python == null || "python3".equals(python)) {
            Path bundledWindowsPython = framework.resolve(".tools").resolve("python312").resolve("python.exe");
            if (Files.isRegularFile(bundledWindowsPython)) {
                return bundledWindowsPython.toString();
            }
        }
        return python == null ? "python3" : python;
    }

    private static String text(JsonNode node, String field, String fallback) {
        String value = node.path(field).asText("");
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String nextAction(JsonNode manifest) {
        JsonNode action = manifest.get("nextAction");
        if (action != null && action.isObject()) {
            String command = firstText(action, "user_command", "command", "id");
            if (!command.isBlank()) {
                return command;
            }
        }
        return text(manifest, "nextAction", text(manifest, "nextCommand", "configure-stakeholders"));
    }

    private static String trimToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private static void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) {
            return;
        }
        try {
            Files.walkFileTree(directory, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.deleteIfExists(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exception) throws IOException {
                    Files.deleteIfExists(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException ex) {
            log.debug("Could not delete canonical setup temp directory {}: {}", directory, ex.toString());
        }
    }
}
