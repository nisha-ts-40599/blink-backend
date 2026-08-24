package com.talentserv.blink.dto;

import java.util.List;

public record ProjectResponse(
        Long id,
        String projectName,
        String projectCode,
        String description,
        String status,
        String projectType,
        List<StakeholderResponse> stakeholders
) {
}
