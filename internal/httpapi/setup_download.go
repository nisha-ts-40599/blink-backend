package httpapi

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/nisha-ts-40599/blink-backend/internal/project"
	"github.com/nisha-ts-40599/blink-backend/internal/zipkit"
)

type setupAgentResponse struct {
	RunID              *string           `json:"runId"`
	Command            *string           `json:"command"`
	Status             *string           `json:"status"`
	ContextReady       *bool             `json:"contextReady"`
	DeliveryReady      *bool             `json:"deliveryReady"`
	GitWritten         *bool             `json:"gitWritten"`
	IdentitySource     *string           `json:"identitySource"`
	OverlayFiles       []overlayFileJSON `json:"overlayFiles"`
	AcceptedFileCount  int               `json:"acceptedFileCount"`
	NextCommand        *string           `json:"nextCommand"`
	Message            *string           `json:"message"`
	Errors             []string          `json:"errors"`
}

type overlayFileJSON struct {
	Path    string `json:"path"`
	Content string `json:"content"`
}

type setupAgentRequest struct {
	RequirementText string `json:"requirementText"`
	Mode            string `json:"mode"`
}

func (s *Server) setupProject(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	p, err := s.proj.RequireOwned(r.Context(), id, sessionEmail(r))
	if err != nil {
		writeErr(w, err)
		return
	}
	var req setupAgentRequest
	_ = readJSON(r, &req)
	mode := strings.ToLower(strings.TrimSpace(req.Mode))
	if mode == "" {
		mode = "apply"
	}
	if mode != "apply" {
		writeErr(w, fmt.Errorf("Only setup-new-workspace apply is hosted."))
		return
	}

	resp, _ := s.runSetup(r.Context(), p, strings.TrimSpace(req.RequirementText), nil)
	if s.s3.Enabled() {
		go s.s3.ProvisionAsync(context.Background(), p.ProjectName, &id)
	}
	writeJSON(w, http.StatusOK, resp)
}

func (s *Server) downloadProject(w http.ResponseWriter, r *http.Request) {
	id, err := pathID(r)
	if err != nil {
		writeErr(w, err)
		return
	}
	p, err := s.proj.RequireOwned(r.Context(), id, sessionEmail(r))
	if err != nil {
		writeErr(w, err)
		return
	}

	if err := r.ParseMultipartForm(32 << 20); err != nil {
		// Allow non-multipart with form values if Content-Type is form-urlencoded.
		if err2 := r.ParseForm(); err2 != nil {
			writeErr(w, fmt.Errorf("invalid multipart form: %w", err))
			return
		}
	}

	requirementsText := formValue(r, "requirementsText")
	setupContext := formValue(r, "setupContext")
	var fileName string
	var fileBytes []byte
	if f, hdr, err := r.FormFile("file"); err == nil {
		defer f.Close()
		fileName = hdr.Filename
		fileBytes, _ = io.ReadAll(io.LimitReader(f, 8<<20))
	}

	markdown, err := zipkit.ToMarkdown(p.ProjectName, fileName, fileBytes, requirementsText)
	if err != nil {
		writeErr(w, err)
		return
	}

	var blinkCtx map[string]any
	if strings.TrimSpace(setupContext) != "" {
		if err := json.Unmarshal([]byte(setupContext), &blinkCtx); err != nil || blinkCtx == nil {
			writeErr(w, fmt.Errorf("Setup context is invalid."))
			return
		}
	}

	setup, overlay := s.runSetup(r.Context(), p, markdown, blinkCtx)

	if s.s3.Enabled() {
		go s.s3.ProvisionAsync(context.Background(), p.ProjectName, &id)
	}

	repos := toRepoFolders(r.Form["repoName"], r.Form["repoPurpose"], r.Form["repoDescription"])
	mcpProviders := r.Form["mcpProvider"]
	hints := zipkit.SiteHints{
		JiraURL:            formValue(r, "mcpJiraUrl"),
		JiraUsername:       formValue(r, "mcpJiraEmail"),
		JiraCloudID:        formValue(r, "mcpJiraCloudId"),
		ConfluenceURL:      formValue(r, "mcpConfluenceUrl"),
		ConfluenceUsername: formValue(r, "mcpConfluenceEmail"),
	}

	root := project.Folder(p.ProjectName, &id)
	bundle, err := zipkit.PackageWorkspace(root, markdown, repos, overlay, mcpProviders, hints, s.cfg.AutomationSDLCPath)
	if err != nil {
		writeErr(w, fmt.Errorf("failed to package workspace: %w", err))
		return
	}

	nextCommand := zipkit.NextSDLCCommand
	if setup.NextCommand != nil && strings.TrimSpace(*setup.NextCommand) != "" {
		nextCommand = strings.TrimSpace(*setup.NextCommand)
	}
	if nextCommand != "" && !strings.HasPrefix(nextCommand, "/") {
		nextCommand = "/" + nextCommand
	}

	status := ""
	if setup.Status != nil {
		status = *setup.Status
	}
	identity := ""
	if setup.IdentitySource != nil {
		identity = *setup.IdentitySource
	}
	ctxReady := false
	if setup.ContextReady != nil {
		ctxReady = *setup.ContextReady
	}
	delivReady := false
	if setup.DeliveryReady != nil {
		delivReady = *setup.DeliveryReady
	}
	folderStatus := ""
	if s.s3.Enabled() {
		folderStatus = s.s3.FolderStatus(p.ProjectName, &id)
	}

	w.Header().Set("Content-Type", "application/zip")
	w.Header().Set("Content-Disposition", fmt.Sprintf(`attachment; filename="%s"`, bundle.Filename))
	w.Header().Set("Content-Length", strconv.Itoa(len(bundle.ZipBytes)))
	w.Header().Set("X-Blink-Workspace-Structure", zipkit.EncodeStructure(bundle.Structure))
	w.Header().Set("X-Blink-File-Count", strconv.Itoa(bundle.FileCount))
	w.Header().Set("X-Blink-Next-Command", nextCommand)
	w.Header().Set("X-Blink-Setup-Status", status)
	w.Header().Set("X-Blink-Identity-Source", identity)
	w.Header().Set("X-Blink-Overlay-Count", strconv.Itoa(len(overlay)))
	w.Header().Set("X-Blink-Setup-Validated", "true")
	w.Header().Set("X-Blink-Context-Ready", strconv.FormatBool(ctxReady))
	w.Header().Set("X-Blink-Delivery-Ready", strconv.FormatBool(delivReady))
	w.Header().Set("X-Blink-Folder-Status", folderStatus)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(bundle.ZipBytes)
}

