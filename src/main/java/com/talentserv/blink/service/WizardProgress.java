package com.talentserv.blink.service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

import com.talentserv.blink.domain.Project;
import com.talentserv.blink.dto.ProjectRequest;
import com.talentserv.blink.dto.ProjectResponse;
import com.talentserv.blink.dto.StakeholderResponse;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

final class WizardProgress {

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private WizardProgress() {
    }

    static void applyOwner(Project project, String ownerEmail) {
        if (ownerEmail == null || ownerEmail.isBlank()) {
            return;
        }
        project.setOwnerEmail(ownerEmail.trim().toLowerCase(Locale.ROOT));
    }

    static void apply(Project project, ProjectRequest request) {
        if (request == null) {
            return;
        }
        if (request.wizardStep() != null && !request.wizardStep().isBlank()) {
            project.setWizardStep(request.wizardStep().trim());
        }
        if (request.wizardCompletedThrough() != null) {
            project.setWizardCompletedThrough(Math.max(0, request.wizardCompletedThrough()));
        }
        if (request.wizardState() != null && !request.wizardState().isNull() && !request.wizardState().isMissingNode()) {
            try {
                project.setWizardStateJson(MAPPER.writeValueAsString(request.wizardState()));
            } catch (Exception ex) {
                project.setWizardStateJson(request.wizardState().toString());
            }
        }
    }

    static JsonNode parseState(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(json);
        } catch (Exception ex) {
            return null;
        }
    }

    static String updatedAt(Project project) {
        LocalDateTime value = project.getUpdatedAt() != null ? project.getUpdatedAt() : project.getCreatedAt();
        return value == null ? null : value.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
    }

    static ProjectResponse toResponse(
            Project project,
            String projectCode,
            String projectType,
            java.util.List<StakeholderResponse> stakeholders
    ) {
        return new ProjectResponse(
                project.getId(),
                project.getProjectName(),
                projectCode,
                project.getDescription(),
                null,
                projectType,
                stakeholders,
                null,
                null,
                null,
                java.util.List.of(),
                "/plan-product-scope",
                "idle",
                project.getOwnerEmail(),
                project.getWizardStep(),
                project.getWizardCompletedThrough(),
                parseState(project.getWizardStateJson()),
                updatedAt(project)
        );
    }
}
