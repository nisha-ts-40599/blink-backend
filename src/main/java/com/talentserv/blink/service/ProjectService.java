package com.talentserv.blink.service;

import java.util.List;

import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;

public interface ProjectService {

    default ProjectResponse create(ProjectRequest request) {
        return create(request, null);
    }

    ProjectResponse create(ProjectRequest request, String ownerEmail);

    default ProjectResponse update(Long projectId, ProjectRequest request) {
        return update(projectId, request, null);
    }

    ProjectResponse update(Long projectId, ProjectRequest request, String ownerEmail);

    List<ProjectResponse> list();

    ProjectResponse get(Long projectId);

    Project requireProject(Long projectId);

    ProjectResponse latestForOwner(String ownerEmail);
}
