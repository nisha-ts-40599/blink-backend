package com.talentserv.blink.dto;

import java.util.List;

public record FigmaIngestRequest(
        String projectId,
        String fileKey,
        String fileUrl,
        Boolean syncJira,
        List<FigmaStoryRef> stories,
        List<FigmaJiraRef> jiraIssues,
        String webhookPublicBase
) {
}
