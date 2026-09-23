package com.talentserv.blink.dto;

import java.util.List;

public record JiraIssueStatusesResponse(
        List<JiraIssueStatusItem> issues
) {
}
