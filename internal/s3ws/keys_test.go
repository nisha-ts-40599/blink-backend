package s3ws

import "testing"

func TestWorkspaceObjectKeyPlacesCursorAtRoot(t *testing.T) {
	folder := "fitoyo_42_workspace"
	got := workspaceObjectKey(folder, ".cursor/commands/setup-new-workspace.md")
	want := "fitoyo_42_workspace/.cursor/commands/setup-new-workspace.md"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestWorkspaceObjectKeyPlacesKitUnderAutomationSdlc(t *testing.T) {
	folder := "fitoyo_42_workspace"
	got := workspaceObjectKey(folder, "framework_prompts/setup-new-workspace.md")
	want := "fitoyo_42_workspace/automation_sdlc/framework_prompts/setup-new-workspace.md"
	if got != want {
		t.Fatalf("got %q want %q", got, want)
	}
}

func TestWorkspaceObjectKeyDoesNotNestCursorUnderKit(t *testing.T) {
	folder := "acme_7_workspace"
	wrong := folder + "/automation_sdlc/.cursor/commands/foo.md"
	got := workspaceObjectKey(folder, ".cursor/commands/foo.md")
	if got == wrong {
		t.Fatal("commands must not land under automation_sdlc/.cursor")
	}
	if got != folder+"/.cursor/commands/foo.md" {
		t.Fatalf("got %q", got)
	}
}

func TestWorkspaceObjectKeyRequirementAtRoot(t *testing.T) {
	got := workspaceObjectKey("demo_1_workspace", "requirement.md")
	if got != "demo_1_workspace/requirement.md" {
		t.Fatalf("got %q", got)
	}
}
