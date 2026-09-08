package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.talentserv.blink.config.BlinkProperties;

class ZipPackageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void zipContainsPilotDemoFolders() throws Exception {
        Path workspace = tempDir.resolve("workspace");
        Path sdlc = workspace.resolve("automation_sdlc");
        Files.createDirectories(sdlc.resolve(".cursor"));
        Files.writeString(sdlc.resolve("marker.txt"), "sdlc");
        Files.writeString(sdlc.resolve(".cursor").resolve("rules.md"), "cursor overlay");
        Files.createDirectories(sdlc.resolve(".cursor").resolve("commands"));
        Files.writeString(sdlc.resolve(".cursor").resolve("commands").resolve("setup-new-workspace.md"), "# setup");

        Path ui = workspace.resolve("blink_demo");
        Files.createDirectories(ui.resolve("src"));
        Files.writeString(ui.resolve("package.json"), "{\"name\":\"blink-ui\"}");
        Files.createDirectories(ui.resolve("node_modules"));
        Files.writeString(ui.resolve("node_modules").resolve("skip.js"), "nope");

        Path backend = workspace.resolve("blink-backend");
        Files.createDirectories(backend.resolve("src"));
        Files.writeString(backend.resolve("pom.xml"), "<project/>");
        Files.writeString(backend.resolve(".env"), "SECRET=1");

        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", workspace.toString());
        try {
            BlinkProperties properties = new BlinkProperties();
            properties.setAutomationSdlcPath("automation_sdlc");
            ZipPackageService service = new ZipPackageService(properties);

            ZipPackageService.WorkspaceBundle bundle = service.packageWorkspace(
                    new ZipPackageService.PackageRequest(
                            "Gymantic",
                            "# Banking Application\n\nNeed accounts.\n",
                            List.of(
                                    new ZipPackageService.RepoFolder("gymantic-backend", "Backend", "API"),
                                    new ZipPackageService.RepoFolder("gymantic-frontend", "Frontend", "UI"),
                                    new ZipPackageService.RepoFolder("gymantic-db", "Database", "Schema"),
                                    new ZipPackageService.RepoFolder("gymantic-infra", "Infrastructure", "IaC")
                            )
                    )
            );

            Set<String> names = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle.zipBytes()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    names.add(entry.getName());
                    if (entry.getName().equals("gymantic_workspace/requirement.md")) {
                        String markdown = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(markdown).contains("Need accounts");
                    }
                }
            }

            assertThat(bundle.filename()).isEqualTo("gymantic_workspace.zip");
            assertThat(bundle.structure())
                    .extracting(ZipPackageService.WorkspaceEntry::name)
                    .contains(
                            "requirement.md",
                            "automation_sdlc",
                            ".cursor",
                            ".cursor/mcp.json",
                            "gymantic-backend",
                            "gymantic-frontend",
                            "gymantic-db",
                            "gymantic-infra"
                    );
            assertThat(names).contains(
                    "gymantic_workspace/",
                    "gymantic_workspace/.cursor/rules.md",
                    "gymantic_workspace/.cursor/commands/setup-new-workspace.md",
                    "gymantic_workspace/.cursor/mcp.json",
                    "gymantic_workspace/.cursor/mcp.windows.json",
                    "gymantic_workspace/.cursor/mcp.unix.json",
                    "gymantic_workspace/.cursor/MCP_SETUP.md",
                    "gymantic_workspace/MCP_SETUP.md",
                    "gymantic_workspace/automation_sdlc/marker.txt",
                    "gymantic_workspace/automation_sdlc/.env.mcp.example",
                    "gymantic_workspace/automation_sdlc/env.mcp.example",
                    "gymantic_workspace/automation_sdlc/scripts/mcp-npx.sh",
                    "gymantic_workspace/automation_sdlc/scripts/mcp-npx.ps1",
                    "gymantic_workspace/requirement.md",
                    "gymantic_workspace/gymantic-backend/README.md",
                    "gymantic_workspace/gymantic-frontend/README.md",
                    "gymantic_workspace/gymantic-db/README.md",
                    "gymantic_workspace/gymantic-infra/README.md"
            );
            assertThat(names).noneMatch(name -> name.contains("/blink_demo/") || name.contains("blink_demo/"));
            assertThat(names).noneMatch(name -> name.contains("/blink_backend/") || name.contains("blink_backend/"));
            assertThat(names).noneMatch(name -> name.contains("node_modules"));
            assertThat(names).noneMatch(name -> name.endsWith(".env"));
            assertThat(ZipPackageService.encodeStructure(bundle.structure()))
                    .contains("requirement.md:file", ".cursor:directory");
        } finally {
            System.setProperty("user.dir", previous);
        }
    }

    @Test
    void zipIncludesMcpJsonForConnectedProvidersWithEnvRefsOnly() throws Exception {
        BlinkProperties properties = new BlinkProperties();
        properties.setAutomationSdlcPath(tempDir.resolve("missing-sdlc").toString());
        ZipPackageService service = new ZipPackageService(properties);

        ZipPackageService.WorkspaceBundle bundle = service.packageWorkspace(
                new ZipPackageService.PackageRequest(
                        "Acme",
                        "# req\n",
                        List.of(),
                        List.of(),
                        List.of("github", "jira")
                )
        );

        String mcpJson = null;
        String windowsJson = null;
        String envExample = null;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle.zipBytes()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().endsWith("/.cursor/mcp.json")) {
                    mcpJson = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (entry.getName().endsWith("/.cursor/mcp.windows.json")) {
                    windowsJson = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
                if (entry.getName().endsWith("/automation_sdlc/.env.mcp.example")) {
                    envExample = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }

        assertThat(mcpJson).isNotNull();
        assertThat(mcpJson).contains(McpJsonWriter.UNIX_WRAPPER);
        assertThat(mcpJson).contains("\"github\"", "\"jira\"", "${env:GITHUB_PERSONAL_ACCESS_TOKEN}", "${env:JIRA_API_TOKEN}");
        assertThat(mcpJson).doesNotContain("ghp_", "\"confluence\"", "/Users/");
        assertThat(windowsJson).contains("\"command\": \"powershell.exe\"");
        assertThat(windowsJson).contains(McpJsonWriter.WINDOWS_WRAPPER);
        assertThat(envExample).contains("GITHUB_PERSONAL_ACCESS_TOKEN=", "JIRA_URL=");
    }

    @Test
    void bundledAutomationSdlcFillsFolderWhenDiskCopyIsMissing() throws Exception {
        String previous = System.getProperty("user.dir");
        System.setProperty("user.dir", tempDir.toString());
        try {
            BlinkProperties properties = new BlinkProperties();
            properties.setAutomationSdlcPath(tempDir.resolve("missing-sdlc").toString());
            ZipPackageService service = new ZipPackageService(properties);

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            service.writeWorkspace("# req\n", buffer);

            Set<String> names = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    names.add(entry.getName());
                }
            }

            assertThat(names).anyMatch(name -> name.startsWith("my_pilot_demo_workspace/automation_sdlc/"));
            assertThat(names).anyMatch(name ->
                    name.equals("my_pilot_demo_workspace/automation_sdlc/Makefile")
                            || name.equals("my_pilot_demo_workspace/automation_sdlc/README.md"));
            assertThat(names).noneMatch(name -> name.contains("/.git/"));
        } finally {
            System.setProperty("user.dir", previous);
        }
    }

    @Test
    void configuredReposSkipReservedWorkspaceFolderNames() throws Exception {
        BlinkProperties properties = new BlinkProperties();
        properties.setAutomationSdlcPath(tempDir.resolve("missing-sdlc").toString());
        ZipPackageService service = new ZipPackageService(properties);

        ZipPackageService.WorkspaceBundle bundle = service.packageWorkspace(
                new ZipPackageService.PackageRequest(
                        "Gymantic",
                        "# req\n",
                        List.of(
                                new ZipPackageService.RepoFolder("automation_sdlc", "SDLC", "collision"),
                                new ZipPackageService.RepoFolder("gymantic-api", "API", "Custom service")
                        )
                )
        );

        assertThat(bundle.structure())
                .extracting(ZipPackageService.WorkspaceEntry::name)
                .contains("automation_sdlc", "gymantic-api")
                .doesNotHaveDuplicates();
    }

    @Test
    void overlayFilesAreWrittenUnderCursorAiSdlc() throws Exception {
        BlinkProperties properties = new BlinkProperties();
        properties.setAutomationSdlcPath(tempDir.resolve("missing-sdlc").toString());
        ZipPackageService service = new ZipPackageService(properties);

        ZipPackageService.WorkspaceBundle bundle = service.packageWorkspace(
                new ZipPackageService.PackageRequest(
                        "Food Delivery",
                        "# req\n",
                        List.of(),
                        List.of(
                                new ZipPackageService.OverlayFile(
                                        ".cursor/ai-sdlc/workspace-context.md",
                                        "# Food Delivery\n"
                                ),
                                new ZipPackageService.OverlayFile(".cursor/commands/hack.md", "nope"),
                                new ZipPackageService.OverlayFile("../secret.txt", "nope")
                        )
                )
        );

        Set<String> names = new HashSet<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle.zipBytes()))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
        }

        assertThat(names).contains("food_delivery_workspace/.cursor/ai-sdlc/workspace-context.md");
        assertThat(names).noneMatch(name -> name.contains("secret.txt"));
        assertThat(names).noneMatch(name -> name.contains("commands/hack.md"));
        assertThat(bundle.structure())
                .extracting(ZipPackageService.WorkspaceEntry::name)
                .contains(".cursor/ai-sdlc");
    }

    @Test
    void overlayPathMustStayUnderCursorAiSdlc() {
        assertThat(ZipPackageService.sanitizeOverlayPath(".cursor/ai-sdlc/workspace-context.md"))
                .isEqualTo(".cursor/ai-sdlc/workspace-context.md");
        assertThat(ZipPackageService.sanitizeOverlayPath(".cursor/commands/setup-new-workspace.md")).isNull();
        assertThat(ZipPackageService.sanitizeOverlayPath(".cursor/ai-sdlc/foo/../../secret.txt")).isNull();
        assertThat(ZipPackageService.sanitizeOverlayPath(".cursor/ai-sdlc")).isNull();
        assertThat(ZipPackageService.sanitizeOverlayPath("/.cursor/ai-sdlc/workspace-context.md"))
                .isEqualTo(".cursor/ai-sdlc/workspace-context.md");
    }
}
