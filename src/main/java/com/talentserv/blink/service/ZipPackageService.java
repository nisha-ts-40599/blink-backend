package com.talentserv.blink.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;

@Service
public class ZipPackageService {

    public static final String WORKSPACE_ROOT = "MY_PILOT_DEMO";

    static final String BUNDLED_AUTOMATION_SDLC = "/templates/automation_sdlc.zip";

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git",
            ".idea",
            "node_modules",
            "target",
            "dist",
            "__pycache__",
            ".venv",
            ".pytest_cache",
            ".mypy_cache",
            "runtime-data"
    );

    private final BlinkProperties properties;

    public ZipPackageService(BlinkProperties properties) {
        this.properties = properties;
    }

    public void writeWorkspace(String requirementMarkdown, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            zip.putNextEntry(new ZipEntry(WORKSPACE_ROOT + "/"));
            zip.closeEntry();

            Path sdlc = resolveAutomationSdlc();
            Path cursor = sdlc != null ? sdlc.resolve(".cursor") : null;
            if (cursor != null && Files.isDirectory(cursor)) {
                copyOrPlaceholder(zip, cursor, WORKSPACE_ROOT + "/.cursor", missing(".cursor"));
            } else {
                int fromBundle = copyBundledPrefix(zip, ".cursor/", WORKSPACE_ROOT + "/.cursor");
                if (fromBundle == 0) {
                    putText(zip, WORKSPACE_ROOT + "/.cursor/README.md", missing(".cursor"));
                }
            }

            if (sdlc != null) {
                copyOrPlaceholder(zip, sdlc, WORKSPACE_ROOT + "/automation_sdlc", missing("automation_sdlc"));
            } else {
                int fromBundle = copyBundledPrefix(zip, "", WORKSPACE_ROOT + "/automation_sdlc");
                if (fromBundle == 0) {
                    putText(zip, WORKSPACE_ROOT + "/automation_sdlc/README.md", missing("automation_sdlc"));
                }
            }

            copyOrPlaceholder(zip, resolveDir("blink_demo", "blink_ui"), WORKSPACE_ROOT + "/blink_ui", missing("blink_ui"));
            copyOrPlaceholder(zip, resolveBackend(), WORKSPACE_ROOT + "/blink_backend", missing("blink_backend"));
            if (requirementMarkdown != null && !requirementMarkdown.isBlank()) {
                putText(zip, WORKSPACE_ROOT + "/requirement.md", requirementMarkdown);
            }
        }
    }

    private static String missing(String folder) {
        return "# " + folder + "\n\nBlink could not find this folder to copy into the download.\n";
    }

    private void copyOrPlaceholder(ZipOutputStream zip, Path source, String zipPrefix, String placeholder)
            throws IOException {
        if (source != null && Files.isDirectory(source)) {
            int copied = copyTree(zip, source, zipPrefix);
            if (copied > 0) {
                return;
            }
        }
        putText(zip, zipPrefix + "/README.md", placeholder);
    }

    private int copyTree(ZipOutputStream zip, Path root, String zipPrefix) throws IOException {
        AtomicInteger copied = new AtomicInteger();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                if (SKIP_DIR_NAMES.contains(dir.getFileName().toString())) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if (skipFile(file.getFileName().toString())) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry(zipPrefix + "/" + relative));
                Files.copy(file, zip);
                zip.closeEntry();
                copied.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
        return copied.get();
    }

    int copyBundledPrefix(ZipOutputStream zip, String entryPrefix, String zipPrefix) throws IOException {
        try (InputStream in = ZipPackageService.class.getResourceAsStream(BUNDLED_AUTOMATION_SDLC)) {
            if (in == null) {
                return 0;
            }
            int copied = 0;
            try (ZipInputStream zin = new ZipInputStream(in)) {
                for (ZipEntry entry = zin.getNextEntry(); entry != null; entry = zin.getNextEntry()) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName().replace('\\', '/');
                    if (name.startsWith("./")) {
                        name = name.substring(2);
                    }
                    if (!entryPrefix.isEmpty()) {
                        if (!name.startsWith(entryPrefix)) {
                            continue;
                        }
                        name = name.substring(entryPrefix.length());
                    }
                    if (name.isBlank() || skipBundledPath(name)) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(zipPrefix + "/" + name));
                    zin.transferTo(zip);
                    zip.closeEntry();
                    copied++;
                }
            }
            return copied;
        }
    }

    private static boolean skipBundledPath(String relative) {
        for (String part : relative.split("/")) {
            if (SKIP_DIR_NAMES.contains(part)) {
                return true;
            }
        }
        return skipFile(Path.of(relative).getFileName().toString());
    }

    Path resolveAutomationSdlc() {
        Path configured = Path.of(properties.getAutomationSdlcPath());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        configured = configured.normalize();
        if (isUsableSdlc(configured)) {
            return configured;
        }
        Path walked = walkForDirectory("automation_sdlc");
        if (isUsableSdlc(walked)) {
            return walked;
        }
        return null;
    }

    Path resolveBackend() {
        Path cwd = Path.of(System.getProperty("user.dir")).normalize();
        if (Files.isRegularFile(cwd.resolve("pom.xml")) && Files.isDirectory(cwd.resolve("src"))) {
            return cwd;
        }
        return firstExistingDirectory(List.of("blink-backend", "blink_backend"));
    }

    Path resolveDir(String... names) {
        return firstExistingDirectory(List.of(names));
    }

    private static boolean isUsableSdlc(Path dir) {
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

    private static Path walkForDirectory(String name) {
        Path dir = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        while (dir != null) {
            Path candidate = dir.resolve(name);
            if (Files.isDirectory(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static Path firstExistingDirectory(List<String> names) {
        Path cwd = Path.of(System.getProperty("user.dir")).normalize();
        Path parent = cwd.getParent();
        for (String name : names) {
            Path here = cwd.resolve(name).normalize();
            if (Files.isDirectory(here)) {
                return here;
            }
            if (parent != null) {
                Path sibling = parent.resolve(name).normalize();
                if (Files.isDirectory(sibling)) {
                    return sibling;
                }
            }
            Path walked = walkForDirectory(name);
            if (walked != null) {
                return walked;
            }
        }
        return null;
    }

    private static boolean skipFile(String name) {
        if (name.equals(".DS_Store") || name.endsWith(".pyc") || name.endsWith(".log")) {
            return true;
        }
        if (name.equals(".env") || (name.startsWith(".env.") && !name.contains("example"))) {
            return true;
        }
        return false;
    }

    static void putText(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
