package com.talentserv.blink.service;

import java.util.Locale;
import java.util.function.Predicate;

public final class ProjectCodes {

    private ProjectCodes() {
    }

    public static String fromName(String projectName, Predicate<String> exists) {
        String base = slug(projectName);
        if (base.isBlank()) {
            base = "PROJECT";
        }
        if (base.length() > 90) {
            base = base.substring(0, 90);
        }
        String candidate = base;
        int suffix = 2;
        while (exists.test(candidate)) {
            String extra = "-" + suffix++;
            int maxBase = Math.max(1, 100 - extra.length());
            candidate = (base.length() > maxBase ? base.substring(0, maxBase) : base) + extra;
        }
        return candidate;
    }

    public static String slug(String projectName) {
        if (projectName == null) {
            return "";
        }
        return projectName.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }

    public static String artifact(String projectName) {
        String slug = slug(projectName).toLowerCase(Locale.ROOT);
        return slug.isBlank() ? "blink-app" : slug;
    }

    public static String packageName(String artifact) {
        String compact = artifact.replace("-", "").replaceAll("[^a-zA-Z0-9]", "");
        if (compact.isBlank() || !Character.isLetter(compact.charAt(0))) {
            compact = "app" + compact;
        }
        return "com." + compact.toLowerCase(Locale.ROOT) + ".backend";
    }
}
