package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.domain.Project;

class ZipPackageServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void zipContainsFrameworkGeneratedProjectAndRequirements() throws Exception {
        Path framework = tempDir.resolve("automation_sdlc");
        Files.createDirectories(framework);
        Files.writeString(framework.resolve("marker.txt"), "sdlc");

        BlinkProperties properties = new BlinkProperties();
        properties.setAutomationSdlcPath(framework.toString());
        ZipPackageService service = new ZipPackageService(properties, new SpringBootProjectGenerator());

        Project project = new Project();
        project.setProjectName("Banking Application");
        project.setDescription("Demo banking workspace");

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        service.writeWorkspace(project, "# Banking Application\n\nNeed accounts.\n", buffer);

        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(buffer.toByteArray()))) {
            boolean sawFramework = false;
            boolean sawPom = false;
            boolean sawRequirement = false;
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (entry.getName().equals("automation_sdlc/marker.txt")) {
                    sawFramework = true;
                }
                if (entry.getName().equals("banking-application/pom.xml")) {
                    sawPom = true;
                    String pom = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(pom).contains("4.1.0").contains("25");
                }
                if (entry.getName().equals("requirement.md")) {
                    sawRequirement = true;
                    String markdown = new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(markdown).contains("Need accounts");
                }
            }
            assertThat(sawFramework).isTrue();
            assertThat(sawPom).isTrue();
            assertThat(sawRequirement).isTrue();
        }
    }
}
