package com.talentserv.blink.service;

import java.util.Locale;

public final class WorkspaceNames {

    private WorkspaceNames() {
    }

    public static String folder(String projectName) {
        return folder(projectName, null);
    }

    public static String folder(String projectName, Long projectId) {
        String slug = slug(projectName);
        if (slug.isBlank()) {
            slug = "project";
        }
        if (slug.endsWith("_workspace")) {
            slug = slug.substring(0, slug.length() - "_workspace".length()).replaceAll("_+$", "");
            if (slug.isBlank()) {
                slug = "project";
            }
        }
        if (projectId != null && projectId > 0) {
            return slug + "_" + projectId + "_workspace";
        }
        return slug + "_workspace";
    }

    public static String key(String projectName, String relative) {
        return key(projectName, null, relative);
    }

    public static String key(String projectName, Long projectId, String relative) {
        String folder = folder(projectName, projectId);
        if (relative == null || relative.isBlank()) {
            return folder + "/";
        }
        String path = relative.replace('\\', '/').replaceFirst("^/+", "");
        if (path.contains("..")) {
            throw new IllegalArgumentException("Invalid workspace path.");
        }
        return folder + "/" + path;
    }

    public static String publicUrl(String baseUrl, String projectName) {
        return publicUrl(baseUrl, projectName, null);
    }

    public static String publicUrl(String baseUrl, String projectName, Long projectId) {
        String folder = folder(projectName, projectId);
        if (baseUrl == null || baseUrl.isBlank()) {
            return folder;
        }
        String base = baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
        return base + folder + "/";
    }

    static String slug(String projectName) {
        if (projectName == null) {
            return "";
        }
        String slug = projectName.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("_+$", "");
        }
        return slug;
    }
}
