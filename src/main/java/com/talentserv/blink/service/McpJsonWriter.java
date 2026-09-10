package com.talentserv.blink.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds portable Cursor {@code .cursor/mcp.json} and {@code .env.mcp.example} for the download zip.
 * <p>
 * Shape matches AI-SDLC {@code mcp_generator.py}: launch via {@code automation_sdlc/scripts/mcp-npx.*}
 * so the wrapper resolves the extract folder and sources {@code .env.mcp}. Absolute developer-machine
 * paths are never baked in — Cursor expands {@code ${workspaceFolder}}. Secrets stay as
 * {@code ${env:VAR}} only; non-secret site URLs/emails may be prefilled in the example env file.
 */
public final class McpJsonWriter {

    /** Relative to the opened workspace root (the unzipped {@code *_workspace} folder). */
    public static final String UNIX_WRAPPER = "${workspaceFolder}/automation_sdlc/scripts/mcp-npx.sh";
    public static final String WINDOWS_WRAPPER = "${workspaceFolder}/automation_sdlc/scripts/mcp-npx.ps1";

    public enum OsProfile {
        /**
         * Default zip {@code mcp.json}: Windows PowerShell wrapper.
         * Most Blink pilot machines are Windows; macOS/Linux users copy {@code mcp.unix.json}.
         */
        PORTABLE("powershell.exe", List.of(
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                WINDOWS_WRAPPER
        )),
        /** Same as default — kept so docs/UI can name the Windows file explicitly. */
        WINDOWS("powershell.exe", List.of(
                "-NoProfile",
                "-ExecutionPolicy",
                "Bypass",
                "-File",
                WINDOWS_WRAPPER
        )),
        /** macOS / Linux bash wrapper. */
        UNIX(UNIX_WRAPPER, List.of());

        private final String command;
        private final List<String> commandPrefixArgs;

        OsProfile(String command, List<String> commandPrefixArgs) {
            this.command = command;
            this.commandPrefixArgs = List.copyOf(commandPrefixArgs);
        }

        public String command() {
            return command;
        }

        public List<String> commandPrefixArgs() {
            return commandPrefixArgs;
        }

        public String fileName() {
            return switch (this) {
                case PORTABLE -> "mcp.json";
                case WINDOWS -> "mcp.windows.json";
                case UNIX -> "mcp.unix.json";
            };
        }
    }

    /** Non-secret values from the Blink wizard used to prefill {@code .env.mcp.example}. */
    public record SiteHints(
            String jiraUrl,
            String jiraUsername,
            String confluenceUrl,
            String confluenceUsername,
            String jiraCloudId
    ) {
        public SiteHints(String jiraUrl, String jiraUsername, String confluenceUrl, String confluenceUsername) {
            this(jiraUrl, jiraUsername, confluenceUrl, confluenceUsername, null);
        }

        public static SiteHints empty() {
            return new SiteHints(null, null, null, null, null);
        }
    }

    private McpJsonWriter() {
    }

    public static String mcpJson(List<String> connectedProviders) {
        return mcpJson(connectedProviders, OsProfile.PORTABLE);
    }

