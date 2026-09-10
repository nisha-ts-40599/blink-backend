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
        String workspaceStatus,
        List<String> sodWarnings,
        String nextCommand,
        String governanceStatus
) {
    public ProjectResponse(
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
        this(
                id,
                projectName,
                projectCode,
                description,
                status,
                projectType,
                stakeholders,
                workspaceKey,
                workspaceUrl,
                workspaceStatus,
                List.of(),
                "/plan-product-scope",
                "idle"
        );
    }

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
                workspaceStatus,
                sodWarnings,
                nextCommand,
                governanceStatus
        );
    }

    public ProjectResponse withGovernance(List<String> sodWarnings, String nextCommand) {
        return withGovernance(sodWarnings, nextCommand, "ready");
    }

    public ProjectResponse withGovernance(List<String> sodWarnings, String nextCommand, String governanceStatus) {
        return new ProjectResponse(
                id,
                projectName,
                projectCode,
                description,
                status,
                projectType,
                stakeholders,
                workspaceKey,
                workspaceUrl,
                workspaceStatus,
                sodWarnings == null ? List.of() : List.copyOf(sodWarnings),
                nextCommand == null || nextCommand.isBlank() ? "/plan-product-scope" : nextCommand,
                governanceStatus == null || governanceStatus.isBlank() ? "idle" : governanceStatus
        );
    }
}
