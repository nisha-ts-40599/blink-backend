package com.talentserv.blink.dto;

public record WorkspaceStatusResponse(
        String workspaceKey,
        String status,
        int filesCopied,
        int filesTotal,
        int percent,
        boolean exists
) {
}
