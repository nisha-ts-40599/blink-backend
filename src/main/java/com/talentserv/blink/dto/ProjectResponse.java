package com.talentserv.blink.dto;

import java.util.List;

public record ProjectResponse(
        Long id,
        String projectName,
        String projectCode,
        String description,
        String status,
        String projectType,
        List<StakeholderResponse> stakeholders,
        String workspaceKey,
        String workspaceUrl,
        String workspaceStatus
) {
    public ProjectResponse withWorkspace(String key, String url, String workspaceStatus) {
        return new ProjectResponse(
                id,
                projectName,
                projectCode,
                description,
                status,
                projectType,
                stakeholders,
                key,
                url,
                workspaceStatus
        );
    }
}
