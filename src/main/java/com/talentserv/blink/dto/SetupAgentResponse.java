package com.talentserv.blink.dto;

import java.util.List;

public record SetupAgentResponse(
        String runId,
        String command,
        String status,
        Boolean contextReady,
        Boolean deliveryReady,
        Boolean gitWritten,
        String identitySource,
        List<OverlayFile> overlayFiles,
        String nextCommand,
        String message,
        List<String> errors
) {
    public record OverlayFile(String path, String content) {
    }
}
