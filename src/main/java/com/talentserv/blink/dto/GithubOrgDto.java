package com.talentserv.blink.dto;

public record GithubOrgDto(
        String login,
        String name,
        String avatarUrl,
        boolean personal
) {
    public GithubOrgDto(String login, String name) {
        this(login, name, null, false);
    }
}
