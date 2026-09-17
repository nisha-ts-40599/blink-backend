package zipkit

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func writeKit(t *testing.T) string {
	t.Helper()
	kit := t.TempDir()
	if err := os.WriteFile(filepath.Join(kit, "Makefile"), []byte("all:\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.MkdirAll(filepath.Join(kit, "app"), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(kit, "app", "main.py"), []byte("print('ok')\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	cmdDir := filepath.Join(kit, ".cursor", "commands")
	if err := os.MkdirAll(cmdDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(cmdDir, "setup-new-workspace.md"), []byte("# setup\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	aiDir := filepath.Join(kit, ".cursor", "ai-sdlc")
	if err := os.MkdirAll(aiDir, 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(aiDir, "placeholder.yaml"), []byte("skip-me: true\n"), 0o644); err != nil {
		t.Fatal(err)
	}
	return kit
}

func TestListAutomationSdlcFilesSkipsCursor(t *testing.T) {
	kit := writeKit(t)
	files, err := ListAutomationSdlcFiles(kit)
	if err != nil {
		t.Fatal(err)
	}
	for _, rel := range files {
		if rel == ".cursor" || strings.HasPrefix(rel, ".cursor/") {
			t.Fatalf("automation_sdlc upload must not include %s", rel)
		}
	}
	found := false
	for _, rel := range files {
		if rel == "app/main.py" {
			found = true
		}
	}
	if !found {
		t.Fatalf("expected app/main.py, got %v", files)
	}
}

func TestListCursorCommandFiles(t *testing.T) {
	kit := writeKit(t)
	files, err := ListCursorCommandFiles(kit)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 || files[0] != ".cursor/commands/setup-new-workspace.md" {
		t.Fatalf("got %v", files)
	}
}
