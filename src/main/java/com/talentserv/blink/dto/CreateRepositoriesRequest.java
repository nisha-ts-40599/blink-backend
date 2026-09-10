package com.talentserv.blink.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateRepositoriesRequest(
        @NotBlank(message = "Provider is required") String provider,
        String projectId,
        String token,
        String username,
        String organization,
        String workspace,
        @NotEmpty(message = "At least one repository is required")
        @Valid List<RepoSpec> repositories
) {
    public record RepoSpec(
            @NotBlank(message = "Repository name is required") String name,
            String description
    ) {
    }
}
