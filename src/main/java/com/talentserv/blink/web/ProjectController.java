package com.talentserv.blink.web;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

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
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.SetupAgentRequest;
import com.talentserv.blink.dto.SetupAgentResponse;
import com.talentserv.blink.service.ProjectService;
import com.talentserv.blink.service.RequirementMarkdownService;
import com.talentserv.blink.service.SetupAgentService;
import com.talentserv.blink.service.ZipPackageService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final ProjectService projectService;
    private final RequirementMarkdownService requirementMarkdownService;
    private final ZipPackageService zipPackageService;
    private final SetupAgentService setupAgentService;

    public ProjectController(
            ProjectService projectService,
            RequirementMarkdownService requirementMarkdownService,
            ZipPackageService zipPackageService,
            SetupAgentService setupAgentService
    ) {
        this.projectService = projectService;
        this.requirementMarkdownService = requirementMarkdownService;
        this.zipPackageService = zipPackageService;
        this.setupAgentService = setupAgentService;
    }

    @GetMapping
    public List<ProjectResponse> list() {
        return projectService.list();
    }

    @PostMapping
    public ProjectResponse create(@Valid @RequestBody ProjectRequest request) {
        return projectService.create(request);
    }

    @PutMapping("/{id}")
    public ProjectResponse update(@PathVariable Long id, @Valid @RequestBody ProjectRequest request) {
        return projectService.update(id, request);
    }

    @GetMapping("/{id}")
    public ProjectResponse get(@PathVariable Long id) {
        return projectService.get(id);
    }

    @PostMapping("/{id}/setup")
    public SetupAgentResponse setup(@PathVariable Long id, @RequestBody(required = false) SetupAgentRequest request) {
        return setupAgentService.start(id, request);
    }

    @PostMapping(path = "/{id}/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "requirementsText", required = false) String requirementsText,
            @RequestParam(value = "repoName", required = false) List<String> repoNames,
            @RequestParam(value = "repoPurpose", required = false) List<String> repoPurposes,
            @RequestParam(value = "repoDescription", required = false) List<String> repoDescriptions
    ) throws IOException {
        Project project = projectService.requireProject(id);
        String markdown = requirementMarkdownService.toMarkdown(project.getProjectName(), file, requirementsText);
        var setup = setupAgentService.applyBestEffort(project, markdown);
        var overlay = SetupAgentService.overlayFiles(setup);
        ZipPackageService.WorkspaceBundle bundle = zipPackageService.packageWorkspace(
                new ZipPackageService.PackageRequest(
                        ZipPackageService.workspaceRootName(project.getProjectName()),
                        markdown,
                        toRepoFolders(repoNames, repoPurposes, repoDescriptions),
                        overlay
                )
        );
        String nextCommand = setup.path("nextCommand").asText(ZipPackageService.NEXT_SDLC_COMMAND);
        if (!nextCommand.isBlank() && !nextCommand.startsWith("/")) {
            nextCommand = "/" + nextCommand;
        }
        ContentDisposition disposition = ContentDisposition.attachment().filename(bundle.filename()).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header("X-Blink-Workspace-Structure", ZipPackageService.encodeStructure(bundle.structure()))
                .header("X-Blink-File-Count", String.valueOf(bundle.fileCount()))
                .header("X-Blink-Next-Command", nextCommand)
                .header("X-Blink-Setup-Status", setup.path("status").asText(""))
                .header("X-Blink-Identity-Source", setup.path("identitySource").asText(""))
                .header("X-Blink-Overlay-Count", String.valueOf(overlay.size()))
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
}
