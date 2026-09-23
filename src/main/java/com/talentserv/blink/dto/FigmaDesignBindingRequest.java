package com.talentserv.blink.dto;

import java.util.List;

public record FigmaDesignBindingRequest(
        String projectId,
        String fileKey,
        String fileUrl,
        String fileName,
        String figmaProjectId,
        Boolean syncJira,
        List<FigmaScreenBinding> screens,
        List<FigmaStoryRef> stories,
        List<FigmaJiraRef> jiraIssues,
        String webhookPublicBase
) {
}