func (s *Server) runSetup(ctx context.Context, p *project.ProjectResponse, requirementText string, blinkContext map[string]any) (setupAgentResponse, map[string]string) {
	payload := map[string]any{
		"command":     "setup-new-workspace",
		"mode":        "apply",
		"projectName": p.ProjectName,
		"projectId":   strconv.FormatInt(p.ID, 10),
	}
	if requirementText != "" {
		payload["requirementText"] = requirementText
	}
	if blinkContext != nil {
		payload["blinkContext"] = blinkContext
	}
	stakes := make([]map[string]string, 0, len(p.Stakeholders))
	for _, st := range p.Stakeholders {
		stakes = append(stakes, map[string]string{
			"role_id": st.RoleCode,
			"name":    st.Name,
			"email":   st.Email,
		})
	}
	payload["stakeholders"] = stakes

	raw, err := s.agent.SetupNewWorkspace(ctx, payload)
	if err != nil {
		return stubSetup(p, requirementText), stubOverlay(p, requirementText)
	}
	var loose map[string]any
	if err := json.Unmarshal(raw, &loose); err != nil {
		return stubSetup(p, requirementText), stubOverlay(p, requirementText)
	}
	status, _ := loose["status"].(string)
	if status != "ok" && status != "overlay_ready" {
		return stubSetup(p, requirementText), stubOverlay(p, requirementText)
	}
	resp := parseSetupResponse(loose)
	return resp, overlayMap(resp.OverlayFiles)
}

func parseSetupResponse(root map[string]any) setupAgentResponse {
	resp := setupAgentResponse{Errors: []string{}}
	resp.RunID = strPtr(root["runId"])
	resp.Command = strPtr(root["command"])
	resp.Status = strPtr(root["status"])
	resp.IdentitySource = strPtr(root["identitySource"])
	resp.NextCommand = strPtr(root["nextCommand"])
	resp.Message = strPtr(root["message"])
	resp.ContextReady = boolPtr(root["contextReady"])
	resp.DeliveryReady = boolPtr(root["deliveryReady"])
	resp.GitWritten = boolPtr(root["gitWritten"])
	if arr, ok := root["overlayFiles"].([]any); ok {
		for _, item := range arr {
			m, _ := item.(map[string]any)
			if m == nil {
				continue
			}
			path, _ := m["path"].(string)
			path = zipkit.SanitizeOverlayPath(path)
			if path == "" {
				continue
			}
			content, _ := m["content"].(string)
			resp.OverlayFiles = append(resp.OverlayFiles, overlayFileJSON{Path: path, Content: content})
		}
	}
	if n, ok := root["acceptedFileCount"].(float64); ok {
		resp.AcceptedFileCount = int(n)
	} else {
		resp.AcceptedFileCount = len(resp.OverlayFiles)
	}
	if arr, ok := root["errors"].([]any); ok {
		for _, e := range arr {
			if s, ok := e.(string); ok {
				resp.Errors = append(resp.Errors, s)
			}
		}
	}
	return resp
}

