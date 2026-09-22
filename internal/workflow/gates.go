package workflow

import (
	"encoding/json"
	"strings"
)

// ClassifyWorkTier never returns 0. unknown_tier_default in sod-policy demands
// a second human when the tier is absent, so every run must have one.
func ClassifyWorkTier(requirement string) int {
	s := strings.ToLower(requirement)
	switch {
	case strings.Contains(s, "payment"), strings.Contains(s, "auth"), strings.Contains(s, "security"), strings.Contains(s, "pii"), strings.Contains(s, "hipaa"), strings.Contains(s, "gdpr"):
		return 3
	case strings.Contains(s, "database"), strings.Contains(s, "api"), strings.Contains(s, "migrat"), strings.Contains(s, "multi-repo"):
		return 2
	default:
		return 1
	}
}

// RequiredGates returns the always-on gates for a work tier. Deployment gates
// stay dormant because Blink ends at a merged/attested PR.
func RequiredGates(tier int) []string {
	switch {
	case tier >= 3:
		return []string{"G-GROOM", "G-PLAN", "G-PR-MERGE", "G-SEC"}
	case tier == 2:
		return []string{"G-GROOM", "G-PLAN", "G-PR-MERGE"}
	default:
		return []string{"G-GROOM", "G-PR-MERGE"}
	}
}

func HeadSHA(result json.RawMessage) string {
	if len(result) == 0 {
		return ""
	}
	var body map[string]any
	if json.Unmarshal(result, &body) != nil {
		return ""
	}
	for _, key := range []string{"headSha", "head_sha"} {
		if s, ok := body[key].(string); ok {
			if v := strings.TrimSpace(s); v != "" {
				return v
			}
		}
	}
	return ""
}

func PRURL(result json.RawMessage) string {
	if len(result) == 0 {
		return ""
	}
	var body map[string]any
	if json.Unmarshal(result, &body) != nil {
		return ""
	}
	for _, key := range []string{"prUrl", "pr_url"} {
		if s, ok := body[key].(string); ok {
			if v := strings.TrimSpace(s); v != "" {
				return v
			}
		}
	}
	return ""
}

var preImplementGates = []string{"G-GROOM", "G-PLAN", "G-SEC"}

func GateFromPayload(payload json.RawMessage) string {
	if len(payload) == 0 {
		return "G-GROOM"
	}
	var body map[string]any
	if json.Unmarshal(payload, &body) != nil {
		return "G-GROOM"
	}
	if s, ok := body["gate"].(string); ok {
		if g := strings.ToUpper(strings.TrimSpace(s)); g != "" {
			return g
		}
	}
	return "G-GROOM"
}

func RequiresGate(required []string, gate string) bool {
	if gate == "G-GROOM" {
		return true
	}
	for _, g := range required {
		if g == gate {
			return true
		}
	}
	return false
}

func NextRequiredGate(required, answered []string) string {
	done := map[string]bool{}
	for _, a := range answered {
		done[strings.ToUpper(strings.TrimSpace(a))] = true
	}
	for _, g := range preImplementGates {
		if !RequiresGate(required, g) {
			continue
		}
		if !done[g] {
			return g
		}
	}
	return ""
}

func StageForGate(gate string) string {
	switch gate {
	case "G-PLAN":
		return StagePlan
	case "G-SEC":
		return StageSecurity
	case "G-PR-MERGE":
		return StageMerge
	default:
		return StageRequirement
	}
}

func RoleForGate(gate string) string {
	switch gate {
	case "G-PLAN":
		return RoleTechLead
	case "G-SEC":
		return RoleSecurityChampion
	default:
		return RoleProductOwner
	}
}

func GateSummary(gate string) string {
	switch gate {
	case "G-PLAN":
		return "Approve the technical plan before implementation (G-PLAN)."
	case "G-SEC":
		return "Approve the security review before implementation (G-SEC)."
	case "G-PR-MERGE":
		return "Attest that you reviewed and tested this draft PR. Blink will not merge."
	default:
		return "Approve the requirement wording before implementation."
	}
}

func ExtraHeadSHA(extra map[string]any) string {
	if extra == nil {
		return ""
	}
	for _, key := range []string{"headSha", "head_sha", "reviewed_commit"} {
		if s, ok := extra[key].(string); ok {
			if v := strings.TrimSpace(s); v != "" {
				return v
			}
		}
	}
	return ""
}

func MergeReadinessState(draft bool, mergeableState, ciState string) string {
	ci := strings.ToLower(strings.TrimSpace(ciState))
	if ci == "failure" || ci == "error" {
		return "CI_FAILING"
	}
	switch strings.ToLower(strings.TrimSpace(mergeableState)) {
	case "dirty", "blocked", "behind":
		return "BLOCKED"
	}
	if draft {
		return "DRAFT_PR_OPEN"
	}
	return "READY_FOR_HUMAN_ATTESTATION"
}

func MergeAttestPayload(prURL, headSHA, branch string, extra map[string]any) json.RawMessage {
	body := map[string]any{
		"gate":                  "G-PR-MERGE",
		"prUrl":                 prURL,
		"headSha":               headSHA,
		"branch":                branch,
		"registered_pr":         prURL,
		"reviewed_commit":       headSHA,
		"tested_commit":         headSHA,
		"merge_readiness_state": "DRAFT_PR_OPEN",
		"summary":               GateSummary("G-PR-MERGE"),
	}
	for key, value := range extra {
		if value != nil {
			body[key] = value
		}
	}
	raw, _ := json.Marshal(body)
	return raw
}

func DecodeGates(raw []byte) []string {
	if len(raw) == 0 {
		return nil
	}
	var gates []string
	if json.Unmarshal(raw, &gates) != nil {
		return nil
	}
	return gates
}

// EnrichConfirmRisky keeps runner-supplied fields and fills the inbox contract:
// conversationId, risk HIGH, summary, and blocked_actions when missing.
func EnrichConfirmRisky(payload json.RawMessage, conversationID string) json.RawMessage {
	body := map[string]any{}
	if len(payload) > 0 {
		_ = json.Unmarshal(payload, &body)
	}
	if conversationID != "" {
		if _, ok := body["conversationId"]; !ok {
			if _, ok := body["conversation_id"]; !ok {
				body["conversationId"] = conversationID
			}
		}
	}
	if _, ok := body["risk"]; !ok {
		body["risk"] = "HIGH"
	}
	if _, ok := body["summary"]; !ok {
		body["summary"] = "OpenHands is waiting for confirmation of a risky action."
	}
	if _, ok := body["blocked_actions"]; !ok {
		if _, ok := body["blockedActions"]; !ok {
			body["blocked_actions"] = []any{}
		}
	}
	out, err := json.Marshal(body)
	if err != nil {
		return payload
	}
	return out
}
