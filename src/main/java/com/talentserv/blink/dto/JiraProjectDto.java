package com.talentserv.blink.dto;

public record JiraProjectDto(
        String id,
        String key,
        String name,
        String projectTypeKey,
        String avatarUrl
) {
    public JiraProjectDto(String id, String key, String name) {
        this(id, key, name, null, null);
    }
}
