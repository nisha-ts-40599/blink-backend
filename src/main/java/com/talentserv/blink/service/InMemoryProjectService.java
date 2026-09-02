package com.talentserv.blink.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.dto.StakeholderResponse;
import com.talentserv.blink.error.ApiException;

@Service
@Profile("nodb")
public class InMemoryProjectService implements ProjectService {

    private final RoleCatalog roleCatalog;
    private final AtomicLong projectIds = new AtomicLong(1);
    private final AtomicLong stakeholderIds = new AtomicLong(1);
    private final ConcurrentHashMap<Long, StoredProject> projects = new ConcurrentHashMap<>();

    public InMemoryProjectService(RoleCatalog roleCatalog) {
        this.roleCatalog = roleCatalog;
    }

    @Override
    public ProjectResponse create(ProjectRequest request) {
        long id = projectIds.getAndIncrement();
        StoredProject stored = store(id, request);
        projects.put(id, stored);
        return toResponse(stored);
    }

    @Override
    public ProjectResponse update(Long projectId, ProjectRequest request) {
        if (!projects.containsKey(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found.");
        }
        StoredProject stored = store(projectId, request);
        projects.put(projectId, stored);
        return toResponse(stored);
    }

    @Override
    public List<ProjectResponse> list() {
        return projects.values().stream().map(this::toResponse).toList();
    }

    @Override
    public ProjectResponse get(Long projectId) {
        return toResponse(requireStored(projectId));
    }

    @Override
    public Project requireProject(Long projectId) {
        return requireStored(projectId).project();
    }

    private StoredProject requireStored(Long projectId) {
        StoredProject stored = projects.get(projectId);
        if (stored == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found.");
        }
        return stored;
    }

    private StoredProject store(Long id, ProjectRequest request) {
        Project project = new Project();
        project.setId(id);
        project.setProjectName(request.projectName().trim());
        project.setDescription(blankToNull(request.description()));
        project.setProjectType(toStorageType(request.projectType()));
        List<StakeholderResponse> stakeholders = new ArrayList<>();
        for (StakeholderRequest row : request.stakeholders()) {
            String roleCode = row.roleCode().trim().toLowerCase(Locale.ROOT);
            stakeholders.add(new StakeholderResponse(
                    stakeholderIds.getAndIncrement(),
                    roleCode,
                    roleCatalog.nameFor(roleCode),
                    row.name().trim(),
                    row.email().trim().toLowerCase(Locale.ROOT)));
        }
        return new StoredProject(project, List.copyOf(stakeholders));
    }

    private ProjectResponse toResponse(StoredProject stored) {
        Project project = stored.project();
        return new ProjectResponse(
                project.getId(),
                project.getProjectName(),
                ProjectCodes.slug(project.getProjectName()),
                project.getDescription(),
                null,
                toApiType(project.getProjectType()),
                stored.stakeholders());
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

    private record StoredProject(Project project, List<StakeholderResponse> stakeholders) {
    }
}
