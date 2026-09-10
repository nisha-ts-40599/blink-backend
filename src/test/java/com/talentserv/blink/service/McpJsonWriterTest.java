package com.talentserv.blink.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class McpJsonWriterTest {

    @Test
    void alwaysIncludesFilesystemAndUsesWrapperWithWorkspaceFolder() {
        String json = McpJsonWriter.mcpJson(List.of());
        assertThat(json).contains("\"filesystem\"");
        assertThat(json).contains("\"command\": \"powershell.exe\"");
        assertThat(json).contains(McpJsonWriter.WINDOWS_WRAPPER);
        assertThat(json).contains("${workspaceFolder}");
        assertThat(json).doesNotContain("\"github\"");
        assertThat(json).doesNotContain("ghp_");
        assertThat(json).doesNotContain("/Users/");
    }

    @Test
    void includesConnectedServersMatchingAiSdlcShape() {
        String json = McpJsonWriter.mcpJson(List.of("github", "jira", "confluence", "bitbucket"));
        assertThat(json).contains("\"github\"");
        assertThat(json).contains("\"jira\"");
        assertThat(json).contains("\"confluence\"");
        assertThat(json).doesNotContain("\"bitbucket\"");
        assertThat(json).contains("${env:GITHUB_PERSONAL_ACCESS_TOKEN}");
        assertThat(json).contains("${env:JIRA_API_TOKEN}");
        assertThat(json).contains("${env:CONFLUENCE_API_TOKEN}");
        assertThat(json).contains("mcp-atlassian");
        assertThat(json).contains("\"--yes\"");
        assertThat(json).contains(McpJsonWriter.WINDOWS_WRAPPER);
    }

    @Test
    void windowsProfileUsesPowershellWrapper() {
        String json = McpJsonWriter.mcpJson(List.of("github"), McpJsonWriter.OsProfile.WINDOWS);
        assertThat(json).contains("\"command\": \"powershell.exe\"");
        assertThat(json).contains(McpJsonWriter.WINDOWS_WRAPPER);
        assertThat(json).contains("${env:GITHUB_PERSONAL_ACCESS_TOKEN}");
        assertThat(McpJsonWriter.OsProfile.WINDOWS.fileName()).isEqualTo("mcp.windows.json");
        assertThat(McpJsonWriter.OsProfile.UNIX.fileName()).isEqualTo("mcp.unix.json");
    }

    @Test
    void setupReadmeExplainsWrapperAndEnvMcp() {
        String readme = McpJsonWriter.setupReadme();
        assertThat(readme).contains("mcp.windows.json", ".env.mcp", "mcp-npx.ps1", "mcp-npx.sh");
        assertThat(readme).contains("GITHUB_PERSONAL_ACCESS_TOKEN");
    }

    @Test
    void envExamplePrefillsSiteHintsWithoutSecrets() {
        String example = McpJsonWriter.envMcpExample(
                List.of("github", "jira", "confluence"),
                new McpJsonWriter.SiteHints(
                        "https://acme.atlassian.net",
                        "dev@acme.com",
                        "https://acme.atlassian.net",
                        "docs@acme.com"
                )
        );
        assertThat(example).contains("GITHUB_PERSONAL_ACCESS_TOKEN=");
        assertThat(example).contains("Blink login does not copy it");
        assertThat(example).contains("JIRA_URL=https://acme.atlassian.net");
        assertThat(example).contains("JIRA_USERNAME=dev@acme.com");
        assertThat(example).contains("JIRA_API_TOKEN=");
        assertThat(example).contains("CONFLUENCE_USERNAME=docs@acme.com");
        assertThat(example).doesNotContain("ghp_");
        assertThat(McpJsonWriter.requiredEnvVars(List.of("github")))
                .containsExactly("GITHUB_PERSONAL_ACCESS_TOKEN");
    }
}
