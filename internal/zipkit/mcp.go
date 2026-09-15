package zipkit

import (
	"strings"
)

const (
	unixWrapper    = "${workspaceFolder}/automation_sdlc/scripts/mcp-npx.sh"
	windowsWrapper = "${workspaceFolder}/automation_sdlc/scripts/mcp-npx.ps1"
)

// SiteHints are non-secret wizard values prefilling .env.mcp.example.
type SiteHints struct {
	JiraURL             string
	JiraUsername        string
	ConfluenceURL       string
	ConfluenceUsername  string
	JiraCloudID         string
}

type osProfile struct {
	command string
	prefix  []string
}

var (
	osPortable = osProfile{
		command: "powershell.exe",
		prefix:  []string{"-NoProfile", "-ExecutionPolicy", "Bypass", "-File", windowsWrapper},
	}
	osWindows = osPortable
	osUnix    = osProfile{command: unixWrapper, prefix: nil}
)

func mcpJSON(providers []string, profile osProfile) string {
	keys := []string{"filesystem"}
	set := normalizeProviders(providers)
	for _, p := range []string{"github", "figma", "jira", "confluence"} {
		if set[p] {
			keys = append(keys, p)
		}
	}
	var b strings.Builder
	b.WriteString("{\n  \"mcpServers\": {\n")
	for i, key := range keys {
		appendServer(&b, key, profile)
		if i < len(keys)-1 {
			b.WriteByte(',')
		}
		b.WriteByte('\n')
	}
	b.WriteString("  }\n}\n")
	return b.String()
}

func mcpSetupReadme() string {
	return `# Cursor MCP setup

Cursor loads ` + "`.cursor/mcp.json`" + `. The default file uses the **Windows** PowerShell
wrapper (` + "`mcp-npx.ps1`" + `). macOS/Linux users should switch to ` + "`mcp.unix.json`" + `.

The wrapper:

1. Resolves your unzip folder at runtime (no hard-coded machine paths)
2. Loads secrets from ` + "`automation_sdlc/.env.mcp`" + `
3. Maps ` + "`JIRA_*` → `ATLASSIAN_*`" + ` for mcp-atlassian when needed
4. Runs MCP packages via ` + "`npm exec`" + `

## Files

| Path | Purpose |
| --- | --- |
| ` + "`.cursor/mcp.json`" + ` | **Default = Windows** (PowerShell + ` + "`mcp-npx.ps1`" + `) |
| ` + "`.cursor/mcp.windows.json`" + ` | Same as default |
| ` + "`.cursor/mcp.unix.json`" + ` | macOS / Linux (` + "`mcp-npx.sh`" + `) |
| ` + "`automation_sdlc/env.mcp.example`" + ` | **Visible** copy (Cursor often hides ` + "`.env*`" + ` files) |
| ` + "`automation_sdlc/.env.mcp.example`" + ` | Same content — copy → ` + "`.env.mcp`" + ` and fill tokens |
| ` + "`automation_sdlc/scripts/mcp-npx.ps1`" + ` | Windows launcher |
| ` + "`automation_sdlc/scripts/mcp-npx.sh`" + ` | macOS / Linux launcher |

## First-time setup (2 minutes)

1. Unzip and **open the ` + "`*_workspace`" + ` folder** in Cursor (File → Open Folder).
2. Copy env file and fill **tokens** (URLs/emails may already be filled from Blink).
   GitHub MCP needs ` + "`GITHUB_PERSONAL_ACCESS_TOKEN`" + ` in ` + "`.env.mcp`" + ` — signing in on Blink
   does not write that token into the zip.

**Windows**
` + "```powershell" + `
Copy-Item automation_sdlc\env.mcp.example automation_sdlc\.env.mcp
notepad automation_sdlc\.env.mcp
` + "```" + `

**macOS / Linux**
` + "```bash" + `
cp .cursor/mcp.unix.json .cursor/mcp.json
cp automation_sdlc/env.mcp.example automation_sdlc/.env.mcp
# edit automation_sdlc/.env.mcp — add tokens
` + "```" + `

If the file tree hides dotfiles, look for ` + "`automation_sdlc/env.mcp.example`" + ` (no leading dot).

3. Restart Cursor (or reload MCP servers).
4. Confirm servers under **Cursor Settings → MCP**.

Requires **Node.js 18+** (` + "`node`" + ` / ` + "`npm`" + ` on PATH).

Never commit ` + "`automation_sdlc/.env.mcp`" + `.
`
}

