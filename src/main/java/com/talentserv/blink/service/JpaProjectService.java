package com.talentserv.blink.service;

import java.util.List;
import java.util.Locale;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.talentserv.blink.domain.AppUser;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.domain.Stakeholder;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.dto.StakeholderResponse;
import com.talentserv.blink.error.ApiException;
import com.talentserv.blink.repo.ProjectRepository;
import com.talentserv.blink.repo.StakeholderRepository;

@Service
@Profile("!nodb")
public class JpaProjectService implements ProjectService {

    private final ProjectRepository projectRepository;
    private final StakeholderRepository stakeholderRepository;
    private final DemoUserService demoUserService;
    private final RoleCatalog roleCatalog;

    public JpaProjectService(
            ProjectRepository projectRepository,
            StakeholderRepository stakeholderRepository,
            DemoUserService demoUserService,
            RoleCatalog roleCatalog
    ) {
        this.projectRepository = projectRepository;
        this.stakeholderRepository = stakeholderRepository;
        this.demoUserService = demoUserService;
        this.roleCatalog = roleCatalog;
    }

    @Override
    @Transactional
    public ProjectResponse create(ProjectRequest request) {
        AppUser actor = demoUserService.requireDemoUser();
        Project project = new Project();
        applyProjectFields(project, request);
        project.setCreatedBy(actor.getId());
        project.setUpdatedBy(actor.getId());
        project = projectRepository.save(project);
        replaceStakeholders(project.getId(), request.stakeholders(), actor.getId());
        return toResponse(project);
    }

    @Override
    @Transactional
    public ProjectResponse update(Long projectId, ProjectRequest request) {
        AppUser actor = demoUserService.requireDemoUser();
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Project not found."));
        applyProjectFields(project, request);
        project.setUpdatedBy(actor.getId());
        project = projectRepository.save(project);
        replaceStakeholders(project.getId(), request.stakeholders(), actor.getId());
        return toResponse(project);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        return projectRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public ProjectResponse get(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Project not found."));
        return toResponse(project);
    }

    @Override
    @Transactional(readOnly = true)
    public Project requireProject(Long projectId) {
        return projectRepository.findById(projectId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Project not found."));
    }

    private void applyProjectFields(Project project, ProjectRequest request) {
        project.setProjectName(request.projectName().trim());
        project.setDescription(blankToNull(request.description()));
        project.setProjectType(toStorageType(request.projectType()));
    }

    private void replaceStakeholders(Long projectId, List<StakeholderRequest> rows, java.util.UUID actorId) {
        stakeholderRepository.deleteByProjectId(projectId);
        stakeholderRepository.flush();
        for (StakeholderRequest row : rows) {
            String roleCode = row.roleCode().trim().toLowerCase(Locale.ROOT);
            String personName = row.name().trim();
            Stakeholder stakeholder = new Stakeholder();
            stakeholder.setProjectId(projectId);
            stakeholder.setRoleCode(roleCode);
            stakeholder.setPersonName(personName);
            stakeholder.setPersonEmail(row.email().trim().toLowerCase(Locale.ROOT));
            stakeholder.setCode("blink_" + java.util.UUID.randomUUID().toString().replace("-", ""));
            stakeholder.setName(personName);
            stakeholder.setCategory(stakeholderRepository.findAnyCategory().orElse("BUSINESS"));
            stakeholder.setActive(true);
            stakeholder.setCreatedBy(actorId);
            stakeholder.setUpdatedBy(actorId);
            stakeholderRepository.save(stakeholder);
        }
    }

    private ProjectResponse toResponse(Project project) {
        List<StakeholderResponse> stakeholders = stakeholderRepository
                .findByProjectIdOrderByPersonNameAsc(project.getId())
                .stream()
                .map(row -> new StakeholderResponse(
                        row.getId(),
                        row.getRoleCode(),
                        roleCatalog.nameFor(row.getRoleCode()),
                        row.getPersonName(),
                        row.getPersonEmail()))
                .toList();
        return new ProjectResponse(
                project.getId(),
                project.getProjectName(),
                ProjectCodes.slug(project.getProjectName()),
                project.getDescription(),
                null,
                toApiType(project.getProjectType()),
                stakeholders);
    }

    private static String toStorageType(String projectType) {
        String value = projectType.trim().toLowerCase(Locale.ROOT);
        if (value.equals("new")) {
            return "NEW";
        }
        if (value.equals("existing")) {
            return "EXISTING";
        }
        throw new ApiException(HttpStatus.BAD_REQUEST, "Project type must be new or existing.");
    }

    private static String toApiType(String stored) {
        if (stored == null) {
            return null;
        }
        return stored.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
