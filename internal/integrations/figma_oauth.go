package integrations

import "strings"

// Conservative scopes that new/private Figma OAuth apps accept after the 2025
// granular-scope migration. Requesting anything else (folders:read, projects:read,
// files:read, webhooks, …) returns {"message":"Invalid scopes for app"}.
const figmaDefaultScopes = "current_user:read,file_content:read,file_metadata:read,webhooks:write"

func normalizeFigmaScopes(raw string) string {
	allowed := map[string]struct{}{
		"current_user:read":  {},
		"file_content:read":  {},
		"file_metadata:read": {},
		"webhooks:read":      {},
		"webhooks:write":     {},
	}
	parts := strings.FieldsFunc(raw, func(r rune) bool {
		return r == ',' || r == ' ' || r == ';'
	})
	out := make([]string, 0, 3)
	seen := map[string]struct{}{}
	for _, part := range parts {
		part = strings.TrimSpace(part)
		if part == "" {
			continue
		}
		if _, ok := allowed[part]; !ok {
			continue
		}
		if _, dup := seen[part]; dup {
			continue
		}
		seen[part] = struct{}{}
		out = append(out, part)
	}
	if _, ok := seen["webhooks:write"]; !ok {
		out = append(out, "webhooks:write")
	}
	if len(out) == 0 {
		return figmaDefaultScopes
	}
	return strings.Join(out, ",")
}
