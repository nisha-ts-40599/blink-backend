package com.talentserv.blink.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Finds the local {@code automation_sdlc} checkout for zip/S3 copies.
 * The three Blink repos sit as siblings under Development/, so the default
 * {@code ../automation_sdlc} path from blink-backend is usually wrong.
 */
final class FrameworkKitPaths {

    private FrameworkKitPaths() {
    }

    static Path resolve(String configuredPath) {
        for (Path candidate : candidates(configuredPath)) {
            if (usable(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> candidates(String configuredPath) {
        Set<Path> unique = new LinkedHashSet<>();
        Path cwd = Path.of(System.getProperty("user.dir", ".")).toAbsolutePath().normalize();
        if (configuredPath != null && !configuredPath.isBlank()) {
            Path configured = Path.of(configuredPath);
            if (!configured.isAbsolute()) {
                configured = cwd.resolve(configured);
            }
            unique.add(configured.normalize());
        }
        Path dir = cwd;
        while (dir != null) {
            unique.add(dir.resolve("automation_sdlc").normalize());
            unique.add(dir.resolve("Blink-Framework").resolve("automation_sdlc").normalize());
            dir = dir.getParent();
        }
        return new ArrayList<>(unique);
    }

    private static boolean usable(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        if (Files.isRegularFile(dir.resolve("Makefile"))
                || Files.isRegularFile(dir.resolve("README.md"))
                || Files.isDirectory(dir.resolve("ai-sdlc"))) {
            return true;
        }
        try (var children = Files.list(dir)) {
            return children.findAny().isPresent();
        } catch (IOException ignored) {
            return false;
        }
    }
}
