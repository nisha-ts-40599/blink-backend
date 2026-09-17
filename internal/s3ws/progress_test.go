package s3ws

import (
	"testing"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

func TestProgressDisabledS3(t *testing.T) {
	s := New(config.Config{})
	id := int64(42)
	out := s.Progress("Fitoyo", &id)
	if out["workspaceKey"] != "fitoyo_42_workspace" {
		t.Fatalf("workspaceKey=%v", out["workspaceKey"])
	}
	if out["status"] != nil {
		t.Fatalf("disabled S3 should not claim status, got %v", out["status"])
	}
	if out["percent"] != 0 || out["filesCopied"] != 0 {
		t.Fatalf("disabled S3 progress should be zero, got %v", out)
	}
}

func TestProgressUsesProjectId(t *testing.T) {
	s := New(config.Config{})
	id := int64(7)
	withID := s.Progress("Acme App", &id)
	without := s.Progress("Acme App", nil)
	if withID["workspaceKey"] == without["workspaceKey"] {
		t.Fatal("projectId must change the S3 folder key")
	}
	if withID["workspaceKey"] != "acme_app_7_workspace" {
		t.Fatalf("got %v", withID["workspaceKey"])
	}
}
