package com.talentserv.blink.service;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.springframework.stereotype.Service;

import com.talentserv.blink.config.BlinkProperties;
import com.talentserv.blink.domain.Project;

@Service
public class ZipPackageService {

    private static final Set<String> SKIP_DIR_NAMES = Set.of(
            ".git",
            ".idea",
            "node_modules",
            "target",
            "dist",
            "__pycache__",
            ".venv",
            ".pytest_cache",
            ".mypy_cache"
    );

    private final BlinkProperties properties;
    private final SpringBootProjectGenerator springBootProjectGenerator;

    public ZipPackageService(BlinkProperties properties, SpringBootProjectGenerator springBootProjectGenerator) {
        this.properties = properties;
        this.springBootProjectGenerator = springBootProjectGenerator;
    }

    public void writeWorkspace(Project project, String requirementMarkdown, OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            addAutomationSdlc(zip);
            addGeneratedProject(zip, project);
            putText(zip, "requirement.md", requirementMarkdown);
        }
    }

    private void addGeneratedProject(ZipOutputStream zip, Project project) throws IOException {
        Map<String, String> files = springBootProjectGenerator.generate(project);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            putText(zip, entry.getKey(), entry.getValue());
        }
    }

    private void addAutomationSdlc(ZipOutputStream zip) throws IOException {
        Path root = resolveAutomationSdlc();
        if (root == null) {
            putText(zip, "automation_sdlc/README.md", """
                    # automation_sdlc

                    Blink could not find the `automation_sdlc` folder next to this API.
                    Set `BLINK_AUTOMATION_SDLC_PATH` to the framework directory and download again.
                    """);
            return;
        }
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
                String name = file.getFileName().toString();
                if (name.endsWith(".pyc") || name.equals(".DS_Store")) {
                    return FileVisitResult.CONTINUE;
                }
                String relative = root.relativize(file).toString().replace('\\', '/');
                zip.putNextEntry(new ZipEntry("automation_sdlc/" + relative));
                Files.copy(file, zip);
                zip.closeEntry();
                return FileVisitResult.CONTINUE;
            }
        });
    }

    Path resolveAutomationSdlc() {
        Path configured = Path.of(properties.getAutomationSdlcPath());
        if (!configured.isAbsolute()) {
            configured = Path.of(System.getProperty("user.dir")).resolve(configured);
        }
        configured = configured.normalize();
        if (Files.isDirectory(configured)) {
            return configured;
        }
        Path sibling = Path.of(System.getProperty("user.dir")).resolve("automation_sdlc").normalize();
        if (Files.isDirectory(sibling)) {
            return sibling;
        }
        Path parentSibling = Path.of(System.getProperty("user.dir")).getParent();
        if (parentSibling != null) {
            Path fromParent = parentSibling.resolve("automation_sdlc").normalize();
            if (Files.isDirectory(fromParent)) {
                return fromParent;
            }
        }
        return null;
    }

    static void putText(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }
}
