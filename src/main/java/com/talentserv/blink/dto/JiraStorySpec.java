package com.talentserv.blink.dto;

import java.util.List;

public record JiraStorySpec(
        String id,
        String epicId,
        String title,
        String objective,
        String asA,
        String iWant,
        String soThat,
        List<String> acceptanceCriteria
) {
}
