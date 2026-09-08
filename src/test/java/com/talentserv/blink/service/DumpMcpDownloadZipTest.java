package com.talentserv.blink.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import com.talentserv.blink.config.BlinkProperties;

/**
 * Manual flow check: writes a sample download zip under {@code target/mcp-flow-test/}.
 * Run: {@code mvn -Dtest=DumpMcpDownloadZipTest test}
 */
class DumpMcpDownloadZipTest {

    @Test
    void writeSampleZipForManualInspection() throws Exception {
        BlinkProperties properties = new BlinkProperties();
        properties.setAutomationSdlcPath("../automation_sdlc");
        ZipPackageService service = new ZipPackageService(properties);

        ZipPackageService.WorkspaceBundle bundle = service.packageWorkspace(
                new ZipPackageService.PackageRequest(
                        "Mcp Flow Demo",
                        "# MCP flow demo\n",
                        List.of(new ZipPackageService.RepoFolder("demo-api", "API", "Sample")),
                        List.of(),
                        List.of("github", "jira", "confluence")
                )
        );

        Path outDir = Path.of("target", "mcp-flow-test");
        Files.createDirectories(outDir);
        Path zipPath = outDir.resolve(bundle.filename());
        Files.write(zipPath, bundle.zipBytes());

        Path extract = outDir.resolve("extracted");
        if (Files.exists(extract)) {
            try (var walk = Files.walk(extract)) {
                walk.sorted((a, b) -> b.compareTo(a)).forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        Files.createDirectories(extract);

        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(zipPath))) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                Path dest = extract.resolve(entry.getName());
                if (entry.isDirectory()) {
                    Files.createDirectories(dest);
                    continue;
                }
                Files.createDirectories(dest.getParent());
                Files.write(dest, zip.readAllBytes());
            }
        }

        Path mcp = find(extract, ".cursor/mcp.json");
        Path win = find(extract, ".cursor/mcp.windows.json");
        Path setup = find(extract, ".cursor/MCP_SETUP.md");
        Path envExample = find(extract, "automation_sdlc/.env.mcp.example");

        System.out.println("ZIP=" + zipPath.toAbsolutePath());
        System.out.println("EXTRACTED=" + extract.toAbsolutePath());
        System.out.println("MCP_JSON=" + (mcp == null ? "MISSING" : mcp));
        System.out.println("WINDOWS_JSON=" + (win == null ? "MISSING" : win));
        System.out.println("SETUP=" + (setup == null ? "MISSING" : setup));
        System.out.println("ENV_EXAMPLE=" + (envExample == null ? "MISSING" : envExample));
        if (mcp != null) {
            System.out.println("--- mcp.json ---");
            System.out.println(Files.readString(mcp, StandardCharsets.UTF_8));
        }
        if (win != null) {
            System.out.println("--- mcp.windows.json (command line) ---");
            String text = Files.readString(win, StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.contains("\"command\"")) {
                    System.out.println(line.trim());
                }
            }
        }
    }

    private static Path find(Path root, String suffix) throws Exception {
        String normalized = suffix.replace('\\', '/');
        try (var walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(path -> path.toString().replace('\\', '/').endsWith(normalized))
                    .findFirst()
                    .orElse(null);
        }
    }
}
