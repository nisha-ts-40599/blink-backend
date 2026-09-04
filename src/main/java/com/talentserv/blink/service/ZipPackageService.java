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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;

@Service
public class ZipPackageService {

    private static final Logger log = LoggerFactory.getLogger(ZipPackageService.class);

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

    private final BlinkProperties properties;

    public ZipPackageService(BlinkProperties properties) {
        this.properties = properties;
    }

    public record RepoFolder(String name, String purpose, String description) {
    }

    public record OverlayFile(String path, String content) {
    }

    public record PackageRequest(
            String workspaceRoot,
            String requirementMarkdown,
            List<RepoFolder> repositories,
            List<OverlayFile> overlayFiles,
            List<String> mcpProviders,
            McpJsonWriter.SiteHints mcpSiteHints
    ) {
        public PackageRequest {
            repositories = repositories == null ? List.of() : List.copyOf(repositories);
            overlayFiles = overlayFiles == null ? List.of() : List.copyOf(overlayFiles);
            mcpProviders = mcpProviders == null ? List.of() : List.copyOf(mcpProviders);
            mcpSiteHints = mcpSiteHints == null ? McpJsonWriter.SiteHints.empty() : mcpSiteHints;
        }

        public PackageRequest(String workspaceRoot, String requirementMarkdown, List<RepoFolder> repositories) {
            this(workspaceRoot, requirementMarkdown, repositories, List.of(), List.of(), McpJsonWriter.SiteHints.empty());
        }

        public PackageRequest(
                String workspaceRoot,
                String requirementMarkdown,
                List<RepoFolder> repositories,
                List<OverlayFile> overlayFiles
        ) {
            this(workspaceRoot, requirementMarkdown, repositories, overlayFiles, List.of(), McpJsonWriter.SiteHints.empty());
        }

        public PackageRequest(
                String workspaceRoot,
                String requirementMarkdown,
                List<RepoFolder> repositories,
                List<OverlayFile> overlayFiles,
                List<String> mcpProviders
        ) {
            this(workspaceRoot, requirementMarkdown, repositories, overlayFiles, mcpProviders, McpJsonWriter.SiteHints.empty());
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
        long started = System.currentTimeMillis();
        String root = workspaceRootName(request.workspaceRoot());
        Path sdlc = resolveAutomationSdlc();
        log.info("Zip start root={} automationSdlc={}", root, sdlc);
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

            if (sdlc != null) {
                copySharedFolder(
                        zip,
                        root,
                        sdlc,
                        "automation_sdlc",
                        Set.of(".cursor"),
                        Set.of(".env.mcp.example"),
                        files,
                        structure,
                        missing("automation_sdlc")
                );
            } else {
                int copied = copyBundledPrefix(
                        zip,
                        "",
                        zipPath(root, "automation_sdlc"),
                        Set.of(".cursor"),
                        Set.of(".env.mcp.example"),
                        files
                );
                if (copied == 0) {
                    putText(zip, zipPath(root, "automation_sdlc/README.md"), missing("automation_sdlc"));
                    files.incrementAndGet();
                }
                structure.add(new WorkspaceEntry("automation_sdlc", "directory"));
            }

            addCursorOverlay(zip, root, files, structure);
            addAgentOverlay(zip, root, request.overlayFiles(), files, structure);
            addMcpConfig(zip, root, request.mcpProviders(), request.mcpSiteHints(), files, structure);
            addConfiguredRepos(zip, root, request.repositories(), files, structure);
        }
        WorkspaceBundle bundle = new WorkspaceBundle(buffer.toByteArray(), root + ".zip", List.copyOf(structure), files.get());
        log.info(
                "Zip ready root={} files={} bytes={} ms={}",
                root,
                bundle.fileCount(),
                bundle.zipBytes().length,
                System.currentTimeMillis() - started
        );
        return bundle;
    }

    public static String workspaceRootName(String projectName) {
        return WorkspaceNames.folder(projectName);
    }

