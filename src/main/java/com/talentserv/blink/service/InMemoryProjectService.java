package com.talentserv.blink.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.DotEnvEnvironmentPostProcessor;
import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderRequest;
import com.talentserv.blink.dto.StakeholderResponse;
import com.talentserv.blink.error.ApiException;

import jakarta.annotation.PostConstruct;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

@Service
@Profile("nodb")
public class InMemoryProjectService implements ProjectService {

    private static final Logger log = LoggerFactory.getLogger(InMemoryProjectService.class);
    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();

    private final RoleCatalog roleCatalog;
    private final Path store;
    private final AtomicLong projectIds = new AtomicLong(1);
    private final AtomicLong stakeholderIds = new AtomicLong(1);
    private final ConcurrentHashMap<Long, StoredProject> projects = new ConcurrentHashMap<>();

    public InMemoryProjectService(RoleCatalog roleCatalog) {
        this.roleCatalog = roleCatalog;
        Path envFile = DotEnvEnvironmentPostProcessor.resolveEnvFile();
        this.store = envFile != null
                ? envFile.getParent().resolve(".blink-nodb.json")
                : Path.of(System.getProperty("user.dir", ".")).resolve(".blink-nodb.json");
    }

    @PostConstruct
    void load() {
        if (!Files.isRegularFile(store)) {
            return;
        }
        try {
            Snapshot snapshot = MAPPER.readValue(store.toFile(), Snapshot.class);
            if (snapshot == null || snapshot.projects == null) {
                return;
            }
            long maxProject = 0;
            long maxStakeholder = 0;
            for (SnapshotProject row : snapshot.projects) {
                if (row == null || row.id == null) {
                    continue;
                }
                StoredProject stored = fromSnapshot(row);
                projects.put(row.id, stored);
                maxProject = Math.max(maxProject, row.id);
                for (StakeholderResponse stakeholder : stored.stakeholders()) {
                    if (stakeholder.id() != null) {
                        maxStakeholder = Math.max(maxStakeholder, stakeholder.id());
                    }
                }
            }
            projectIds.set(Math.max(snapshot.nextProjectId, maxProject + 1));
            stakeholderIds.set(Math.max(snapshot.nextStakeholderId, maxStakeholder + 1));
            log.info("Loaded {} local project(s) from {}", projects.size(), store.toAbsolutePath());
        } catch (Exception ex) {
            log.warn("Could not load local project store {}: {}", store, ex.toString());
        }
    }

    @Override
    public ProjectResponse create(ProjectRequest request) {
        long id = projectIds.getAndIncrement();
        StoredProject stored = store(id, request);
        projects.put(id, stored);
        persist();
        return toResponse(stored);
    }

    @Override
    public ProjectResponse update(Long projectId, ProjectRequest request) {
        if (!projects.containsKey(projectId)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Project not found.");
        }
        StoredProject stored = store(projectId, request);
        projects.put(projectId, stored);
        persist();
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
                stored.stakeholders(),
                null,
                null,
                null);
    }

    private synchronized void persist() {
        Snapshot snapshot = new Snapshot(
                projectIds.get(),
                stakeholderIds.get(),
                projects.values().stream().map(this::toSnapshot).toList()
        );
        try {
            MAPPER.writeValue(store.toFile(), snapshot);
        } catch (Exception ex) {
            log.warn("Could not save local project store {}: {}", store, ex.toString());
        }
    }

    private SnapshotProject toSnapshot(StoredProject stored) {
        Project project = stored.project();
        return new SnapshotProject(
                project.getId(),
                project.getProjectName(),
                project.getDescription(),
                project.getProjectType(),
                stored.stakeholders()
        );
    }

    private StoredProject fromSnapshot(SnapshotProject row) {
        Project project = new Project();
        project.setId(row.id);
        project.setProjectName(row.projectName);
        project.setDescription(row.description);
        project.setProjectType(row.projectType);
        List<StakeholderResponse> stakeholders = row.stakeholders == null ? List.of() : List.copyOf(row.stakeholders);
        return new StoredProject(project, stakeholders);
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

    private record Snapshot(long nextProjectId, long nextStakeholderId, List<SnapshotProject> projects) {
    }

    private record SnapshotProject(
            Long id,
            String projectName,
            String description,
            String projectType,
            List<StakeholderResponse> stakeholders
    ) {
    }
}
