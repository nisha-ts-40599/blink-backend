package com.talentserv.blink.dto;

public record StakeholderRoleResponse(
        String roleCode,
        String roleName,
        String description,
        boolean required,
        Integer displayOrder,
        String defaultName,
        String defaultEmail
) {
}
