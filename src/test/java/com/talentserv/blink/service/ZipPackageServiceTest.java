package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
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

            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            service.writeWorkspace("# Banking Application\n\nNeed accounts.\n", buffer);

            Set<String> names = new HashSet<>();
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                    names.add(entry.getName());
                    if (entry.getName().equals("MY_PILOT_DEMO/requirement.md")) {
                        String markdown = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                        assertThat(markdown).contains("Need accounts");
                    }
                }
            }

            assertThat(names).contains(
                    "MY_PILOT_DEMO/",
                    "MY_PILOT_DEMO/.cursor/rules.md",
                    "MY_PILOT_DEMO/automation_sdlc/marker.txt",
                    "MY_PILOT_DEMO/blink_ui/package.json",
                    "MY_PILOT_DEMO/blink_backend/pom.xml",
                    "MY_PILOT_DEMO/requirement.md"
            );
            assertThat(names).noneMatch(name -> name.contains("node_modules"));
            assertThat(names).noneMatch(name -> name.endsWith(".env"));
        } finally {
            System.setProperty("user.dir", previous);
        }
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

            assertThat(names).anyMatch(name -> name.startsWith("MY_PILOT_DEMO/automation_sdlc/"));
            assertThat(names).contains("MY_PILOT_DEMO/automation_sdlc/Makefile");
            assertThat(names).contains("MY_PILOT_DEMO/automation_sdlc/README.md");
            assertThat(names).anyMatch(name -> name.startsWith("MY_PILOT_DEMO/automation_sdlc/ai-sdlc/"));
            assertThat(names).noneMatch(name -> name.contains("/.git/"));
        } finally {
            System.setProperty("user.dir", previous);
        }
    }
}
