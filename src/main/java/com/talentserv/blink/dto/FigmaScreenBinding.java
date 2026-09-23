package com.talentserv.blink.dto;

public record FigmaScreenBinding(
        String nodeId,
        String name,
        String pageId,
        String pageName,
        String type,
        String storyId,
        String jiraKey,
        String fingerprint,
        String thumbnailUrl
) {
    public FigmaScreenBinding withBinding(String storyId, String jiraKey) {
        return new FigmaScreenBinding(nodeId, name, pageId, pageName, type, storyId, jiraKey, fingerprint, thumbnailUrl);
    }

    public FigmaScreenBinding withFingerprint(String fingerprint) {
        return new FigmaScreenBinding(nodeId, name, pageId, pageName, type, storyId, jiraKey, fingerprint, thumbnailUrl);
    }

    public FigmaScreenBinding withThumbnail(String thumbnailUrl) {
        return new FigmaScreenBinding(nodeId, name, pageId, pageName, type, storyId, jiraKey, fingerprint, thumbnailUrl);
    }
}
