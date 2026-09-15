package com.talentserv.blink.dto;

import java.util.List;

public record S3WorkspaceDeleteResponse(
        int deletedFolders,
        long deletedObjects,
        List<String> folders
) {
}
