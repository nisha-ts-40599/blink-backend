package com.talentserv.blink.service;

import java.util.List;

import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;

public interface ProjectService {

    ProjectResponse create(ProjectRequest request);

    ProjectResponse update(Long projectId, ProjectRequest request);

    List<ProjectResponse> list();

    ProjectResponse get(Long projectId);

    Project requireProject(Long projectId);
}