func envMcpExample(providers []string, hints SiteHints) string {
	set := normalizeProviders(providers)
	var b strings.Builder
	b.WriteString("# AI-SDLC MCP env — copy to automation_sdlc/.env.mcp (never commit)\n")
	b.WriteString("# mcp-npx.sh / mcp-npx.ps1 load this file automatically.\n")
	b.WriteString("# Cursor mcp.json keeps secrets as ${env:VAR}; fill token values here.\n")
	b.WriteString("# Non-secret URLs/emails may be pre-filled from the Blink wizard.\n\n")
	if set["github"] {
		b.WriteString("# After unzip: paste a GitHub PAT here for Cursor MCP (Blink login does not copy it).\n")
		b.WriteString("GITHUB_PERSONAL_ACCESS_TOKEN=\n\n")
	}
	if set["figma"] {
		b.WriteString("# After unzip: paste a Figma PAT here for Cursor MCP (Blink login does not copy it).\n")
		b.WriteString("FIGMA_ACCESS_TOKEN=\n\n")
	}
	if set["jira"] {
		b.WriteString("JIRA_URL=" + valueOr(hints.JiraURL, "https://YOUR_ORG.atlassian.net") + "\n")
		b.WriteString("JIRA_USERNAME=" + valueOr(hints.JiraUsername, "you@example.com") + "\n")
		b.WriteString("JIRA_API_TOKEN=\n")
		b.WriteString("JIRA_CLOUD_ID=" + valueOr(hints.JiraCloudID, "") + "\n")
		b.WriteString("# mcp-atlassian also reads these (wrapper fills from JIRA_* if blank):\n")
		b.WriteString("ATLASSIAN_BASE_URL=" + valueOr(hints.JiraURL, "https://YOUR_ORG.atlassian.net") + "\n")
		b.WriteString("ATLASSIAN_SITE_URL=" + valueOr(hints.JiraURL, "https://YOUR_ORG.atlassian.net") + "\n")
		b.WriteString("ATLASSIAN_EMAIL=" + valueOr(hints.JiraUsername, "you@example.com") + "\n")
		b.WriteString("ATLASSIAN_API_TOKEN=\n\n")
	}
	if set["confluence"] {
		base := valueOr(hints.ConfluenceURL, "https://YOUR_ORG.atlassian.net")
		wiki := base
		if !strings.HasSuffix(base, "/wiki") {
			wiki = trimSlash(base) + "/wiki"
		}
		site := trimSlash(strings.TrimSuffix(strings.TrimSuffix(base, "/"), "/wiki"))
		b.WriteString("ATLASSIAN_BASE_URL=" + site + "\n")
		b.WriteString("ATLASSIAN_SITE_URL=" + site + "\n")
		b.WriteString("CONFLUENCE_URL=" + wiki + "\n")
		b.WriteString("CONFLUENCE_USERNAME=" + valueOr(hints.ConfluenceUsername, "you@example.com") + "\n")
		b.WriteString("CONFLUENCE_API_TOKEN=\n")
		b.WriteString("CONFLUENCE_CLOUD_ID=\n\n")
	}
	if !set["github"] && !set["figma"] && !set["jira"] && !set["confluence"] {
		b.WriteString("# No cloud MCP servers were connected in the wizard.\n")
		b.WriteString("# Connect GitHub / Figma / Jira / Confluence and re-download, or add servers manually.\n")
	}
	return b.String()
}

func normalizeProviders(connected []string) map[string]bool {
	out := map[string]bool{"filesystem": true}
	for _, raw := range connected {
		id := strings.ToLower(strings.TrimSpace(raw))
		switch id {
		case "github", "figma", "jira", "confluence":
			out[id] = true
		}
	}
	return out
}

