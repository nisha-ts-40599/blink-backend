package com.talentserv.blink.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;

@Service
public class ZipPackageService {

    public static final String WORKSPACE_ROOT = "MY_PILOT_DEMO";
    public static final String DEFAULT_ARCHIVE_NAME = WORKSPACE_ROOT + ".zip";
    public static final String NEXT_SDLC_COMMAND = "/setup-new-workspace";

    static final String BUNDLED_AUTOMATION_SDLC = "/templates/automation_sdlc.zip";
    private static final Set<String> RESERVED_TOP_LEVEL = Set.of(
            "requirement.md",
            ".cursor",
            "automation_sdlc",
            "blink_demo",
            "blink_backend",
            "blink-backend"
    );

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git",
            ".idea",
            "node_modules",
            "target",
            "dist",
            "__pycache__",
            ".venv",
            ".venv-ai-sdlc",
            ".pytest_cache",
            ".mypy_cache",
            "runtime-data",
            "blink_demo",
            "blink_backend",
            "blink-backend"
    );

    private final BlinkProperties properties;

    public ZipPackageService(BlinkProperties properties) {
        this.properties = properties;
    }

    public record RepoFolder(String name, String purpose, String description) {
    }

    public record PackageRequest(String workspaceRoot, String requirementMarkdown, List<RepoFolder> repositories) {
        public PackageRequest {
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
        }
    }

    public record WorkspaceEntry(String name, String kind) {
    }

    public record WorkspaceBundle(
            byte[] zipBytes,
            String filename,
            List<WorkspaceEntry> structure,
            int fileCount
    ) {
    }

    public void writeWorkspace(String requirementMarkdown, OutputStream output) throws IOException {
        WorkspaceBundle bundle = packageWorkspace(requirementMarkdown);
        output.write(bundle.zipBytes());
    }

    public WorkspaceBundle packageWorkspace(String requirementMarkdown) throws IOException {
        return packageWorkspace(new PackageRequest(WORKSPACE_ROOT, requirementMarkdown, List.of()));
    }

    public WorkspaceBundle packageWorkspace(PackageRequest request) throws IOException {
        String root = workspaceRootName(request.workspaceRoot());
        List<WorkspaceEntry> structure = new ArrayList<>();
        AtomicInteger files = new AtomicInteger();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(buffer)) {
            zip.putNextEntry(new ZipEntry(root + "/"));
            zip.closeEntry();

            String markdown = request.requirementMarkdown() == null || request.requirementMarkdown().isBlank()
                    ? "# requirement\n"
                    : request.requirementMarkdown();
            putText(zip, zipPath(root, "requirement.md"), markdown);
            files.incrementAndGet();
            structure.add(new WorkspaceEntry("requirement.md", "file"));

            Path sdlc = resolveAutomationSdlc();
            if (sdlc != null) {
                copySharedFolder(
                        zip,
                        root,
                        sdlc,
                        "automation_sdlc",
                        Set.of(".cursor"),
                        files,
                        structure,
                        missing("automation_sdlc")
                );
            } else {
                int copied = copyBundledPrefix(zip, "", zipPath(root, "automation_sdlc"), Set.of(".cursor"), files);
                if (copied == 0) {
                    putText(zip, zipPath(root, "automation_sdlc/README.md"), missing("automation_sdlc"));
                    files.incrementAndGet();
                }
                structure.add(new WorkspaceEntry("automation_sdlc", "directory"));
            }

            addCursorOverlay(zip, root, files, structure);
            addConfiguredRepos(zip, root, request.repositories(), files, structure);
        }
        return new WorkspaceBundle(buffer.toByteArray(), root + ".zip", List.copyOf(structure), files.get());
    }

    public static String workspaceRootName(String projectName) {
        String slug = slugify(projectName);
        if (slug.isBlank()) {
            slug = "project";
        }
        return slug.endsWith("-workspace") ? slug : slug + "-workspace";
    }

    public static String encodeStructure(List<WorkspaceEntry> structure) {
        return structure.stream()
                .map(entry -> entry.name() + ":" + entry.kind())
                .collect(Collectors.joining(","));
    }

    private void addCursorOverlay(ZipOutputStream zip, String root, AtomicInteger files, List<WorkspaceEntry> structure)
            throws IOException {
        Path overlay = resolveCursorOverlay();
        int copied = 0;
        if (overlay != null) {
            copied = copyTree(zip, overlay, zipPath(root, ".cursor"), Set.of(), files);
        }
        if (copied == 0) {
            copied = copyBundledPrefix(zip, ".cursor/", zipPath(root, ".cursor"), Set.of(), files);
        }
        if (copied == 0) {
            putText(zip, zipPath(root, ".cursor/README.md"), missing(".cursor"));
            files.incrementAndGet();
        }
        structure.add(new WorkspaceEntry(".cursor", "directory"));
    }

    private void addConfiguredRepos(
            ZipOutputStream zip,
            String root,
            List<RepoFolder> repositories,
            AtomicInteger files,
            List<WorkspaceEntry> structure
    ) throws IOException {
        LinkedHashSet<String> used = new LinkedHashSet<>(RESERVED_TOP_LEVEL);
        for (RepoFolder repo : repositories) {
            String name = slugify(repo.name());
            if (name.isBlank() || !used.add(name)) {
                continue;
            }
            StringBuilder readme = new StringBuilder("# ").append(name).append("\n\n");
            if (repo.purpose() != null && !repo.purpose().isBlank()) {
                readme.append(repo.purpose().trim()).append("\n\n");
            }
            if (repo.description() != null && !repo.description().isBlank()) {
                readme.append(repo.description().trim()).append("\n");
            }
            putText(zip, zipPath(root, name + "/README.md"), readme.toString());
            files.incrementAndGet();
            structure.add(new WorkspaceEntry(name, "directory"));
        }
    }

    private Path resolveCursorOverlay() {
        Path cwd = Path.of(System.getProperty("user.dir")).normalize();
        Path parent = cwd.getParent();
        Path workspaceCursor = cwd.resolve(".cursor");
        if (hasCursorContent(workspaceCursor)) {
            return workspaceCursor;
        }
        if (parent != null) {
            Path sibling = parent.resolve(".cursor");
            if (hasCursorContent(sibling) && sibling.resolve("commands").toFile().isDirectory()) {
                return sibling;
            }
        }
        Path sdlc = resolveAutomationSdlc();
        if (sdlc != null) {
            Path nested = sdlc.resolve(".cursor");
            if (hasCursorContent(nested)) {
                return nested;
            }
        }
        return null;
    }

    private static boolean hasCursorContent(Path dir) {
        if (dir == null || !Files.isDirectory(dir)) {
            return false;
        }
        try (var walk = Files.walk(dir)) {
            return walk.anyMatch(Files::isRegularFile);
        } catch (IOException ignored) {
            return false;
        }
    }

    static String zipPath(String root, String relative) {
        return root + "/" + relative.replace('\\', '/');
    }

    static String slugify(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String slug = value.trim().toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("-+$", "");
        }
        return slug;
    }

    private static String missing(String folder) {
        return "# " + folder + "\n\nBlink could not find this folder to copy into the download.\n";
    }

    private void copySharedFolder(
            ZipOutputStream zip,
            String root,
            Path source,
            String zipName,
            Set<String> extraSkipDirs,
            AtomicInteger files,
            List<WorkspaceEntry> structure,
            String placeholder
    ) throws IOException {
        if (source != null && Files.isDirectory(source)) {
            int copied = copyTree(zip, source, zipPath(root, zipName), extraSkipDirs, files);
            if (copied > 0) {
                structure.add(new WorkspaceEntry(zipName, "directory"));
                return;
            }
        }
        putText(zip, zipPath(root, zipName + "/README.md"), placeholder);
        files.incrementAndGet();
        structure.add(new WorkspaceEntry(zipName, "directory"));
    }

    private int copyTree(
            ZipOutputStream zip,
            Path root,
            String zipPrefix,
            Set<String> extraSkipDirs,
            AtomicInteger files
    ) throws IOException {
        AtomicInteger copied = new AtomicInteger();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String name = dir.getFileName().toString();
                if (SKIP_DIR_NAMES.contains(name) || extraSkipDirs.contains(name)) {
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
                files.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });
        return copied.get();
    }

    int copyBundledPrefix(ZipOutputStream zip, String entryPrefix, String zipPrefix) throws IOException {
        return copyBundledPrefix(zip, entryPrefix, zipPrefix, Set.of(), new AtomicInteger());
    }

    int copyBundledPrefix(
            ZipOutputStream zip,
            String entryPrefix,
            String zipPrefix,
            Set<String> skipTopLevel,
            AtomicInteger files
    ) throws IOException {
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
                    if (name.isBlank() || skipBundledPath(name, skipTopLevel)) {
                        continue;
                    }
                    zip.putNextEntry(new ZipEntry(zipPrefix + "/" + name));
                    zin.transferTo(zip);
                    zip.closeEntry();
                    copied++;
                    files.incrementAndGet();
                }
            }
            return copied;
        }
    }

    private static boolean skipBundledPath(String relative, Set<String> skipTopLevel) {
        String[] parts = relative.split("/");
        if (parts.length > 0 && skipTopLevel.contains(parts[0])) {
            return true;
        }
        for (String part : parts) {
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
