package com.talentserv.blink.dto;

import java.util.List;

public record S3WorkspaceListResponse(
        boolean enabled,
        String bucket,
        List<S3WorkspaceProjectResponse> workspaces
) {
}
