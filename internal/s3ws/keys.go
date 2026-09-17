package s3ws

import "strings"

// workspaceObjectKey maps a kit-relative path onto the Java S3 layout:
// kit files under automation_sdlc/, .cursor and requirement.md at the workspace root.
func workspaceObjectKey(folder, rel string) string {
	folder = strings.Trim(folder, "/")
	rel = strings.TrimPrefix(strings.ReplaceAll(rel, `\`, "/"), "/")
	if rel == "" {
		return folder
	}
	if rel == "requirement.md" ||
		rel == workspaceManifest ||
		rel == ".cursor" ||
		strings.HasPrefix(rel, ".cursor/") ||
		strings.HasPrefix(rel, "automation_sdlc/") {
		return folder + "/" + rel
	}
	return folder + "/automation_sdlc/" + rel
}
