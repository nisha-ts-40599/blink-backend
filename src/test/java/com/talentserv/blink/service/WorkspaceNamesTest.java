package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

class WorkspaceNamesTest {

    @Test
    void folderUsesUnderscoreWorkspaceSuffix() {
        assertThat(WorkspaceNames.folder("Food Delivery")).isEqualTo("food_delivery_workspace");
        assertThat(WorkspaceNames.folder("food_delivery_workspace")).isEqualTo("food_delivery_workspace");
        assertThat(WorkspaceNames.folder("Food Delivery", 6L)).isEqualTo("food_delivery_6_workspace");
        assertThat(WorkspaceNames.key("Food Delivery", ".cursor/ai-sdlc/workspace-context.md"))
                .isEqualTo("food_delivery_workspace/.cursor/ai-sdlc/workspace-context.md");
        assertThat(WorkspaceNames.key("Food Delivery", 6L, "requirement.md"))
                .isEqualTo("food_delivery_6_workspace/requirement.md");
        assertThat(WorkspaceNames.publicUrl("https://blink-ai-dev.s3-us-west-2.amazonaws.com/", "Food Delivery"))
                .isEqualTo("https://blink-ai-dev.s3-us-west-2.amazonaws.com/food_delivery_workspace/");
        assertThat(WorkspaceNames.publicUrl("https://blink-ai-dev.s3-us-west-2.amazonaws.com/", "Food Delivery", 6L))
                .isEqualTo("https://blink-ai-dev.s3-us-west-2.amazonaws.com/food_delivery_6_workspace/");
    }

    @Test
    void zipObjectsKeepsWorkspaceRootAndCursorBesideAutomationSdlc() throws Exception {
        Map<String, byte[]> objects = new LinkedHashMap<>();
        objects.put("food_delivery_workspace/automation_sdlc/README.md", "# sdlc\n".getBytes());
        objects.put("food_delivery_workspace/.cursor/ai-sdlc/workspace-context.md", "# ctx\n".getBytes());
        objects.put("food_delivery_workspace/requirement.md", "# req\n".getBytes());

        var bundle = S3WorkspaceService.zipObjects("food_delivery_workspace", objects);

        assertThat(bundle.filename()).isEqualTo("food_delivery_workspace.zip");
        try (ZipInputStream zip = new ZipInputStream(new java.io.ByteArrayInputStream(bundle.zipBytes()))) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                names.add(entry.getName());
            }
            assertThat(names).contains(
                    "food_delivery_workspace/",
                    "food_delivery_workspace/automation_sdlc/README.md",
                    "food_delivery_workspace/.cursor/ai-sdlc/workspace-context.md",
                    "food_delivery_workspace/requirement.md"
            );
        }
    }

    @Test
    void inspectSummaryCountsTopLevelFoldersWithoutDownloadingBodies() {
        List<S3WorkspaceService.ListedObject> objects = List.of(
                new S3WorkspaceService.ListedObject("food_delivery_workspace/.blink-workspace.json", 40),
                new S3WorkspaceService.ListedObject("food_delivery_workspace/requirement.md", 20),
                new S3WorkspaceService.ListedObject("food_delivery_workspace/automation_sdlc/README.md", 100),
                new S3WorkspaceService.ListedObject("food_delivery_workspace/automation_sdlc/Makefile", 50),
                new S3WorkspaceService.ListedObject("food_delivery_workspace/.cursor/ai-sdlc/workspace-context.md", 30)
        );

        var inventory = S3WorkspaceService.summarize(
                "blink-ai-dev",
                "food_delivery_workspace",
                "https://blink-ai-dev.s3-us-west-2.amazonaws.com/food_delivery_workspace/",
                "ready",
                objects
        );

        assertThat(inventory.exists()).isTrue();
        assertThat(inventory.objectCount()).isEqualTo(5);
        assertThat(inventory.totalBytes()).isEqualTo(240);
        assertThat(inventory.topLevel())
                .extracting(com.talentserv.blink.dto.WorkspaceInventoryResponse.TopLevel::name)
                .containsExactlyInAnyOrder("automation_sdlc", ".cursor", ".blink-workspace.json", "requirement.md");
        assertThat(inventory.topLevel().getFirst().name()).isEqualTo("automation_sdlc");
        assertThat(inventory.sampleFiles()).contains(
                "automation_sdlc/README.md",
                ".cursor/ai-sdlc/workspace-context.md"
        );
    }
}