    public static String workspaceRootName(String projectName, Long projectId) {
        return WorkspaceNames.folder(projectName, projectId);
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
        // Skip host MCP configs — download always writes portable OS variants via addMcpConfig.
        Set<String> skipFiles = Set.of("mcp.json", "mcp.windows.json", "mcp.unix.json", "MCP_SETUP.md");
        if (overlay != null) {
            copied = copyTree(zip, overlay, zipPath(root, ".cursor"), Set.of(), skipFiles, files);
        }
        if (copied == 0) {
            copied = copyBundledPrefix(zip, ".cursor/", zipPath(root, ".cursor"), Set.of(), skipFiles, files);
        }
        if (copied == 0) {
            putText(zip, zipPath(root, ".cursor/README.md"), missing(".cursor"));
            files.incrementAndGet();
        }
        structure.add(new WorkspaceEntry(".cursor", "directory"));
    }

    private void addAgentOverlay(
            ZipOutputStream zip,
            String root,
            List<OverlayFile> overlayFiles,
            AtomicInteger files,
            List<WorkspaceEntry> structure
    ) throws IOException {
        boolean added = false;
        for (OverlayFile file : overlayFiles) {
            String relative = sanitizeOverlayPath(file.path());
            if (relative == null) {
                continue;
            }
            putText(zip, zipPath(root, relative), file.content() == null ? "" : file.content());
            files.incrementAndGet();
            added = true;
        }
        if (added) {
            structure.add(new WorkspaceEntry(".cursor/ai-sdlc", "directory"));
        }
    }

    static String sanitizeOverlayPath(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        String normalized = path.replace('\\', '/').replaceFirst("^/+", "");
        if (normalized.contains("..") || !normalized.startsWith(".cursor/ai-sdlc/") || normalized.endsWith("/")) {
            return null;
        }
        return normalized;
    }

    private void addMcpConfig(
            ZipOutputStream zip,
            String root,
            List<String> mcpProviders,
            McpJsonWriter.SiteHints siteHints,
            AtomicInteger files,
            List<WorkspaceEntry> structure
    ) throws IOException {
        putText(zip, zipPath(root, ".cursor/mcp.json"), McpJsonWriter.mcpJson(mcpProviders));
        files.incrementAndGet();
        putText(
                zip,
                zipPath(root, ".cursor/mcp.windows.json"),
                McpJsonWriter.mcpJson(mcpProviders, McpJsonWriter.OsProfile.WINDOWS)
        );
        files.incrementAndGet();
        putText(
                zip,
                zipPath(root, ".cursor/mcp.unix.json"),
                McpJsonWriter.mcpJson(mcpProviders, McpJsonWriter.OsProfile.UNIX)
        );
        files.incrementAndGet();
        String setup = McpJsonWriter.setupReadme();
        // Cursor loads .cursor/mcp.json; also put a root copy so Explorer users find setup instructions.
        putText(zip, zipPath(root, ".cursor/MCP_SETUP.md"), setup);
        files.incrementAndGet();
        putText(zip, zipPath(root, "MCP_SETUP.md"), setup);
        files.incrementAndGet();
        String envExample = McpJsonWriter.envMcpExample(mcpProviders, siteHints);
        // Canonical name for the wrapper; also ship a non-dot twin — Cursor often hides `.env*`.
        putText(zip, zipPath(root, "automation_sdlc/.env.mcp.example"), envExample);
        files.incrementAndGet();
        putText(zip, zipPath(root, "automation_sdlc/env.mcp.example"), envExample);
        files.incrementAndGet();
        // Framework kit skips automation_sdlc/scripts/ — always ship the MCP wrappers the json points at.
        addMcpWrapperScripts(zip, root, files);
        if (structure.stream().noneMatch(entry -> ".cursor".equals(entry.name()))) {
            structure.add(new WorkspaceEntry(".cursor", "directory"));
        }
        if (structure.stream().noneMatch(entry -> ".cursor/mcp.json".equals(entry.name()))) {
            structure.add(new WorkspaceEntry(".cursor/mcp.json", "file"));
        }
        if (structure.stream().noneMatch(entry -> "automation_sdlc/env.mcp.example".equals(entry.name()))) {
            structure.add(new WorkspaceEntry("automation_sdlc/env.mcp.example", "file"));
        }
        if (structure.stream().noneMatch(entry -> "MCP_SETUP.md".equals(entry.name()))) {
            structure.add(new WorkspaceEntry("MCP_SETUP.md", "file"));
        }
    }

