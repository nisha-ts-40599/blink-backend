package workflow

import (
	"encoding/json"
	"testing"
)

func TestClassifyWorkTierNeverZero(t *testing.T) {
	if ClassifyWorkTier("") != 1 {
		t.Fatal("empty requirement must still be tier 1")
	}
	if ClassifyWorkTier("Athletes can log a daily walk.") != 1 {
		t.Fatal("plain wording is tier 1")
	}
	if ClassifyWorkTier("Add a REST API for walk logs") != 2 {
		t.Fatal("api wording is tier 2")
	}
	if ClassifyWorkTier("Store payment tokens and PII") != 3 {
		t.Fatal("payment/pii is tier 3")
	}
}

func TestNextRequiredGateSequence(t *testing.T) {
	tier2 := RequiredGates(2)
	if NextRequiredGate(tier2, nil) != "G-GROOM" {
		t.Fatal("first gate is always G-GROOM")
	}
	if NextRequiredGate(tier2, []string{"G-GROOM"}) != "G-PLAN" {
		t.Fatal("tier 2 must wait on G-PLAN")
	}
	if NextRequiredGate(tier2, []string{"G-GROOM", "G-PLAN"}) != "" {
		t.Fatal("tier 2 should implement after G-PLAN")
	}
	tier3 := RequiredGates(3)
	if NextRequiredGate(tier3, []string{"G-GROOM", "G-PLAN"}) != "G-SEC" {
		t.Fatal("tier 3 must wait on G-SEC")
	}
	if NextRequiredGate(RequiredGates(1), []string{"G-GROOM"}) != "" {
		t.Fatal("tier 1 implements after G-GROOM")
	}
}

func TestMergeReadinessState(t *testing.T) {
	if MergeReadinessState(true, "clean", "success") != "DRAFT_PR_OPEN" {
		t.Fatal("draft stays draft")
	}
	if MergeReadinessState(false, "dirty", "success") != "BLOCKED" {
		t.Fatal("dirty is blocked")
	}
	if MergeReadinessState(false, "clean", "failure") != "CI_FAILING" {
		t.Fatal("failed CI")
	}
	if MergeReadinessState(false, "clean", "success") != "READY_FOR_HUMAN_ATTESTATION" {
		t.Fatal("clean ready PR")
	}
}

func TestMergeAttestPayloadDefaults(t *testing.T) {
	raw := MergeAttestPayload("https://github.com/acme/app/pull/1", "abc123", "feat", nil)
	var body map[string]any
	if err := json.Unmarshal(raw, &body); err != nil {
		t.Fatal(err)
	}
	if body["gate"] != "G-PR-MERGE" || body["registered_pr"] != "https://github.com/acme/app/pull/1" {
		t.Fatalf("payload=%s", raw)
	}
	if body["reviewed_commit"] != "abc123" || body["tested_commit"] != "abc123" {
		t.Fatalf("commits=%s", raw)
	}
}

func TestExtraHeadSHA(t *testing.T) {
	if ExtraHeadSHA(map[string]any{"headSha": "abc"}) != "abc" {
		t.Fatal("headSha")
	}
	if ExtraHeadSHA(map[string]any{"reviewed_commit": "def"}) != "def" {
		t.Fatal("reviewed_commit")
	}
}

func TestRequiredGates(t *testing.T) {
	if got := RequiredGates(1); len(got) != 2 || got[0] != "G-GROOM" || got[1] != "G-PR-MERGE" {
		t.Fatalf("tier 1 gates=%v", got)
	}
	if got := RequiredGates(2); len(got) != 3 {
		t.Fatalf("tier 2 gates=%v", got)
	}
	if got := RequiredGates(3); len(got) != 4 || got[3] != "G-SEC" {
		t.Fatalf("tier 3 gates=%v", got)
	}
}

func TestEnrichConfirmRiskyFillsContract(t *testing.T) {
	got := EnrichConfirmRisky(json.RawMessage(`{"conversationId":"c1"}`), "ignored")
	var body map[string]any
	if err := json.Unmarshal(got, &body); err != nil {
		t.Fatal(err)
	}
	if body["conversationId"] != "c1" {
		t.Fatalf("conversationId=%v", body["conversationId"])
	}
	if body["risk"] != "HIGH" {
		t.Fatalf("risk=%v", body["risk"])
	}
	if body["summary"] == nil || body["summary"] == "" {
		t.Fatal("summary required")
	}
	if _, ok := body["blocked_actions"]; !ok {
		t.Fatal("blocked_actions required")
	}
}
