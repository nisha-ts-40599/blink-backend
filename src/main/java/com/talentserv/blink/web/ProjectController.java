package com.talentserv.blink.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ConfigureStakeholdersResponse;
import com.talentserv.blink.dto.PlanProductScopeRequest;
import com.talentserv.blink.dto.PlanProductScopeResponse;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.SetupAgentRequest;
import com.talentserv.blink.dto.SetupAgentResponse;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.dto.WorkspaceInventoryResponse;
import com.talentserv.blink.dto.WorkspaceStatusResponse;
import com.talentserv.blink.service.AgentRuntimeService;
import com.talentserv.blink.service.McpJsonWriter;
import com.talentserv.blink.service.ProjectCodes;
import com.talentserv.blink.service.ProjectGovernanceService;
import com.talentserv.blink.service.ProjectService;
import com.talentserv.blink.service.RequirementMarkdownService;
import com.talentserv.blink.service.S3WorkspaceService;
import com.talentserv.blink.service.SetupAgentService;
import com.talentserv.blink.service.WorkspaceNames;
import com.talentserv.blink.service.ZipPackageService;
import com.talentserv.blink.config.BlinkProperties;

import jakarta.validation.Valid;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final Logger log = LoggerFactory.getLogger(ProjectController.class);
    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProjectService projectService;
    private final RequirementMarkdownService requirementMarkdownService;
    private final ZipPackageService zipPackageService;
    private final SetupAgentService setupAgentService;
    private final S3WorkspaceService s3WorkspaceService;
    private final AgentRuntimeService agentRuntimeService;
    private final ProjectGovernanceService projectGovernanceService;
    private final BlinkProperties properties;

    public ProjectController(
            ProjectService projectService,
            RequirementMarkdownService requirementMarkdownService,
            ZipPackageService zipPackageService,
            SetupAgentService setupAgentService,
            S3WorkspaceService s3WorkspaceService,
            AgentRuntimeService agentRuntimeService,
            ProjectGovernanceService projectGovernanceService,
            BlinkProperties properties
    ) {
        this.projectService = projectService;
        this.requirementMarkdownService = requirementMarkdownService;
        this.zipPackageService = zipPackageService;
        this.setupAgentService = setupAgentService;
        this.s3WorkspaceService = s3WorkspaceService;
        this.agentRuntimeService = agentRuntimeService;
        this.projectGovernanceService = projectGovernanceService;
        this.properties = properties;
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    @GetMapping("/workspace-tree")
    public WorkspaceInventoryResponse workspaceTree(
            @RequestParam String projectName,
            @RequestParam(required = false) Long projectId
    ) {
        return s3WorkspaceService.inspect(projectName, projectId);
    }

    @GetMapping("/workspace-status")
    public WorkspaceStatusResponse workspaceStatus(
            @RequestParam String projectName,
            @RequestParam(required = false) Long projectId
    ) {
        WorkspaceStatusResponse progress = s3WorkspaceService.progress(projectName, projectId);
        if (progress == null) {
            return new WorkspaceStatusResponse(null, null, 0, 0, 0, false);
        }
        return progress;
    }

    @PostMapping
    public ProjectResponse create(@Valid @RequestBody ProjectRequest request) {
        ProjectResponse created = attachWorkspace(projectService.create(request), true);
        return projectGovernanceService.applyStakeholderGovernance(created, request.stakeholders());
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
        ProjectResponse updated = attachWorkspace(projectService.update(id, request), true);
        return projectGovernanceService.applyStakeholderGovernance(updated, request.stakeholders());
    }

    @PostMapping("/{id}/configure-stakeholders")
    public ConfigureStakeholdersResponse configureStakeholders(
            @PathVariable Long id,
            @RequestBody(required = false) List<StakeholderRequest> stakeholders
    ) {
        return projectGovernanceService.configureStakeholders(id, stakeholders);
    }

    @PostMapping("/{id}/plan-product-scope")
    public PlanProductScopeResponse planProductScope(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) PlanProductScopeRequest request
    ) {
        return projectGovernanceService.planProductScope(id, request);
    }

    @PostMapping("/plan-product-scope")
    public PlanProductScopeResponse planProductScopeStandalone(
            @Valid @RequestBody PlanProductScopeRequest request
    ) {
        return projectGovernanceService.planProductScopeStandalone(request);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        return attachWorkspace(projectService.get(id), false);
    }

    @GetMapping("/{id}/workspace")
    public WorkspaceInventoryResponse workspace(@PathVariable Long id) {
        Project project = projectService.requireProject(id);
        return s3WorkspaceService.inspect(project.getProjectName(), id);
    }

    @PostMapping("/{id}/setup")
    public SetupAgentResponse setup(@PathVariable Long id, @RequestBody(required = false) SetupAgentRequest request) {
        SetupAgentResponse response = setupAgentService.start(id, request);
        if (s3WorkspaceService.enabled()) {
            Project project = projectService.requireProject(id);
            s3WorkspaceService.provisionAsync(project.getProjectName(), id);
            s3WorkspaceService.putCursorOverlayAsync(
                    project.getProjectName(),
                    id,
                    SetupAgentService.overlayFilesFromResponse(response)
            );
        }
        return response;
    }

    @PostMapping(path = "/{id}/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "requirementsText", required = false) String requirementsText,
            @RequestParam(value = "repoName", required = false) List<String> repoNames,
            @RequestParam(value = "repoPurpose", required = false) List<String> repoPurposes,
            @RequestParam(value = "repoDescription", required = false) List<String> repoDescriptions,
            @RequestParam(value = "setupContext", required = false) String setupContext,
            @RequestParam(value = "mcpProvider", required = false) List<String> mcpProviders,
            @RequestParam(value = "mcpJiraUrl", required = false) String mcpJiraUrl,
            @RequestParam(value = "mcpJiraEmail", required = false) String mcpJiraEmail,
            @RequestParam(value = "mcpJiraCloudId", required = false) String mcpJiraCloudId,
            @RequestParam(value = "mcpConfluenceUrl", required = false) String mcpConfluenceUrl,
            @RequestParam(value = "mcpConfluenceEmail", required = false) String mcpConfluenceEmail
    ) throws IOException {
        Project project = projectService.requireProject(id);
        long started = System.currentTimeMillis();
        log.info("Download start projectId={} name={}", id, project.getProjectName());
        String markdown = requirementMarkdownService.toMarkdown(project.getProjectName(), file, requirementsText);
        log.info("Download building canonical setup workspace");
        var setup = setupAgentService.applyBestEffort(project, markdown, parseSetupContext(setupContext));
        var overlay = SetupAgentService.overlayFiles(setup);
        log.info(
                "Download setup status={} overlayFiles={} elapsedMs={}",
                setup.path("status").asText(""),
                overlay.size(),
                System.currentTimeMillis() - started
        );
        ZipPackageService.WorkspaceBundle bundle;
        if (s3WorkspaceService.enabled()) {
            log.info("Download writing requirement and .cursor overlay to S3 asynchronously without waiting for template copy");
            s3WorkspaceService.provisionAsync(project.getProjectName(), id);
            s3WorkspaceService.putRequirementAsync(project.getProjectName(), id, markdown);
            s3WorkspaceService.putCursorOverlayAsync(project.getProjectName(), id, overlay);
        }
        log.info("Download packaging zip from local automation_sdlc");
        bundle = zipPackageService.packageWorkspace(
                new ZipPackageService.PackageRequest(
                        ZipPackageService.workspaceRootName(project.getProjectName(), id),
                        markdown,
                        toRepoFolders(repoNames, repoPurposes, repoDescriptions),
                        overlay,
                        mcpProviders == null ? List.of() : mcpProviders,
                        new McpJsonWriter.SiteHints(mcpJiraUrl, mcpJiraEmail, mcpConfluenceUrl, mcpConfluenceEmail, mcpJiraCloudId)
                )
        );
        log.info(
                "Download zip ready files={} bytes={} elapsedMs={}",
                bundle.fileCount(),
                bundle.zipBytes().length,
                System.currentTimeMillis() - started
        );
        String nextCommand = setup.path("nextCommand").asText(ZipPackageService.NEXT_SDLC_COMMAND);
        if (!nextCommand.isBlank() && !nextCommand.startsWith("/")) {
            nextCommand = "/" + nextCommand;
        }
        ContentDisposition disposition = ContentDisposition.attachment().filename(bundle.filename()).build();
        String folderStatus = s3WorkspaceService.enabled() ? s3WorkspaceService.status(project.getProjectName(), id) : "";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Blink-Workspace-Structure", ZipPackageService.encodeStructure(bundle.structure()))
                .header("X-Blink-File-Count", String.valueOf(bundle.fileCount()))
                .header("X-Blink-Next-Command", nextCommand)
                .header("X-Blink-Setup-Status", setup.path("status").asText(""))
                .header("X-Blink-Identity-Source", setup.path("identitySource").asText(""))
                .header("X-Blink-Overlay-Count", String.valueOf(overlay.size()))
                .header("X-Blink-Setup-Validated", "true")
                .header("X-Blink-Context-Ready", String.valueOf(setup.path("contextReady").asBoolean(false)))
                .header("X-Blink-Delivery-Ready", String.valueOf(setup.path("deliveryReady").asBoolean(false)))
                .header("X-Blink-Folder-Status", folderStatus == null ? "" : folderStatus)
                .contentType(ZIP)
                .contentLength(bundle.zipBytes().length)
                .body(new ByteArrayResource(bundle.zipBytes()));
    }

    private static List<ZipPackageService.RepoFolder> toRepoFolders(
            List<String> names,
            List<String> purposes,
            List<String> descriptions
    ) {
        if (names == null || names.isEmpty()) {
            return List.of();
        }
        List<ZipPackageService.RepoFolder> repos = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            String name = names.get(i);
            if (name == null || name.isBlank()) {
                continue;
            }
            String purpose = purposes != null && i < purposes.size() ? purposes.get(i) : "";
            String description = descriptions != null && i < descriptions.size() ? descriptions.get(i) : "";
            repos.add(new ZipPackageService.RepoFolder(name, purpose, description));
        }
        return repos;
    }

    private static JsonNode parseSetupContext(String setupContext) {
        if (setupContext == null || setupContext.isBlank()) {
            return null;
        }
        try {
            JsonNode parsed = MAPPER.readTree(setupContext);
            if (!parsed.isObject()) {
                throw new IllegalArgumentException("Setup context must be an object.");
            }
            return parsed;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Setup context is invalid.");
        }
    }

    private ProjectResponse attachWorkspace(ProjectResponse project, boolean provision) {
        if (project == null || project.projectName() == null || project.projectName().isBlank()) {
            return project;
        }
        String key = WorkspaceNames.folder(project.projectName(), project.id());
        String url = WorkspaceNames.publicUrl(properties.getS3PublicBaseUrl(), project.projectName(), project.id());
        if (provision && s3WorkspaceService.enabled()) {
            s3WorkspaceService.provisionAsync(project.projectName(), project.id());
        }
        if (!s3WorkspaceService.enabled()) {
            return project.withWorkspace(null, null, null);
        }
        return project.withWorkspace(key, url, s3WorkspaceService.status(project.projectName(), project.id()));
    }
}