    public static String mcpJson(List<String> connectedProviders, OsProfile profile) {
        OsProfile os = profile == null ? OsProfile.PORTABLE : profile;
        Set<String> providers = normalize(connectedProviders);
        StringBuilder json = new StringBuilder();
        json.append("{\n");
        json.append("  \"mcpServers\": {\n");
        List<String> keys = new ArrayList<>();
        keys.add("filesystem");
        if (providers.contains("github")) {
            keys.add("github");
        }
        if (providers.contains("jira")) {
            keys.add("jira");
        }
        if (providers.contains("confluence")) {
            keys.add("confluence");
        }
        for (int i = 0; i < keys.size(); i++) {
            appendServer(json, keys.get(i), os);
            if (i < keys.size() - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("  }\n");
        json.append("}\n");
        return json.toString();
    }

    public static String setupReadme() {
        return """
                # Cursor MCP setup

                Cursor loads `.cursor/mcp.json`. The default file uses the **Windows** PowerShell
                wrapper (`mcp-npx.ps1`). macOS/Linux users should switch to `mcp.unix.json`.

                The wrapper:

                1. Resolves your unzip folder at runtime (no hard-coded machine paths)
                2. Loads secrets from `automation_sdlc/.env.mcp`
                3. Maps `JIRA_*` → `ATLASSIAN_*` for mcp-atlassian when needed
                4. Runs MCP packages via `npm exec`

                ## Files

                | Path | Purpose |
                | --- | --- |
                | `.cursor/mcp.json` | **Default = Windows** (PowerShell + `mcp-npx.ps1`) |
                | `.cursor/mcp.windows.json` | Same as default |
                | `.cursor/mcp.unix.json` | macOS / Linux (`mcp-npx.sh`) |
                | `automation_sdlc/env.mcp.example` | **Visible** copy (Cursor often hides `.env*` files) |
                | `automation_sdlc/.env.mcp.example` | Same content — copy → `.env.mcp` and fill tokens |
                | `automation_sdlc/scripts/mcp-npx.ps1` | Windows launcher |
                | `automation_sdlc/scripts/mcp-npx.sh` | macOS / Linux launcher |

                ## First-time setup (2 minutes)

                1. Unzip and **open the `*_workspace` folder** in Cursor (File → Open Folder).
                2. Copy env file and fill **tokens** (URLs/emails may already be filled from Blink).
           GitHub MCP needs `GITHUB_PERSONAL_ACCESS_TOKEN` in `.env.mcp` — signing in on Blink
           does not write that token into the zip.

                **Windows**
                ```powershell
                Copy-Item automation_sdlc\\env.mcp.example automation_sdlc\\.env.mcp
                notepad automation_sdlc\\.env.mcp
                ```

                **macOS / Linux**
                ```bash
                cp .cursor/mcp.unix.json .cursor/mcp.json
                cp automation_sdlc/env.mcp.example automation_sdlc/.env.mcp
                # edit automation_sdlc/.env.mcp — add tokens
                ```

                If the file tree hides dotfiles, look for `automation_sdlc/env.mcp.example` (no leading dot).

                3. Restart Cursor (or reload MCP servers).
                4. Confirm servers under **Cursor Settings → MCP**.

                Requires **Node.js 18+** (`node` / `npm` on PATH).

                Never commit `automation_sdlc/.env.mcp`.
                """;
    }

    public static String envMcpExample(List<String> connectedProviders) {
        return envMcpExample(connectedProviders, SiteHints.empty());
    }

    public static String envMcpExample(List<String> connectedProviders, SiteHints hints) {
        SiteHints site = hints == null ? SiteHints.empty() : hints;
        Set<String> providers = normalize(connectedProviders);
        StringBuilder out = new StringBuilder();
        out.append("# AI-SDLC MCP env — copy to automation_sdlc/.env.mcp (never commit)\n");
        out.append("# mcp-npx.sh / mcp-npx.ps1 load this file automatically.\n");
        out.append("# Cursor mcp.json keeps secrets as ${env:VAR}; fill token values here.\n");
        out.append("# Non-secret URLs/emails may be pre-filled from the Blink wizard.\n\n");
        if (providers.contains("github")) {
            out.append("# After unzip: paste a GitHub PAT here for Cursor MCP (Blink login does not copy it).\n");
            out.append("GITHUB_PERSONAL_ACCESS_TOKEN=\n\n");
        }
        if (providers.contains("jira")) {
            out.append("JIRA_URL=").append(valueOr(site.jiraUrl(), "https://YOUR_ORG.atlassian.net")).append('\n');
            out.append("JIRA_USERNAME=").append(valueOr(site.jiraUsername(), "you@example.com")).append('\n');
            out.append("JIRA_API_TOKEN=\n");
            out.append("JIRA_CLOUD_ID=").append(valueOr(site.jiraCloudId(), "")).append('\n');
            out.append("# mcp-atlassian also reads these (wrapper fills from JIRA_* if blank):\n");
            out.append("ATLASSIAN_BASE_URL=").append(valueOr(site.jiraUrl(), "https://YOUR_ORG.atlassian.net")).append('\n');
            out.append("ATLASSIAN_SITE_URL=").append(valueOr(site.jiraUrl(), "https://YOUR_ORG.atlassian.net")).append('\n');
            out.append("ATLASSIAN_EMAIL=").append(valueOr(site.jiraUsername(), "you@example.com")).append('\n');
            out.append("ATLASSIAN_API_TOKEN=\n\n");
        }
        if (providers.contains("confluence")) {
            String base = valueOr(site.confluenceUrl(), "https://YOUR_ORG.atlassian.net");
            String wiki = base.endsWith("/wiki") ? base : trimSlash(base) + "/wiki";
            out.append("ATLASSIAN_BASE_URL=").append(trimSlash(base.replaceAll("/wiki/?$", ""))).append('\n');
            out.append("ATLASSIAN_SITE_URL=").append(trimSlash(base.replaceAll("/wiki/?$", ""))).append('\n');
            out.append("CONFLUENCE_URL=").append(wiki).append('\n');
            out.append("CONFLUENCE_USERNAME=")
                    .append(valueOr(site.confluenceUsername(), "you@example.com"))
                    .append('\n');
            out.append("CONFLUENCE_API_TOKEN=\n");
            out.append("CONFLUENCE_CLOUD_ID=\n\n");
        }
        if (!providers.contains("github") && !providers.contains("jira") && !providers.contains("confluence")) {
            out.append("# No cloud MCP servers were connected in the wizard.\n");
            out.append("# Connect GitHub / Jira / Confluence and re-download, or add servers manually.\n");
        }
        return out.toString();
    }

    public static List<String> requiredEnvVars(List<String> connectedProviders) {
        Set<String> providers = normalize(connectedProviders);
        List<String> names = new ArrayList<>();
        if (providers.contains("github")) {
            names.add("GITHUB_PERSONAL_ACCESS_TOKEN");
        }
        if (providers.contains("jira")) {
            names.add("JIRA_URL");
            names.add("JIRA_USERNAME");
            names.add("JIRA_API_TOKEN");
            names.add("JIRA_CLOUD_ID");
        }
        if (providers.contains("confluence")) {
            names.add("ATLASSIAN_BASE_URL");
            names.add("ATLASSIAN_SITE_URL");
            names.add("CONFLUENCE_URL");
            names.add("CONFLUENCE_USERNAME");
            names.add("CONFLUENCE_API_TOKEN");
            names.add("CONFLUENCE_CLOUD_ID");
        }
        return names;
    }

    private static Set<String> normalize(List<String> connectedProviders) {
        Set<String> out = new LinkedHashSet<>();
        out.add("filesystem");
        if (connectedProviders == null) {
            return out;
        }
        for (String raw : connectedProviders) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String id = raw.trim().toLowerCase(Locale.ROOT);
            if ("github".equals(id) || "jira".equals(id) || "confluence".equals(id)) {
                out.add(id);
            }
        }
        return out;
    }

    private static void appendServer(StringBuilder json, String key, OsProfile os) {
        switch (key) {
            case "filesystem" -> appendFilesystem(json, os);
            case "github" -> appendGithub(json, os);
            case "jira" -> appendJira(json, os);
            case "confluence" -> appendConfluence(json, os);
            default -> {
            }
        }
    }

    private static void appendArgsStart(StringBuilder json, OsProfile os, String... packageArgs) {
        json.append("      \"args\": [\n");
        for (String prefix : os.commandPrefixArgs()) {
            json.append("        \"").append(escape(prefix)).append("\",\n");
        }
        for (int i = 0; i < packageArgs.length; i++) {
            json.append("        \"").append(escape(packageArgs[i])).append('"');
            if (i < packageArgs.length - 1) {
                json.append(',');
            }
            json.append('\n');
        }
        json.append("      ]");
    }

    private static void appendFilesystem(StringBuilder json, OsProfile os) {
        json.append("    \"filesystem\": {\n");
        json.append("      \"command\": \"").append(escape(os.command())).append("\",\n");
        // Full workspace (repos + automation_sdlc), not a hard-coded absolute path.
        appendArgsStart(json, os, "-y", "@modelcontextprotocol/server-filesystem", "${workspaceFolder}");
        json.append(",\n");
        json.append("      \"env\": {}\n");
        json.append("    }");
    }

    private static void appendGithub(StringBuilder json, OsProfile os) {
        json.append("    \"github\": {\n");
        json.append("      \"command\": \"").append(escape(os.command())).append("\",\n");
        appendArgsStart(json, os, "-y", "@modelcontextprotocol/server-github");
        json.append(",\n");
        json.append("      \"env\": {\n");
        json.append("        \"GITHUB_PERSONAL_ACCESS_TOKEN\": \"${env:GITHUB_PERSONAL_ACCESS_TOKEN}\"\n");
        json.append("      }\n");
        json.append("    }");
    }

    private static void appendJira(StringBuilder json, OsProfile os) {
        json.append("    \"jira\": {\n");
        json.append("      \"command\": \"").append(escape(os.command())).append("\",\n");
        appendArgsStart(
                json,
                os,
                "--yes",
                "--package",
                "mcp-atlassian",
                "--package",
                "jsdom",
                "--",
                "mcp-atlassian"
        );
        json.append(",\n");
        json.append("      \"env\": {\n");
        json.append("        \"ATLASSIAN_BASE_URL\": \"${env:JIRA_URL}\",\n");
        json.append("        \"ATLASSIAN_SITE_URL\": \"${env:JIRA_URL}\",\n");
        json.append("        \"ATLASSIAN_EMAIL\": \"${env:JIRA_USERNAME}\",\n");
        json.append("        \"ATLASSIAN_API_TOKEN\": \"${env:JIRA_API_TOKEN}\",\n");
        json.append("        \"JIRA_URL\": \"${env:JIRA_URL}\",\n");
        json.append("        \"JIRA_CLOUD_ID\": \"${env:JIRA_CLOUD_ID}\",\n");
        json.append("        \"JIRA_USERNAME\": \"${env:JIRA_USERNAME}\",\n");
        json.append("        \"JIRA_API_TOKEN\": \"${env:JIRA_API_TOKEN}\"\n");
        json.append("      }\n");
        json.append("    }");
    }

    private static void appendConfluence(StringBuilder json, OsProfile os) {
        json.append("    \"confluence\": {\n");
        json.append("      \"command\": \"").append(escape(os.command())).append("\",\n");
        appendArgsStart(
                json,
                os,
                "--yes",
                "--package",
                "mcp-atlassian",
                "--package",
                "jsdom",
                "--",
                "mcp-atlassian"
        );
        json.append(",\n");
        json.append("      \"env\": {\n");
        json.append("        \"ATLASSIAN_BASE_URL\": \"${env:ATLASSIAN_BASE_URL}\",\n");
        json.append("        \"ATLASSIAN_SITE_URL\": \"${env:ATLASSIAN_SITE_URL}\",\n");
        json.append("        \"ATLASSIAN_EMAIL\": \"${env:CONFLUENCE_USERNAME}\",\n");
        json.append("        \"ATLASSIAN_API_TOKEN\": \"${env:CONFLUENCE_API_TOKEN}\",\n");
        json.append("        \"CONFLUENCE_URL\": \"${env:CONFLUENCE_URL}\",\n");
        json.append("        \"CONFLUENCE_CLOUD_ID\": \"${env:CONFLUENCE_CLOUD_ID}\",\n");
        json.append("        \"CONFLUENCE_USERNAME\": \"${env:CONFLUENCE_USERNAME}\",\n");
        json.append("        \"CONFLUENCE_API_TOKEN\": \"${env:CONFLUENCE_API_TOKEN}\"\n");
        json.append("      }\n");
        json.append("    }");
    }

    private static String valueOr(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String trimSlash(String url) {
        String v = url.trim();
        while (v.endsWith("/")) {
            v = v.substring(0, v.length() - 1);
        }
        return v;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