    private void addMcpWrapperScripts(ZipOutputStream zip, String root, AtomicInteger files) throws IOException {
        Path sdlc = resolveAutomationSdlc();
        copyOrEmbedWrapper(zip, root, sdlc, "mcp-npx.sh", files);
        copyOrEmbedWrapper(zip, root, sdlc, "mcp-npx.ps1", files);
    }

    private void copyOrEmbedWrapper(
            ZipOutputStream zip,
            String root,
            Path sdlc,
            String scriptName,
            AtomicInteger files
    ) throws IOException {
        String zipEntry = zipPath(root, "automation_sdlc/scripts/" + scriptName);
        if (sdlc != null) {
            Path file = sdlc.resolve("scripts").resolve(scriptName);
            if (Files.isRegularFile(file)) {
                zip.putNextEntry(new ZipEntry(zipEntry));
                Files.copy(file, zip);
                zip.closeEntry();
                files.incrementAndGet();
                return;
            }
        }
        // Fallback so the zip still references a runnable stub if disk copy is missing.
        String stub = scriptName.endsWith(".ps1")
                ? """
                Write-Error "mcp-npx.ps1 missing from automation_sdlc/scripts — re-download or copy from AI-SDLC."
                exit 1
                """
                : """
                #!/usr/bin/env bash
                echo "mcp-npx.sh missing from automation_sdlc/scripts — re-download or copy from AI-SDLC." >&2
                exit 1
                """;
        putText(zip, zipEntry, stub);
        files.incrementAndGet();
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
            Set<String> skipFileNames,
            AtomicInteger files,
            List<WorkspaceEntry> structure,
            String placeholder
    ) throws IOException {
        if (source != null && Files.isDirectory(source)) {
            int copied = copyTree(zip, source, zipPath(root, zipName), extraSkipDirs, skipFileNames, files);
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
        return copyTree(zip, root, zipPrefix, extraSkipDirs, Set.of(), files);
    }

    private int copyTree(
            ZipOutputStream zip,
            Path root,
            String zipPrefix,
            Set<String> extraSkipDirs,
            Set<String> skipFileNames,
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
                String parent = dir.getParent() == null ? null : dir.getParent().getFileName().toString();
                if (FrameworkKitFilter.skipDirectory(name, parent) || extraSkipDirs.contains(name)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                String fileName = file.getFileName().toString();
                if (skipFile(fileName) || skipFileNames.contains(fileName)) {
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
        return copyBundledPrefix(zip, entryPrefix, zipPrefix, Set.of(), Set.of(), new AtomicInteger());
    }

    int copyBundledPrefix(
            ZipOutputStream zip,
            String entryPrefix,
            String zipPrefix,
            Set<String> skipTopLevel,
            AtomicInteger files
    ) throws IOException {
        return copyBundledPrefix(zip, entryPrefix, zipPrefix, skipTopLevel, Set.of(), files);
    }

    int copyBundledPrefix(
            ZipOutputStream zip,
            String entryPrefix,
            String zipPrefix,
            Set<String> skipTopLevel,
            Set<String> skipFileNames,
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
                    if (name.isBlank() || skipBundledPath(name, skipTopLevel, skipFileNames)) {
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

    private static boolean skipBundledPath(String relative, Set<String> skipTopLevel, Set<String> skipFileNames) {
        String[] parts = relative.split("/");
        if (parts.length > 0 && skipTopLevel.contains(parts[0])) {
            return true;
        }
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            boolean last = i == parts.length - 1;
            String parent = i == 0 ? null : parts[i - 1];
            if (!last && FrameworkKitFilter.skipDirectory(part, parent)) {
                return true;
            }
            if (last && (skipFile(part) || skipFileNames.contains(part))) {
                return true;
            }
        }
        return false;
    }

    Path resolveAutomationSdlc() {
        return FrameworkKitPaths.resolve(properties.getAutomationSdlcPath());
    }

    private static boolean skipFile(String name) {
        return FrameworkKitFilter.skipFile(name);
    }

    static void putText(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
