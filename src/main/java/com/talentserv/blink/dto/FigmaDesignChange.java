package com.talentserv.blink.dto;

public record FigmaDesignChange(
        String kind,
        String nodeId,
        String name,
        String previousName,
        String storyId,
        String jiraKey,
        String detail
) {
}
