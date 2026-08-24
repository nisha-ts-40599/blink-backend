package com.talentserv.blink.web;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
import com.talentserv.blink.service.ProjectCodes;
import com.talentserv.blink.service.ProjectService;
import com.talentserv.blink.service.RequirementMarkdownService;
import com.talentserv.blink.service.ZipPackageService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api/projects")
public class ProjectController {

    private static final MediaType ZIP = MediaType.parseMediaType("application/zip");

    private final ProjectService projectService;
    private final RequirementMarkdownService requirementMarkdownService;
    private final ZipPackageService zipPackageService;

    public ProjectController(
            ProjectService projectService,
            RequirementMarkdownService requirementMarkdownService,
            ZipPackageService zipPackageService
    ) {
        this.projectService = projectService;
        this.requirementMarkdownService = requirementMarkdownService;
        this.zipPackageService = zipPackageService;
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

    @PostMapping(path = "/{id}/download", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> download(
            @PathVariable Long id,
            @RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(value = "requirementsText", required = false) String requirementsText
    ) throws IOException {
        Project project = projectService.requireProject(id);
        String markdown = requirementMarkdownService.toMarkdown(project.getProjectName(), file, requirementsText);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        zipPackageService.writeWorkspace(project, markdown, buffer);
        byte[] bytes = buffer.toByteArray();
        String filename = ProjectCodes.artifact(project.getProjectName()) + "-workspace.zip";
        ContentDisposition disposition = ContentDisposition.attachment().filename(filename).build();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(ZIP)
                .contentLength(bytes.length)
                .body(new ByteArrayResource(bytes));
    }
}
