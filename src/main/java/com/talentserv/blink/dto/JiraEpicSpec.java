package com.talentserv.blink.dto;

import java.util.List;

public record JiraEpicSpec(
        String id,
        String title,
        String objective,
        List<String> storyIds
) {
    public JiraEpicSpec(String id, String title, String objective) {
        this(id, title, objective, List.of());
    }
}