func appendServer(b *strings.Builder, key string, os osProfile) {
	switch key {
	case "filesystem":
		b.WriteString("    \"filesystem\": {\n")
		b.WriteString("      \"command\": \"" + escapeJSON(os.command) + "\",\n")
		appendArgsStart(b, os, "-y", "@modelcontextprotocol/server-filesystem", "${workspaceFolder}")
		b.WriteString(",\n      \"env\": {}\n    }")
	case "github":
		b.WriteString("    \"github\": {\n")
		b.WriteString("      \"command\": \"" + escapeJSON(os.command) + "\",\n")
		appendArgsStart(b, os, "-y", "@modelcontextprotocol/server-github")
		b.WriteString(",\n      \"env\": {\n")
		b.WriteString("        \"GITHUB_PERSONAL_ACCESS_TOKEN\": \"${env:GITHUB_PERSONAL_ACCESS_TOKEN}\"\n")
		b.WriteString("      }\n    }")
	case "figma":
		b.WriteString("    \"figma\": {\n")
		b.WriteString("      \"command\": \"" + escapeJSON(os.command) + "\",\n")
		appendArgsStart(b, os, "-y", "@modelcontextprotocol/server-figma")
		b.WriteString(",\n      \"env\": {\n")
		b.WriteString("        \"FIGMA_ACCESS_TOKEN\": \"${env:FIGMA_ACCESS_TOKEN}\"\n")
		b.WriteString("      }\n    }")
	case "jira":
		b.WriteString("    \"jira\": {\n")
		b.WriteString("      \"command\": \"" + escapeJSON(os.command) + "\",\n")
		appendArgsStart(b, os, "--yes", "--package", "mcp-atlassian", "--package", "jsdom", "--", "mcp-atlassian")
		b.WriteString(",\n      \"env\": {\n")
		b.WriteString("        \"ATLASSIAN_BASE_URL\": \"${env:JIRA_URL}\",\n")
		b.WriteString("        \"ATLASSIAN_SITE_URL\": \"${env:JIRA_URL}\",\n")
		b.WriteString("        \"ATLASSIAN_EMAIL\": \"${env:JIRA_USERNAME}\",\n")
		b.WriteString("        \"ATLASSIAN_API_TOKEN\": \"${env:JIRA_API_TOKEN}\",\n")
		b.WriteString("        \"JIRA_URL\": \"${env:JIRA_URL}\",\n")
		b.WriteString("        \"JIRA_CLOUD_ID\": \"${env:JIRA_CLOUD_ID}\",\n")
		b.WriteString("        \"JIRA_USERNAME\": \"${env:JIRA_USERNAME}\",\n")
		b.WriteString("        \"JIRA_API_TOKEN\": \"${env:JIRA_API_TOKEN}\"\n")
		b.WriteString("      }\n    }")
	case "confluence":
		b.WriteString("    \"confluence\": {\n")
		b.WriteString("      \"command\": \"" + escapeJSON(os.command) + "\",\n")
		appendArgsStart(b, os, "--yes", "--package", "mcp-atlassian", "--package", "jsdom", "--", "mcp-atlassian")
		b.WriteString(",\n      \"env\": {\n")
		b.WriteString("        \"ATLASSIAN_BASE_URL\": \"${env:ATLASSIAN_BASE_URL}\",\n")
		b.WriteString("        \"ATLASSIAN_SITE_URL\": \"${env:ATLASSIAN_SITE_URL}\",\n")
		b.WriteString("        \"ATLASSIAN_EMAIL\": \"${env:CONFLUENCE_USERNAME}\",\n")
		b.WriteString("        \"ATLASSIAN_API_TOKEN\": \"${env:CONFLUENCE_API_TOKEN}\",\n")
		b.WriteString("        \"CONFLUENCE_URL\": \"${env:CONFLUENCE_URL}\",\n")
		b.WriteString("        \"CONFLUENCE_CLOUD_ID\": \"${env:CONFLUENCE_CLOUD_ID}\",\n")
		b.WriteString("        \"CONFLUENCE_USERNAME\": \"${env:CONFLUENCE_USERNAME}\",\n")
		b.WriteString("        \"CONFLUENCE_API_TOKEN\": \"${env:CONFLUENCE_API_TOKEN}\"\n")
		b.WriteString("      }\n    }")
	}
}

func appendArgsStart(b *strings.Builder, os osProfile, packageArgs ...string) {
	b.WriteString("      \"args\": [\n")
	for _, p := range os.prefix {
		b.WriteString("        \"" + escapeJSON(p) + "\",\n")
	}
	for i, a := range packageArgs {
		b.WriteString("        \"" + escapeJSON(a) + "\"")
		if i < len(packageArgs)-1 {
			b.WriteByte(',')
		}
		b.WriteByte('\n')
	}
	b.WriteString("      ]")
}

func valueOr(v, fallback string) string {
	if strings.TrimSpace(v) == "" {
		return fallback
	}
	return strings.TrimSpace(v)
}

func trimSlash(url string) string {
	v := strings.TrimSpace(url)
	for strings.HasSuffix(v, "/") {
		v = v[:len(v)-1]
	}
	return v
}

func escapeJSON(v string) string {
	return strings.ReplaceAll(strings.ReplaceAll(v, `\`, `\\`), `"`, `\"`)
}
