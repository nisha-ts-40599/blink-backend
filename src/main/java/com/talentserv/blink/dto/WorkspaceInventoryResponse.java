package com.talentserv.blink.dto;

import java.util.List;

public record WorkspaceInventoryResponse(
        String bucket,
        String workspaceKey,
        String workspaceUrl,
        String status,
        boolean exists,
        int objectCount,
        long totalBytes,
        List<TopLevel> topLevel,
        List<String> sampleFiles
) {
    public record TopLevel(String name, int files, long bytes) {
    }
}
