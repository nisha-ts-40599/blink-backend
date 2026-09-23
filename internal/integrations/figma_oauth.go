package integrations

import "strings"

// Conservative scopes that new/private Figma OAuth apps accept after the 2025
// granular-scope migration. Requesting webhooks:write returns
// {"message":"Invalid scopes for app"} unless that scope is enabled on the
// Figma app, and Starter teams cannot create webhooks anyway.
const figmaDefaultScopes = "current_user:read,file_content:read,file_metadata:read"

func normalizeFigmaScopes(raw string) string {
	allowed := map[string]struct{}{
		"current_user:read":  {},
		"file_content:read":  {},
		"file_metadata:read": {},
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
	if len(out) == 0 {
		return figmaDefaultScopes
	}
	return strings.Join(out, ",")
}