func stubSetup(p *project.ProjectResponse, requirementText string) setupAgentResponse {
	overlay := stubOverlay(p, requirementText)
	files := make([]overlayFileJSON, 0, len(overlay))
	for path, content := range overlay {
		files = append(files, overlayFileJSON{Path: path, Content: content})
	}
	status := "ok"
	cmd := "setup-new-workspace"
	next := zipkit.NextSDLCCommand
	src := "blink-go-stub"
	ctxReady := true
	delivReady := true
	msg := "Canonical setup agent unavailable; delivered Blink stub overlay."
	return setupAgentResponse{
		Command:           &cmd,
		Status:            &status,
		ContextReady:      &ctxReady,
		DeliveryReady:     &delivReady,
		IdentitySource:    &src,
		OverlayFiles:      files,
		AcceptedFileCount: len(files),
		NextCommand:       &next,
		Message:           &msg,
		Errors:            []string{},
	}
}

func stubOverlay(p *project.ProjectResponse, requirementText string) map[string]string {
	name := p.ProjectName
	req := strings.TrimSpace(requirementText)
	if req == "" {
		req = "# " + name + "\n"
	}
	return map[string]string{
		".cursor/ai-sdlc/setup/project-initialisation-input.yaml": fmt.Sprintf(
			"projectName: %q\nprojectId: %d\nsource: blink-go-stub\n", name, p.ID),
		".cursor/ai-sdlc/setup/setup-result.yaml": fmt.Sprintf(
			"status: ok\nprojectName: %q\nmessage: stub overlay from Blink Go backend\n", name),
		".cursor/ai-sdlc/setup/setup-response-contract.yaml": "version: 1\nstatus: ok\n",
		".cursor/ai-sdlc/governance/role-registry.yaml":      "roles: []\n",
		".cursor/ai-sdlc/greenfield-project-spec.yaml": fmt.Sprintf(
			"projectName: %q\ndescription: %q\n", name, p.Description),
		".cursor/ai-sdlc/intake/requirements/requirement-source-history.yaml": fmt.Sprintf(
			"sources:\n  - kind: blink-download\n    preview: |\n%s\n", indentBlock(req, 6)),
	}
}

func overlayMap(files []overlayFileJSON) map[string]string {
	out := make(map[string]string, len(files))
	for _, f := range files {
		path := zipkit.SanitizeOverlayPath(f.Path)
		if path == "" {
			continue
		}
		out[path] = f.Content
	}
	return out
}

func toRepoFolders(names, purposes, descriptions []string) []zipkit.RepoFolder {
	out := make([]zipkit.RepoFolder, 0, len(names))
	for i, name := range names {
		if strings.TrimSpace(name) == "" {
			continue
		}
		purpose, desc := "", ""
		if i < len(purposes) {
			purpose = purposes[i]
		}
		if i < len(descriptions) {
			desc = descriptions[i]
		}
		out = append(out, zipkit.RepoFolder{Name: name, Purpose: purpose, Description: desc})
	}
	return out
}

func formValue(r *http.Request, key string) string {
	if r.MultipartForm != nil && r.MultipartForm.Value != nil {
		if vals := r.MultipartForm.Value[key]; len(vals) > 0 {
			return vals[0]
		}
	}
	return r.FormValue(key)
}

func indentBlock(s string, spaces int) string {
	pad := strings.Repeat(" ", spaces)
	lines := strings.Split(s, "\n")
	for i, line := range lines {
		lines[i] = pad + line
	}
	return strings.Join(lines, "\n")
}

func strPtr(v any) *string {
	s, ok := v.(string)
	if !ok || strings.TrimSpace(s) == "" {
		return nil
	}
	s = strings.TrimSpace(s)
	return &s
}

func boolPtr(v any) *bool {
	b, ok := v.(bool)
	if !ok {
		return nil
	}
	return &b
}
