package com.talentserv.blink.dto;

public record StakeholderResponse(
        Long id,
        String roleCode,
        String roleName,
        String name,
        String email
) {
}
