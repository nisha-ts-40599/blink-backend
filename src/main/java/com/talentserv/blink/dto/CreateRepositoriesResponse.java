package com.talentserv.blink.dto;

import java.util.List;

public record CreateRepositoriesResponse(
        String provider,
        List<RepoResult> repositories
) {
    public record RepoResult(
            String name,
            String status,
            String htmlUrl,
            String message
    ) {
    }
}
