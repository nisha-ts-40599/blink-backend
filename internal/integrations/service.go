package integrations

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
	"github.com/nisha-ts-40599/blink-backend/internal/config"
	"github.com/nisha-ts-40599/blink-backend/internal/crypto"
)

var (
	sourceEpicRe  = regexp.MustCompile(`(?m)^Source epic:\s*(\S+)\s*$`)
	sourceStoryRe = regexp.MustCompile(`(?m)^Source story:\s*(\S+)\s*$`)
	figmaTeamRe   = regexp.MustCompile(`(?:/files)?/team/(\d+)`)
)

type Service struct {
	pool *pgxpool.Pool
	cfg  config.Config
	box  *crypto.Box
	http *http.Client
}

func New(pool *pgxpool.Pool, cfg config.Config, box *crypto.Box) *Service {
	return &Service{
		pool: pool,
		cfg:  cfg,
		box:  box,
		http: &http.Client{Timeout: 45 * time.Second},
	}
}

type storedIntegration struct {
	ProjectID    int64
	Provider     string
	Account      string
	BaseURL      string
	Email        string
	Username     string
	Organization string
	Workspace    string
	ProjectKey   string
	ProjectName  string
	SpaceKey     string
	CloudID      string
	AuthType     string
	AccessToken  string
	RefreshToken string
	ExpiresAt    *time.Time
}

type jiraCtx struct {
	APIBase    string
	BrowseBase string
	Headers    map[string]string
}

// --- HTTP handlers ---

func (s *Service) Connect(w http.ResponseWriter, r *http.Request) {
	var req map[string]any
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	provider := strings.ToLower(strings.TrimSpace(str(req["provider"])))
	projectID := parseID(req["projectId"])
	token := str(req["token"])
	if provider == "" || token == "" {
		writeErr(w, http.StatusBadRequest, "provider and token are required.")
		return
	}
	account := firstNonEmpty(str(req["username"]), str(req["email"]), "Connected account")
	authType := "token"
	baseURL := str(req["baseUrl"])
	cloudID := ""
	var projects []any

	switch provider {
	case "jira", "confluence":
		if baseURL == "" {
			writeErr(w, http.StatusBadRequest, "baseUrl is required for Atlassian token connect.")
			return
		}
		email := str(req["email"])
		if email == "" {
			writeErr(w, http.StatusBadRequest, "email is required for Atlassian API token.")
			return
		}
		account = email
	case "github", "figma", "bitbucket":
		// token-only providers
	default:
		writeErr(w, http.StatusBadRequest, "Unsupported provider.")
		return
	}

	if err := s.persist(r.Context(), storedIntegration{
		ProjectID: projectID, Provider: provider, Account: account, BaseURL: baseURL,
		Email: str(req["email"]), Username: str(req["username"]), Organization: str(req["organization"]),
		Workspace: str(req["workspace"]), ProjectKey: str(req["projectKey"]), ProjectName: str(req["projectName"]),
		SpaceKey: str(req["spaceKey"]), CloudID: cloudID, AuthType: authType, AccessToken: token,
	}); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"connected": true, "provider": provider, "account": account,
		"detail": "Connected " + provider, "projectKey": str(req["projectKey"]),
		"projectName": str(req["projectName"]), "baseUrl": baseURL, "cloudId": cloudID,
		"authType": authType, "accessToken": nil, "projects": projects,
	})
}

func (s *Service) CreateRepositories(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Provider     string `json:"provider"`
		ProjectID    any    `json:"projectId"`
		Organization string `json:"organization"`
		Token        string `json:"token"`
		Repositories []struct {
			Name        string `json:"name"`
			Description string `json:"description"`
		} `json:"repositories"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if strings.ToLower(strings.TrimSpace(req.Provider)) != "github" {
		writeErr(w, http.StatusBadRequest, "Only GitHub repository creation is supported.")
		return
	}
	stored, _ := s.load(r.Context(), parseID(req.ProjectID), "github")
	token := firstNonEmpty(req.Token, stored.AccessToken)
	if token == "" {
		writeErr(w, http.StatusBadRequest, "Connect GitHub on Integrations first (sign in with GitHub).")
		return
	}
	org := firstNonEmpty(req.Organization, stored.Organization)
	endpoint := "https://api.github.com/user/repos"
	if org != "" {
		endpoint = "https://api.github.com/orgs/" + url.PathEscape(org) + "/repos"
	}
	headers := map[string]string{
		"Authorization": "Bearer " + token,
		"Accept":        "application/vnd.github+json",
		"Content-Type":  "application/json",
	}
	results := make([]map[string]any, 0, len(req.Repositories))
	for _, spec := range req.Repositories {
		name := strings.TrimSpace(spec.Name)
		if name == "" {
			continue
		}
		body, _ := json.Marshal(map[string]any{"name": name, "description": spec.Description, "private": true})
		status, resp, err := s.do(r.Context(), http.MethodPost, endpoint, headers, body)
		if err != nil {
			results = append(results, map[string]any{"name": name, "status": "failed", "message": err.Error()})
			continue
		}
		switch {
		case status == 422 && strings.Contains(strings.ToLower(resp), "already_exists"):
			results = append(results, map[string]any{"name": name, "status": "exists", "url": jsonText(resp, "html_url"), "message": "Repository already exists."})
		case status >= 200 && status < 300:
			results = append(results, map[string]any{"name": name, "status": "created", "url": jsonText(resp, "html_url"), "message": "Created"})
		default:
			results = append(results, map[string]any{"name": name, "status": "failed", "message": fmt.Sprintf("GitHub HTTP %d", status)})
		}
	}
	writeJSON(w, http.StatusOK, map[string]any{"provider": "github", "results": results})
}

func (s *Service) JiraOAuthURL(w http.ResponseWriter, r *http.Request) {
	_ = optionalAuth(r) // session optional
	clientID := strings.TrimSpace(s.cfg.JiraClientID)
	if clientID == "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"configured": false, "url": nil, "clientId": nil, "redirectUri": nil,
			"message": "Jira OAuth is not configured. Set BLINK_JIRA_CLIENT_ID and BLINK_JIRA_CLIENT_SECRET, or connect using an API token.",
		})
		return
	}
	redirect := s.resolveRedirect("jira", r.URL.Query().Get("redirectUri"), publicAPIBase(r))
	scopes := firstNonEmpty(s.cfg.JiraScopes, "read:jira-work write:jira-work delete:jira-work read:jira-user read:me offline_access")
	u := "https://auth.atlassian.com/authorize" +
		"?audience=api.atlassian.com" +
		"&client_id=" + url.QueryEscape(clientID) +
		"&scope=" + url.QueryEscape(scopes) +
		"&redirect_uri=" + url.QueryEscape(redirect) +
		"&state=" + url.QueryEscape(uuid.NewString()) +
		"&response_type=code&prompt=consent"
	writeJSON(w, http.StatusOK, map[string]any{
		"configured": true, "url": u, "clientId": clientID, "redirectUri": redirect,
		"message": "Ready for Atlassian authorization.",
	})
}

func (s *Service) JiraOAuthExchange(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code        string `json:"code"`
		RedirectURI string `json:"redirectUri"`
		ProjectID   any    `json:"projectId"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	clientID := strings.TrimSpace(s.cfg.JiraClientID)
	clientSecret := strings.TrimSpace(s.cfg.JiraClientSecret)
	if clientID == "" || clientSecret == "" {
		writeErr(w, http.StatusBadRequest, "Jira OAuth credentials (client ID/secret) are not configured on the server.")
		return
	}
	code := strings.TrimSpace(req.Code)
	if code == "" {
		writeErr(w, http.StatusBadRequest, "Authorization code is required.")
		return
	}
	redirect := s.resolveRedirect("jira", req.RedirectURI, "")
	tokenBody, _ := json.Marshal(map[string]string{
		"grant_type": "authorization_code", "client_id": clientID, "client_secret": clientSecret,
		"code": code, "redirect_uri": redirect,
	})
	status, body, err := s.do(r.Context(), http.MethodPost, "https://auth.atlassian.com/oauth/token",
		map[string]string{"Accept": "application/json", "Content-Type": "application/json"}, tokenBody)
	if err != nil || status < 200 || status >= 300 {
		writeErr(w, http.StatusUnauthorized, "Failed to exchange Atlassian code: "+firstNonEmpty(jsonText(body, "error_description"), jsonText(body, "error"), fmt.Sprintf("HTTP %d", status)))
		return
	}
	access := jsonText(body, "access_token")
	refresh := jsonText(body, "refresh_token")
	if access == "" {
		writeErr(w, http.StatusUnauthorized, "Atlassian response did not contain an access token.")
		return
	}
	var expiresAt *time.Time
	if n := jsonInt(body, "expires_in"); n > 0 {
		t := time.Now().UTC().Add(time.Duration(n) * time.Second)
		expiresAt = &t
	}
	_, resBody, _ := s.do(r.Context(), http.MethodGet, "https://api.atlassian.com/oauth/token/accessible-resources",
		map[string]string{"Authorization": "Bearer " + access, "Accept": "application/json"}, nil)
	cloudID, siteURL, siteName := "", "", ""
	var arr []map[string]any
	_ = json.Unmarshal([]byte(resBody), &arr)
	if len(arr) > 0 {
		cloudID = str(arr[0]["id"])
		siteURL = str(arr[0]["url"])
		siteName = str(arr[0]["name"])
	}
	if cloudID == "" {
		writeErr(w, http.StatusBadRequest, "No accessible Jira Cloud sites found for this Atlassian account.")
		return
	}
	account := "Atlassian User"
	_, meBody, _ := s.do(r.Context(), http.MethodGet,
		"https://api.atlassian.com/ex/jira/"+url.PathEscape(cloudID)+"/rest/api/3/myself",
		map[string]string{"Authorization": "Bearer " + access}, nil)
	if n := firstNonEmpty(jsonText(meBody, "displayName"), jsonText(meBody, "emailAddress")); n != "" {
		account = n
	}
	projects := s.fetchJiraProjects(r.Context(), jiraCtx{
		APIBase: "https://api.atlassian.com/ex/jira/" + cloudID,
		Headers: map[string]string{"Authorization": "Bearer " + access, "Accept": "application/json"},
	})
	projectKey, projectName := "", ""
	if len(projects) > 0 {
		projectKey = str(projects[0]["key"])
		projectName = str(projects[0]["name"])
	}
	_ = s.persist(r.Context(), storedIntegration{
		ProjectID: parseID(req.ProjectID), Provider: "jira", Account: account, BaseURL: siteURL,
		ProjectKey: projectKey, ProjectName: projectName, CloudID: cloudID, AuthType: "oauth",
		AccessToken: access, RefreshToken: refresh, ExpiresAt: expiresAt,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"connected": true, "provider": "jira", "account": account,
		"detail": "Connected with Atlassian OAuth as " + account + " to " + firstNonEmpty(siteName, siteURL),
		"projectKey": projectKey, "projectName": projectName, "baseUrl": siteURL, "cloudId": cloudID,
		"authType": "oauth", "accessToken": nil, "projects": projects,
	})
}

func (s *Service) GitHubOAuthURL(w http.ResponseWriter, r *http.Request) {
	_ = optionalAuth(r)
	clientID := strings.TrimSpace(s.cfg.GitHubClientID)
	if clientID == "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"configured": false, "url": nil, "clientId": nil, "redirectUri": nil,
			"message": "GitHub OAuth is not configured. Set BLINK_GITHUB_CLIENT_ID and BLINK_GITHUB_CLIENT_SECRET on the server.",
		})
		return
	}
	redirect := s.resolveRedirect("github", r.URL.Query().Get("redirectUri"), publicAPIBase(r))
	scopes := firstNonEmpty(s.cfg.GitHubScopes, "repo read:org user:email")
	u := "https://github.com/login/oauth/authorize" +
		"?client_id=" + url.QueryEscape(clientID) +
		"&redirect_uri=" + url.QueryEscape(redirect) +
		"&scope=" + url.QueryEscape(scopes) +
		"&state=" + url.QueryEscape(uuid.NewString()) +
		"&allow_signup=false"
	writeJSON(w, http.StatusOK, map[string]any{
		"configured": true, "url": u, "clientId": clientID, "redirectUri": redirect,
		"message": "Ready for GitHub authorization.",
	})
}

func (s *Service) GitHubOAuthExchange(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code        string `json:"code"`
		RedirectURI string `json:"redirectUri"`
		ProjectID   any    `json:"projectId"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	clientID := strings.TrimSpace(s.cfg.GitHubClientID)
	clientSecret := strings.TrimSpace(s.cfg.GitHubClientSecret)
	if clientID == "" || clientSecret == "" {
		writeErr(w, http.StatusBadRequest, "GitHub OAuth credentials (client ID/secret) are not configured on the server.")
		return
	}
	code := strings.TrimSpace(req.Code)
	if code == "" {
		writeErr(w, http.StatusBadRequest, "Authorization code is required.")
		return
	}
	redirect := s.resolveRedirect("github", req.RedirectURI, "")
	form := url.Values{
		"client_id": {clientID}, "client_secret": {clientSecret},
		"code": {code}, "redirect_uri": {redirect},
	}
	status, body, err := s.do(r.Context(), http.MethodPost, "https://github.com/login/oauth/access_token",
		map[string]string{"Accept": "application/json", "Content-Type": "application/x-www-form-urlencoded"},
		[]byte(form.Encode()))
	if err != nil || status < 200 || status >= 300 {
		writeErr(w, http.StatusUnauthorized, "Failed to exchange GitHub code.")
		return
	}
	access := jsonText(body, "access_token")
	if access == "" {
		writeErr(w, http.StatusUnauthorized, firstNonEmpty(jsonText(body, "error_description"), "GitHub response did not contain an access token."))
		return
	}
	_, meBody, _ := s.do(r.Context(), http.MethodGet, "https://api.github.com/user",
		map[string]string{"Authorization": "Bearer " + access, "Accept": "application/vnd.github+json"}, nil)
	account := firstNonEmpty(jsonText(meBody, "login"), "GitHub User")
	_ = s.persist(r.Context(), storedIntegration{
		ProjectID: parseID(req.ProjectID), Provider: "github", Account: account,
		BaseURL: "https://github.com", Username: account, AuthType: "oauth", AccessToken: access,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"connected": true, "provider": "github", "account": account,
		"detail": "Connected with GitHub OAuth as " + account, "authType": "oauth", "accessToken": nil,
	})
}

func (s *Service) FigmaOAuthURL(w http.ResponseWriter, r *http.Request) {
	_ = optionalAuth(r)
	clientID := strings.TrimSpace(s.cfg.FigmaClientID)
	if clientID == "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"configured": false, "url": nil, "clientId": nil, "redirectUri": nil,
			"message": "Figma OAuth is not configured. Set BLINK_FIGMA_CLIENT_ID and BLINK_FIGMA_CLIENT_SECRET on the server.",
		})
		return
	}
	redirect := s.resolveRedirect("figma", r.URL.Query().Get("redirectUri"), publicAPIBase(r))
	scopes := firstNonEmpty(s.cfg.FigmaScopes,
		"current_user:read,file_comments:read,file_comments:write,file_content:read,file_dev_resources:read,file_dev_resources:write,file_metadata:read,file_versions:read")
	u := "https://www.figma.com/oauth" +
		"?client_id=" + url.QueryEscape(clientID) +
		"&redirect_uri=" + url.QueryEscape(redirect) +
		"&scope=" + url.QueryEscape(scopes) +
		"&state=" + url.QueryEscape(uuid.NewString()) +
		"&response_type=code"
	writeJSON(w, http.StatusOK, map[string]any{
		"configured": true, "url": u, "clientId": clientID, "redirectUri": redirect,
		"message": "Ready for Figma authorization.",
	})
}

func (s *Service) FigmaOAuthExchange(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Code         string `json:"code"`
		RedirectURI  string `json:"redirectUri"`
		ProjectID    any    `json:"projectId"`
		Organization string `json:"organization"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	clientID := strings.TrimSpace(s.cfg.FigmaClientID)
	clientSecret := strings.TrimSpace(s.cfg.FigmaClientSecret)
	if clientID == "" || clientSecret == "" {
		writeErr(w, http.StatusBadRequest, "Figma OAuth credentials are not configured on the server.")
		return
	}
	code := strings.TrimSpace(req.Code)
	if code == "" {
		writeErr(w, http.StatusBadRequest, "Authorization code is required.")
		return
	}
	redirect := s.resolveRedirect("figma", req.RedirectURI, "")
	form := url.Values{
		"client_id": {clientID}, "client_secret": {clientSecret},
		"redirect_uri": {redirect}, "code": {code}, "grant_type": {"authorization_code"},
	}
	status, body, err := s.do(r.Context(), http.MethodPost, "https://api.figma.com/v1/oauth/token",
		map[string]string{"Content-Type": "application/x-www-form-urlencoded"}, []byte(form.Encode()))
	if err != nil || status < 200 || status >= 300 {
		writeErr(w, http.StatusUnauthorized, "Failed to exchange Figma code.")
		return
	}
	access := jsonText(body, "access_token")
	refresh := jsonText(body, "refresh_token")
	if access == "" {
		writeErr(w, http.StatusUnauthorized, "Figma response did not contain an access token.")
		return
	}
	var expiresAt *time.Time
	if n := jsonInt(body, "expires_in"); n > 0 {
		t := time.Now().UTC().Add(time.Duration(n) * time.Second)
		expiresAt = &t
	}
	_, meBody, _ := s.do(r.Context(), http.MethodGet, "https://api.figma.com/v1/me",
		map[string]string{"Authorization": "Bearer " + access}, nil)
	account := firstNonEmpty(jsonText(meBody, "email"), jsonText(meBody, "handle"), "Figma User")
	org := firstNonEmpty(parseFigmaTeamID(req.Organization), req.Organization)
	_ = s.persist(r.Context(), storedIntegration{
		ProjectID: parseID(req.ProjectID), Provider: "figma", Account: account,
		BaseURL: "https://www.figma.com", Username: account, Organization: org,
		AuthType: "oauth", AccessToken: access, RefreshToken: refresh, ExpiresAt: expiresAt,
	})
	writeJSON(w, http.StatusOK, map[string]any{
		"connected": true, "provider": "figma", "account": account,
		"detail": "Connected with Figma OAuth as " + account, "organization": org,
		"authType": "oauth", "accessToken": nil,
	})
}

func (s *Service) JiraProjects(w http.ResponseWriter, r *http.Request) {
	var req map[string]any
	_ = readJSON(r, &req)
	ctxJ, err := s.resolveJira(r.Context(), parseID(req["projectId"]), req)
	if err != nil {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	writeJSON(w, http.StatusOK, s.fetchJiraProjects(r.Context(), ctxJ))
}

func (s *Service) GitHubOrgs(w http.ResponseWriter, r *http.Request) {
	var req map[string]any
	_ = readJSON(r, &req)
	stored, _ := s.load(r.Context(), parseID(req["projectId"]), "github")
	token := firstNonEmpty(str(req["token"]), stored.AccessToken)
	if token == "" {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	_, body, err := s.do(r.Context(), http.MethodGet, "https://api.github.com/user/orgs",
		map[string]string{"Authorization": "Bearer " + token, "Accept": "application/vnd.github+json"}, nil)
	if err != nil {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	var raw []map[string]any
	_ = json.Unmarshal([]byte(body), &raw)
	out := make([]map[string]any, 0, len(raw))
	for _, o := range raw {
		out = append(out, map[string]any{
			"id": str(o["id"]), "login": str(o["login"]), "name": firstNonEmpty(str(o["name"]), str(o["login"])),
			"avatarUrl": str(o["avatar_url"]),
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Service) FigmaTeams(w http.ResponseWriter, r *http.Request) {
	var req map[string]any
	_ = readJSON(r, &req)
	stored, _ := s.load(r.Context(), parseID(req["projectId"]), "figma")
	token := firstNonEmpty(str(req["token"]), stored.AccessToken)
	if token == "" {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	_, body, err := s.do(r.Context(), http.MethodGet, "https://api.figma.com/v1/teams",
		map[string]string{"Authorization": "Bearer " + token}, nil)
	if err != nil {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	var root map[string]any
	_ = json.Unmarshal([]byte(body), &root)
	teams, _ := root["teams"].([]any)
	out := make([]map[string]any, 0)
	for _, t := range teams {
		m, _ := t.(map[string]any)
		if m == nil {
			continue
		}
		out = append(out, map[string]any{
			"id": str(m["id"]), "login": str(m["id"]), "name": str(m["name"]), "avatarUrl": "",
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Service) FigmaProjects(w http.ResponseWriter, r *http.Request) {
	var req map[string]any
	_ = readJSON(r, &req)
	stored, _ := s.load(r.Context(), parseID(req["projectId"]), "figma")
	token := firstNonEmpty(str(req["token"]), stored.AccessToken)
	teamID := parseFigmaTeamID(firstNonEmpty(str(req["organization"]), stored.Organization))
	if token == "" || teamID == "" {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	_, body, err := s.do(r.Context(), http.MethodGet, "https://api.figma.com/v1/teams/"+url.PathEscape(teamID)+"/projects",
		map[string]string{"Authorization": "Bearer " + token}, nil)
	if err != nil {
		writeJSON(w, http.StatusOK, []any{})
		return
	}
	var root map[string]any
	_ = json.Unmarshal([]byte(body), &root)
	projects, _ := root["projects"].([]any)
	out := make([]map[string]any, 0)
	for _, p := range projects {
		m, _ := p.(map[string]any)
		if m == nil {
			continue
		}
		out = append(out, map[string]any{
			"id": str(m["id"]), "key": str(m["id"]), "name": str(m["name"]), "type": "figma", "avatar": "",
		})
	}
	writeJSON(w, http.StatusOK, out)
}

func (s *Service) CreateJiraIssues(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ProjectID   any              `json:"projectId"`
		ProjectKey  string           `json:"projectKey"`
		BaseURL     string           `json:"baseUrl"`
		Email       string           `json:"email"`
		Token       string           `json:"token"`
		CloudID     string           `json:"cloudId"`
		AccessToken string           `json:"accessToken"`
		Epics       []map[string]any `json:"epics"`
		Stories     []map[string]any `json:"stories"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	projectKey := strings.TrimSpace(req.ProjectKey)
	if projectKey == "" {
		writeErr(w, http.StatusBadRequest, "Jira project key is required.")
		return
	}
	if len(req.Epics) == 0 && len(req.Stories) == 0 {
		writeErr(w, http.StatusBadRequest, "Add at least one epic or story to create in Jira.")
		return
	}
	projectID := parseID(req.ProjectID)
	stored, _ := s.load(r.Context(), projectID, "jira")
	ctxJ, err := s.resolveJira(r.Context(), projectID, map[string]any{
		"baseUrl": req.BaseURL, "email": req.Email, "token": req.Token,
		"cloudId": req.CloudID, "accessToken": req.AccessToken,
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if projectID > 0 {
		stored.ProjectID = projectID
		stored.Provider = "jira"
		stored.ProjectKey = projectKey
		if stored.AccessToken != "" {
			_ = s.persist(r.Context(), stored)
		}
	}

	created := make([]map[string]any, 0)
	errors := make([]string, 0)
	epicKeys := map[string]string{}

	for _, epic := range req.Epics {
		title := strings.TrimSpace(str(epic["title"]))
		if title == "" {
			continue
		}
		id := str(epic["id"])
		desc := strings.TrimSpace(str(epic["objective"]))
		if id != "" {
			if desc != "" {
				desc += "\n"
			}
			desc += "Source epic: " + id
		}
		item, err := s.createJiraIssue(r.Context(), ctxJ, projectKey, "Epic", title, desc, "")
		if err != nil {
			errors = append(errors, title+": "+err.Error())
			created = append(created, map[string]any{"id": id, "jiraKey": nil, "url": nil, "type": "Epic", "status": "failed", "message": err.Error()})
			continue
		}
		item["id"] = id
		item["type"] = "Epic"
		created = append(created, item)
		if key := str(item["jiraKey"]); key != "" && id != "" {
			epicKeys[id] = key
		}
	}

	for _, story := range req.Stories {
		title := strings.TrimSpace(str(story["title"]))
		if title == "" {
			continue
		}
		id := str(story["id"])
		desc := buildStoryDescription(story)
		parent := ""
		if eid := str(story["epicId"]); eid != "" {
			parent = epicKeys[eid]
		}
		if parent == "" {
			parent = str(story["epicKey"])
		}
		item, err := s.createJiraIssue(r.Context(), ctxJ, projectKey, "Story", title, desc, parent)
		if err != nil {
			errors = append(errors, title+": "+err.Error())
			created = append(created, map[string]any{"id": id, "jiraKey": nil, "url": nil, "type": "Story", "status": "failed", "message": err.Error()})
			continue
		}
		item["id"] = id
		item["type"] = "Story"
		created = append(created, item)
	}

	ok := 0
	for _, c := range created {
		if str(c["status"]) == "created" {
			ok++
		}
	}
	status := "ok"
	if len(errors) > 0 {
		if ok > 0 {
			status = "partial"
		} else {
			status = "error"
		}
	}
	msg := fmt.Sprintf("%d issue(s) created in Jira project %s", ok, projectKey)
	if len(errors) > 0 {
		msg += fmt.Sprintf(" (%d failed).", len(errors))
	} else {
		msg += "."
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": status, "message": msg, "created": created, "errors": errors})
}

func (s *Service) CreateJiraComment(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ProjectID       any    `json:"projectId"`
		IssueKey        string `json:"issueKey"`
		Body            string `json:"body"`
		BlinkQuestionID string `json:"blinkQuestionId"`
		BaseURL         string `json:"baseUrl"`
		Email           string `json:"email"`
		Token           string `json:"token"`
		CloudID         string `json:"cloudId"`
		AccessToken     string `json:"accessToken"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	issueKey := strings.TrimSpace(req.IssueKey)
	bodyText := strings.TrimSpace(req.Body)
	if issueKey == "" || bodyText == "" {
		writeErr(w, http.StatusBadRequest, "Jira issue key and comment body are required.")
		return
	}
	qid := strings.TrimSpace(req.BlinkQuestionID)
	if qid != "" && !strings.Contains(bodyText, "blink-question:"+qid) {
		bodyText = "[blink-question:" + qid + "]\n" + bodyText
	}
	ctxJ, err := s.resolveJira(r.Context(), parseID(req.ProjectID), map[string]any{
		"baseUrl": req.BaseURL, "email": req.Email, "token": req.Token,
		"cloudId": req.CloudID, "accessToken": req.AccessToken,
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	payload, _ := json.Marshal(map[string]any{"body": adfDocument(bodyText)})
	status, resp, err := s.do(r.Context(), http.MethodPost,
		ctxJ.APIBase+"/rest/api/3/issue/"+url.PathEscape(issueKey)+"/comment",
		withJSON(ctxJ.Headers), payload)
	if err != nil || status < 200 || status >= 300 {
		writeErr(w, http.StatusBadGateway, firstNonEmpty(jsonText(resp, "message"), fmt.Sprintf("Jira returned HTTP %d.", status)))
		return
	}
	commentID := firstNonEmpty(jsonText(resp, "id"), fmt.Sprintf("%v", jsonRaw(resp, "id")))
	writeJSON(w, http.StatusOK, map[string]any{
		"status": "ok", "message": "Comment posted on " + issueKey,
		"issueKey": issueKey, "commentId": commentID, "blinkQuestionId": qid,
	})
}

func (s *Service) PollJiraComments(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ProjectID   any    `json:"projectId"`
		BaseURL     string `json:"baseUrl"`
		Email       string `json:"email"`
		Token       string `json:"token"`
		CloudID     string `json:"cloudId"`
		AccessToken string `json:"accessToken"`
		Items       []struct {
			IssueKey        string `json:"issueKey"`
			BlinkQuestionID string `json:"blinkQuestionId"`
		} `json:"items"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	if len(req.Items) == 0 {
		writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "message": "Nothing to poll.", "replies": []any{}})
		return
	}
	ctxJ, err := s.resolveJira(r.Context(), parseID(req.ProjectID), map[string]any{
		"baseUrl": req.BaseURL, "email": req.Email, "token": req.Token,
		"cloudId": req.CloudID, "accessToken": req.AccessToken,
	})
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	replies := make([]map[string]any, 0)
	for _, item := range req.Items {
		issueKey := strings.TrimSpace(item.IssueKey)
		qid := strings.TrimSpace(item.BlinkQuestionID)
		if issueKey == "" || qid == "" {
			continue
		}
		_, body, err := s.do(r.Context(), http.MethodGet,
			ctxJ.APIBase+"/rest/api/3/issue/"+url.PathEscape(issueKey)+"/comment",
			ctxJ.Headers, nil)
		if err != nil {
			continue
		}
		var root map[string]any
		_ = json.Unmarshal([]byte(body), &root)
		comments, _ := root["comments"].([]any)
		marker := "blink-question:" + qid
		foundMarker := false
		for _, c := range comments {
			cm, _ := c.(map[string]any)
			if cm == nil {
				continue
			}
			plain := adfToPlain(cm["body"])
			if strings.Contains(plain, marker) {
				foundMarker = true
				continue
			}
			if foundMarker && strings.TrimSpace(plain) != "" {
				replies = append(replies, map[string]any{
					"issueKey": issueKey, "blinkQuestionId": qid,
					"commentId": str(cm["id"]), "body": plain,
				})
				break
			}
		}
	}
	msg := "No replies yet."
	if len(replies) > 0 {
		msg = fmt.Sprintf("%d reply(ies) found.", len(replies))
	}
	writeJSON(w, http.StatusOK, map[string]any{"status": "ok", "message": msg, "replies": replies})
}

func (s *Service) Binding(w http.ResponseWriter, r *http.Request) {
	var req struct {
		ProjectID    any    `json:"projectId"`
		Provider     string `json:"provider"`
		ProjectKey   string `json:"projectKey"`
		ProjectName  string `json:"projectName"`
		Organization string `json:"organization"`
		SpaceKey     string `json:"spaceKey"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	projectID := parseID(req.ProjectID)
	provider := strings.ToLower(strings.TrimSpace(req.Provider))
	if projectID <= 0 || provider == "" {
		writeErr(w, http.StatusBadRequest, "Project id and provider are required.")
		return
	}
	stored, ok := s.load(r.Context(), projectID, provider)
	if !ok {
		writeErr(w, http.StatusBadRequest, "Connect "+provider+" before saving the project selection.")
		return
	}
	if k := strings.TrimSpace(req.ProjectKey); k != "" {
		stored.ProjectKey = k
	}
	if n := strings.TrimSpace(req.ProjectName); n != "" {
		stored.ProjectName = n
	}
	if provider == "github" || provider == "figma" {
		org := strings.TrimSpace(req.Organization)
		if provider == "figma" {
			org = parseFigmaTeamID(org)
		}
		if org != "" {
			stored.Organization = org
		}
	}
	if provider == "confluence" {
		if sk := strings.TrimSpace(req.SpaceKey); sk != "" {
			stored.SpaceKey = sk
		}
	}
	if err := s.persist(r.Context(), stored); err != nil {
		writeErr(w, http.StatusInternalServerError, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"connected": true, "provider": stored.Provider, "account": stored.Account,
		"detail": "Saved " + provider + " binding", "projectKey": stored.ProjectKey,
		"projectName": stored.ProjectName, "baseUrl": stored.BaseURL, "cloudId": stored.CloudID,
		"authType": stored.AuthType, "accessToken": nil, "organization": stored.Organization,
	})
}

func (s *Service) JiraOAuthCallback(w http.ResponseWriter, r *http.Request) {
	writeOAuthHTML(w, "Atlassian", "JIRA_OAUTH_RESPONSE", r)
}

func (s *Service) GitHubOAuthCallback(w http.ResponseWriter, r *http.Request) {
	writeOAuthHTML(w, "GitHub", "GITHUB_OAUTH_RESPONSE", r)
}

func (s *Service) FigmaOAuthCallback(w http.ResponseWriter, r *http.Request) {
	writeOAuthHTML(w, "Figma", "FIGMA_OAUTH_RESPONSE", r)
}

func (s *Service) ListBlinkIssues(w http.ResponseWriter, r *http.Request) {
	projectID := parseID(r.URL.Query().Get("projectId"))
	if projectID <= 0 {
		writeErr(w, http.StatusBadRequest, "Blink project id is required.")
		return
	}
	stored, ok := s.load(r.Context(), projectID, "jira")
	if !ok {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": false, "projectId": projectID, "projectKey": nil, "browseBase": nil,
			"issues": []any{}, "message": "Connect Jira for this Blink project first.",
		})
		return
	}
	projectKey := strings.TrimSpace(stored.ProjectKey)
	if projectKey == "" {
		writeJSON(w, http.StatusOK, map[string]any{
			"ok": false, "projectId": projectID, "projectKey": nil, "browseBase": nil,
			"issues": []any{}, "message": "Pick a Jira project on the integrations step before resetting issues.",
		})
		return
	}
	ctxJ, err := s.jiraFromStored(stored)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	issues := s.searchBlinkMarked(r.Context(), ctxJ, projectKey)
	msg := fmt.Sprintf("No Blink-marked issues in Jira project %s.", projectKey)
	if len(issues) > 0 {
		msg = fmt.Sprintf("%d Blink-marked issue(s) in %s.", len(issues), projectKey)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"ok": true, "projectId": projectID, "projectKey": projectKey,
		"browseBase": ctxJ.BrowseBase, "issues": issues, "message": msg,
	})
}

func (s *Service) DeleteBlinkIssue(w http.ResponseWriter, r *http.Request) {
	projectID := parseID(r.URL.Query().Get("projectId"))
	issueKey := strings.TrimSpace(chi.URLParam(r, "issueKey"))
	if projectID <= 0 || issueKey == "" {
		writeErr(w, http.StatusBadRequest, "Blink project id and Jira issue key are required.")
		return
	}
	stored, ok := s.load(r.Context(), projectID, "jira")
	if !ok || strings.TrimSpace(stored.ProjectKey) == "" {
		writeErr(w, http.StatusBadRequest, "Connect Jira and select a project for this Blink project first.")
		return
	}
	ctxJ, err := s.jiraFromStored(stored)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	projectKey := strings.TrimSpace(stored.ProjectKey)
	marked := s.fetchBlinkMarked(r.Context(), ctxJ, projectKey, issueKey)
	if marked == nil {
		writeErr(w, http.StatusBadRequest, "Issue is not a Blink-marked Jira item (missing Source epic:/Source story:).")
		return
	}
	if str(marked["sourceKind"]) == "epic" {
		foreign := s.unmarkedChildren(r.Context(), ctxJ, str(marked["key"]))
		if len(foreign) > 0 {
			writeErr(w, http.StatusConflict, "Epic "+str(marked["key"])+" still has non-Blink children: "+strings.Join(foreign, ", "))
			return
		}
		// delete marked children first
		for _, child := range s.listChildren(r.Context(), ctxJ, str(marked["key"])) {
			key := str(child["key"])
			plain := adfToPlain(child["fields"])
			if nested, ok := child["fields"].(map[string]any); ok {
				plain = adfToPlain(nested["description"])
			}
			if sourceEpicID(plain) != "" || sourceStoryID(plain) != "" {
				_ = s.deleteJiraIssue(r.Context(), ctxJ, key)
			}
		}
	}
	if err := s.deleteJiraIssue(r.Context(), ctxJ, str(marked["key"])); err != nil {
		writeErr(w, http.StatusBadGateway, err.Error())
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"deletedCount": 1, "skippedCount": 0, "deleted": []string{str(marked["key"])},
		"skipped": []string{}, "errors": []string{},
	})
}

func (s *Service) DeleteAllBlinkIssues(w http.ResponseWriter, r *http.Request) {
	projectID := parseID(r.URL.Query().Get("projectId"))
	if projectID <= 0 {
		writeErr(w, http.StatusBadRequest, "Blink project id is required.")
		return
	}
	stored, ok := s.load(r.Context(), projectID, "jira")
	if !ok || strings.TrimSpace(stored.ProjectKey) == "" {
		writeErr(w, http.StatusBadRequest, "Connect Jira and select a project for this Blink project first.")
		return
	}
	ctxJ, err := s.jiraFromStored(stored)
	if err != nil {
		writeErr(w, http.StatusBadRequest, err.Error())
		return
	}
	projectKey := strings.TrimSpace(stored.ProjectKey)
	issues := s.searchBlinkMarked(r.Context(), ctxJ, projectKey)
	deleted, skipped, errs := []string{}, []string{}, []string{}

	for _, issue := range issues {
		if str(issue["sourceKind"]) != "story" {
			continue
		}
		key := str(issue["key"])
		again := s.fetchBlinkMarked(r.Context(), ctxJ, projectKey, key)
		if again == nil {
			skipped = append(skipped, key+" (not Blink-marked)")
			continue
		}
		if err := s.deleteJiraIssue(r.Context(), ctxJ, key); err != nil {
			errs = append(errs, key+": "+err.Error())
			continue
		}
		deleted = append(deleted, key)
	}
	for _, issue := range issues {
		if str(issue["sourceKind"]) != "epic" {
			continue
		}
		key := str(issue["key"])
		again := s.fetchBlinkMarked(r.Context(), ctxJ, projectKey, key)
		if again == nil {
			skipped = append(skipped, key+" (not Blink-marked)")
			continue
		}
		if foreign := s.unmarkedChildren(r.Context(), ctxJ, key); len(foreign) > 0 {
			skipped = append(skipped, key+" (has non-Blink children)")
			continue
		}
		if err := s.deleteJiraIssue(r.Context(), ctxJ, key); err != nil {
			errs = append(errs, key+": "+err.Error())
			continue
		}
		deleted = append(deleted, key)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"deletedCount": len(deleted), "skippedCount": len(skipped),
		"deleted": deleted, "skipped": skipped, "errors": errs,
	})
}

// --- persistence ---

func (s *Service) persist(ctx context.Context, in storedIntegration) error {
	if in.ProjectID <= 0 || strings.TrimSpace(in.Provider) == "" {
		return nil
	}
	existing, _ := s.load(ctx, in.ProjectID, in.Provider)
	merged := mergeStored(existing, in)
	accessEnc, err := s.seal(merged.AccessToken)
	if err != nil {
		return err
	}
	refreshEnc, err := s.seal(merged.RefreshToken)
	if err != nil {
		return err
	}
	_, err = s.pool.Exec(ctx, `
		INSERT INTO project_integration (
			project_id, provider, account, base_url, email, username, organization, workspace,
			project_key, project_name, space_key, cloud_id, auth_type,
			access_token_enc, refresh_token_enc, expires_at, created_at, updated_at
		) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14,$15,$16,NOW(),NOW())
		ON CONFLICT (project_id, provider) DO UPDATE SET
			account=EXCLUDED.account, base_url=EXCLUDED.base_url, email=EXCLUDED.email,
			username=EXCLUDED.username, organization=EXCLUDED.organization, workspace=EXCLUDED.workspace,
			project_key=EXCLUDED.project_key, project_name=EXCLUDED.project_name, space_key=EXCLUDED.space_key,
			cloud_id=EXCLUDED.cloud_id, auth_type=EXCLUDED.auth_type,
			access_token_enc=EXCLUDED.access_token_enc, refresh_token_enc=EXCLUDED.refresh_token_enc,
			expires_at=EXCLUDED.expires_at, updated_at=NOW()
	`, merged.ProjectID, merged.Provider, nullStr(merged.Account), nullStr(merged.BaseURL), nullStr(merged.Email),
		nullStr(merged.Username), nullStr(merged.Organization), nullStr(merged.Workspace),
		nullStr(merged.ProjectKey), nullStr(merged.ProjectName), nullStr(merged.SpaceKey), nullStr(merged.CloudID),
		nullStr(merged.AuthType), nullStr(accessEnc), nullStr(refreshEnc), merged.ExpiresAt)
	return err
}

func (s *Service) load(ctx context.Context, projectID int64, provider string) (storedIntegration, bool) {
	var out storedIntegration
	if projectID <= 0 || provider == "" {
		return out, false
	}
	var accessEnc, refreshEnc *string
	err := s.pool.QueryRow(ctx, `
		SELECT project_id, provider, COALESCE(account,''), COALESCE(base_url,''), COALESCE(email,''),
			COALESCE(username,''), COALESCE(organization,''), COALESCE(workspace,''),
			COALESCE(project_key,''), COALESCE(project_name,''), COALESCE(space_key,''), COALESCE(cloud_id,''),
			COALESCE(auth_type,''), access_token_enc, refresh_token_enc, expires_at
		FROM project_integration WHERE project_id=$1 AND provider=$2
	`, projectID, strings.ToLower(provider)).Scan(
		&out.ProjectID, &out.Provider, &out.Account, &out.BaseURL, &out.Email,
		&out.Username, &out.Organization, &out.Workspace, &out.ProjectKey, &out.ProjectName,
		&out.SpaceKey, &out.CloudID, &out.AuthType, &accessEnc, &refreshEnc, &out.ExpiresAt,
	)
	if err != nil {
		if err == pgx.ErrNoRows {
			return out, false
		}
		return out, false
	}
	if accessEnc != nil {
		out.AccessToken, _ = s.open(*accessEnc)
	}
	if refreshEnc != nil {
		out.RefreshToken, _ = s.open(*refreshEnc)
	}
	return out, true
}

func (s *Service) seal(plain string) (string, error) {
	if strings.TrimSpace(plain) == "" {
		return "", nil
	}
	if s.box == nil {
		return plain, nil
	}
	return s.box.Seal(plain)
}

func (s *Service) open(enc string) (string, error) {
	if strings.TrimSpace(enc) == "" {
		return "", nil
	}
	if s.box == nil {
		return enc, nil
	}
	return s.box.Open(enc)
}

func mergeStored(existing, in storedIntegration) storedIntegration {
	if existing.ProjectID == 0 {
		in.Provider = strings.ToLower(strings.TrimSpace(in.Provider))
		return in
	}
	out := existing
	out.Provider = strings.ToLower(strings.TrimSpace(firstNonEmpty(in.Provider, existing.Provider)))
	out.Account = firstNonEmpty(in.Account, existing.Account)
	out.BaseURL = firstNonEmpty(in.BaseURL, existing.BaseURL)
	out.Email = firstNonEmpty(in.Email, existing.Email)
	out.Username = firstNonEmpty(in.Username, existing.Username)
	out.Organization = firstNonEmpty(in.Organization, existing.Organization)
	out.Workspace = firstNonEmpty(in.Workspace, existing.Workspace)
	out.ProjectKey = firstNonEmpty(in.ProjectKey, existing.ProjectKey)
	out.ProjectName = firstNonEmpty(in.ProjectName, existing.ProjectName)
	out.SpaceKey = firstNonEmpty(in.SpaceKey, existing.SpaceKey)
	out.CloudID = firstNonEmpty(in.CloudID, existing.CloudID)
	out.AuthType = firstNonEmpty(in.AuthType, existing.AuthType)
	out.AccessToken = firstNonEmpty(in.AccessToken, existing.AccessToken)
	out.RefreshToken = firstNonEmpty(in.RefreshToken, existing.RefreshToken)
	if in.ExpiresAt != nil {
		out.ExpiresAt = in.ExpiresAt
	}
	out.ProjectID = existing.ProjectID
	if in.ProjectID > 0 {
		out.ProjectID = in.ProjectID
	}
	return out
}

// --- Jira helpers ---

func (s *Service) resolveJira(ctx context.Context, projectID int64, req map[string]any) (jiraCtx, error) {
	stored, _ := s.load(ctx, projectID, "jira")
	cloudID := firstNonEmpty(str(req["cloudId"]), stored.CloudID)
	access := firstNonEmpty(str(req["accessToken"]))
	if access == "" && strings.EqualFold(stored.AuthType, "oauth") {
		access = stored.AccessToken
	}
	token := firstNonEmpty(str(req["token"]))
	if token == "" && !strings.EqualFold(stored.AuthType, "oauth") {
		token = stored.AccessToken
	}
	baseURL := firstNonEmpty(str(req["baseUrl"]), stored.BaseURL)
	email := firstNonEmpty(str(req["email"]), stored.Email)
	return s.buildJiraCtx(cloudID, access, baseURL, email, token)
}

func (s *Service) jiraFromStored(stored storedIntegration) (jiraCtx, error) {
	if strings.EqualFold(stored.AuthType, "oauth") {
		return s.buildJiraCtx(stored.CloudID, stored.AccessToken, stored.BaseURL, "", "")
	}
	return s.buildJiraCtx("", "", stored.BaseURL, stored.Email, stored.AccessToken)
}

func (s *Service) buildJiraCtx(cloudID, access, baseURL, email, token string) (jiraCtx, error) {
	if cloudID != "" && access != "" {
		api := "https://api.atlassian.com/ex/jira/" + cloudID
		browse := strings.TrimRight(baseURL, "/")
		if browse == "" {
			browse = api
		}
		return jiraCtx{
			APIBase: api, BrowseBase: browse,
			Headers: map[string]string{"Authorization": "Bearer " + access, "Accept": "application/json"},
		}, nil
	}
	if baseURL != "" && email != "" && token != "" {
		host := atlassianHost(baseURL)
		if host == "" {
			return jiraCtx{}, fmt.Errorf("Invalid Atlassian Cloud URL.")
		}
		api := "https://" + host
		auth := "Basic " + base64.StdEncoding.EncodeToString([]byte(email+":"+token))
		return jiraCtx{
			APIBase: api, BrowseBase: api,
			Headers: map[string]string{"Authorization": auth, "Accept": "application/json"},
		}, nil
	}
	return jiraCtx{}, fmt.Errorf("Connect Jira with OAuth or an API token before creating issues.")
}

func (s *Service) fetchJiraProjects(ctx context.Context, jctx jiraCtx) []map[string]any {
	_, body, err := s.do(ctx, http.MethodGet, jctx.APIBase+"/rest/api/3/project", jctx.Headers, nil)
	if err != nil {
		return []map[string]any{}
	}
	var arr []map[string]any
	_ = json.Unmarshal([]byte(body), &arr)
	out := make([]map[string]any, 0, len(arr))
	for _, item := range arr {
		key := str(item["key"])
		if key == "" {
			continue
		}
		avatar := ""
		if au, ok := item["avatarUrls"].(map[string]any); ok {
			avatar = str(au["48x48"])
		}
		out = append(out, map[string]any{
			"id": str(item["id"]), "key": key, "name": str(item["name"]),
			"type": str(item["projectTypeKey"]), "avatar": avatar,
		})
	}
	return out
}

func (s *Service) createJiraIssue(ctx context.Context, jctx jiraCtx, projectKey, issueType, summary, description, parentKey string) (map[string]any, error) {
	if len(summary) > 240 {
		summary = summary[:240]
	}
	fields := map[string]any{
		"project":   map[string]string{"key": projectKey},
		"summary":   summary,
		"issuetype": map[string]string{"name": issueType},
	}
	if strings.TrimSpace(description) != "" {
		fields["description"] = adfDocument(description)
	}
	if strings.TrimSpace(parentKey) != "" {
		fields["parent"] = map[string]string{"key": parentKey}
	}
	payload, _ := json.Marshal(map[string]any{"fields": fields})
	status, body, err := s.do(ctx, http.MethodPost, jctx.APIBase+"/rest/api/3/issue", withJSON(jctx.Headers), payload)
	if err != nil {
		return nil, err
	}
	if status < 200 || status >= 300 {
		return nil, fmt.Errorf("%s", firstNonEmpty(jsonText(body, "message"), fmt.Sprintf("Jira returned HTTP %d.", status)))
	}
	key := jsonText(body, "key")
	browse := strings.TrimRight(jctx.BrowseBase, "/") + "/browse/" + key
	return map[string]any{"jiraKey": key, "url": browse, "status": "created", "message": "Created " + key}, nil
}

func (s *Service) searchBlinkMarked(ctx context.Context, jctx jiraCtx, projectKey string) []map[string]any {
	jql := `project = ` + projectKey + ` AND (description ~ "Source epic:" OR description ~ "Source story:") ORDER BY key ASC`
	payload, _ := json.Marshal(map[string]any{
		"jql": jql, "maxResults": 100,
		"fields": []string{"summary", "description", "issuetype", "project"},
	})
	status, body, err := s.do(ctx, http.MethodPost, jctx.APIBase+"/rest/api/3/search/jql", withJSON(jctx.Headers), payload)
	if err != nil || status < 200 || status >= 300 {
		return []map[string]any{}
	}
	var root map[string]any
	_ = json.Unmarshal([]byte(body), &root)
	issues, _ := root["issues"].([]any)
	out := make([]map[string]any, 0)
	for _, raw := range issues {
		issue, _ := raw.(map[string]any)
		if m := toBlinkMarked(jctx, projectKey, issue); m != nil {
			out = append(out, m)
		}
	}
	return out
}

func (s *Service) fetchBlinkMarked(ctx context.Context, jctx jiraCtx, projectKey, issueKey string) map[string]any {
	_, body, err := s.do(ctx, http.MethodGet,
		jctx.APIBase+"/rest/api/3/issue/"+url.PathEscape(issueKey)+"?fields=summary,description,issuetype,project",
		jctx.Headers, nil)
	if err != nil {
		return nil
	}
	var issue map[string]any
	_ = json.Unmarshal([]byte(body), &issue)
	return toBlinkMarked(jctx, projectKey, issue)
}

func toBlinkMarked(jctx jiraCtx, projectKey string, issue map[string]any) map[string]any {
	if issue == nil {
		return nil
	}
	key := str(issue["key"])
	fields, _ := issue["fields"].(map[string]any)
	if fields == nil {
		return nil
	}
	proj, _ := fields["project"].(map[string]any)
	if key == "" || !strings.EqualFold(str(proj["key"]), projectKey) {
		return nil
	}
	plain := adfToPlain(fields["description"])
	epicID := sourceEpicID(plain)
	storyID := sourceStoryID(plain)
	itype, _ := fields["issuetype"].(map[string]any)
	typeName := str(itype["name"])
	summary := firstNonEmpty(str(fields["summary"]), key)
	urlStr := strings.TrimRight(jctx.BrowseBase, "/") + "/browse/" + key
	if epicID != "" && (strings.EqualFold(typeName, "Epic") || storyID == "") {
		return map[string]any{"key": key, "type": firstNonEmpty(typeName, "Epic"), "summary": summary, "sourceKind": "epic", "sourceId": epicID, "url": urlStr}
	}
	if storyID != "" {
		return map[string]any{"key": key, "type": firstNonEmpty(typeName, "Story"), "summary": summary, "sourceKind": "story", "sourceId": storyID, "url": urlStr}
	}
	return nil
}

func (s *Service) listChildren(ctx context.Context, jctx jiraCtx, epicKey string) []map[string]any {
	jql := `"Epic Link" = ` + epicKey + ` OR parent = ` + epicKey
	payload, _ := json.Marshal(map[string]any{
		"jql": jql, "maxResults": 100, "fields": []string{"summary", "description", "issuetype", "project"},
	})
	status, body, err := s.do(ctx, http.MethodPost, jctx.APIBase+"/rest/api/3/search/jql", withJSON(jctx.Headers), payload)
	if err != nil || status < 200 || status >= 300 {
		return nil
	}
	var root map[string]any
	_ = json.Unmarshal([]byte(body), &root)
	raw, _ := root["issues"].([]any)
	out := make([]map[string]any, 0, len(raw))
	for _, r := range raw {
		if m, ok := r.(map[string]any); ok {
			out = append(out, m)
		}
	}
	return out
}

func (s *Service) unmarkedChildren(ctx context.Context, jctx jiraCtx, epicKey string) []string {
	foreign := []string{}
	for _, child := range s.listChildren(ctx, jctx, epicKey) {
		key := str(child["key"])
		fields, _ := child["fields"].(map[string]any)
		plain := adfToPlain(nil)
		if fields != nil {
			plain = adfToPlain(fields["description"])
		}
		if sourceEpicID(plain) == "" && sourceStoryID(plain) == "" {
			foreign = append(foreign, key)
		}
	}
	return foreign
}

func (s *Service) deleteJiraIssue(ctx context.Context, jctx jiraCtx, issueKey string) error {
	status, body, err := s.do(ctx, http.MethodDelete,
		jctx.APIBase+"/rest/api/3/issue/"+url.PathEscape(issueKey), jctx.Headers, nil)
	if err != nil {
		return err
	}
	if status == 204 || (status >= 200 && status < 300) {
		return nil
	}
	if status == 403 {
		return fmt.Errorf("Jira refused to delete %s", issueKey)
	}
	return fmt.Errorf("%s", firstNonEmpty(jsonText(body, "message"), fmt.Sprintf("Jira returned HTTP %d.", status)))
}

func buildStoryDescription(story map[string]any) string {
	var b strings.Builder
	asA, iWant, soThat := str(story["asA"]), str(story["iWant"]), str(story["soThat"])
	if asA != "" && iWant != "" {
		b.WriteString("As a " + asA + ", I want " + iWant)
		if soThat != "" {
			b.WriteString(" so that " + soThat)
		}
		b.WriteByte('\n')
	} else if obj := strings.TrimSpace(str(story["objective"])); obj != "" {
		b.WriteString(obj)
		b.WriteByte('\n')
	}
	if ac, ok := story["acceptanceCriteria"].([]any); ok && len(ac) > 0 {
		b.WriteString("Acceptance criteria:")
		for _, item := range ac {
			line := strings.TrimSpace(fmt.Sprint(item))
			if line != "" {
				b.WriteString("\n- " + line)
			}
		}
	}
	if id := str(story["id"]); id != "" {
		if b.Len() > 0 {
			b.WriteByte('\n')
		}
		b.WriteString("Source story: " + id)
	}
	return b.String()
}

func adfDocument(text string) map[string]any {
	content := make([]any, 0)
	for _, line := range strings.Split(text, "\n") {
		if strings.TrimSpace(line) == "" {
			continue
		}
		content = append(content, map[string]any{
			"type": "paragraph",
			"content": []any{
				map[string]any{"type": "text", "text": line},
			},
		})
	}
	if len(content) == 0 {
		t := strings.TrimSpace(text)
		if t == "" {
			t = " "
		}
		content = append(content, map[string]any{
			"type": "paragraph",
			"content": []any{
				map[string]any{"type": "text", "text": t},
			},
		})
	}
	return map[string]any{"type": "doc", "version": 1, "content": content}
}

func adfToPlain(v any) string {
	switch t := v.(type) {
	case string:
		return t
	case map[string]any:
		var b strings.Builder
		walkADF(t, &b)
		return b.String()
	default:
		return ""
	}
}

func walkADF(node map[string]any, b *strings.Builder) {
	if text := str(node["text"]); text != "" {
		b.WriteString(text)
	}
	if typ := str(node["type"]); typ == "paragraph" || typ == "hardBreak" {
		if b.Len() > 0 && !strings.HasSuffix(b.String(), "\n") {
			b.WriteByte('\n')
		}
	}
	if arr, ok := node["content"].([]any); ok {
		for _, c := range arr {
			if m, ok := c.(map[string]any); ok {
				walkADF(m, b)
			}
		}
	}
}

func sourceEpicID(plain string) string {
	m := sourceEpicRe.FindStringSubmatch(plain)
	if len(m) > 1 {
		return m[1]
	}
	return ""
}

func sourceStoryID(plain string) string {
	m := sourceStoryRe.FindStringSubmatch(plain)
	if len(m) > 1 {
		return m[1]
	}
	return ""
}

// --- OAuth / HTTP helpers ---

func oauthConfigured(cfg config.Config, provider string) string {
	switch provider {
	case "jira":
		return cfg.JiraRedirectURI
	case "github":
		return cfg.GitHubRedirectURI
	case "figma":
		return cfg.FigmaRedirectURI
	default:
		return ""
	}
}

func publicAPIBase(r *http.Request) string {
	proto := firstNonEmpty(r.Header.Get("X-Forwarded-Proto"), "http")
	if i := strings.Index(proto, ","); i >= 0 {
		proto = strings.TrimSpace(proto[:i])
	}
	host := firstNonEmpty(r.Header.Get("X-Forwarded-Host"), r.Host)
	if i := strings.Index(host, ","); i >= 0 {
		host = strings.TrimSpace(host[:i])
	}
	if host == "" {
		return ""
	}
	return proto + "://" + host
}

func optionalAuth(r *http.Request) string {
	return strings.TrimSpace(r.Header.Get("Authorization"))
}

func (s *Service) do(ctx context.Context, method, rawURL string, headers map[string]string, body []byte) (int, string, error) {
	var reader io.Reader
	if body != nil {
		reader = bytes.NewReader(body)
	}
	req, err := http.NewRequestWithContext(ctx, method, rawURL, reader)
	if err != nil {
		return 0, "", err
	}
	for k, v := range headers {
		req.Header.Set(k, v)
	}
	res, err := s.http.Do(req)
	if err != nil {
		return 0, "", err
	}
	defer res.Body.Close()
	b, _ := io.ReadAll(io.LimitReader(res.Body, 4<<20))
	return res.StatusCode, string(b), nil
}

func withJSON(h map[string]string) map[string]string {
	out := map[string]string{}
	for k, v := range h {
		out[k] = v
	}
	out["Content-Type"] = "application/json"
	out["Accept"] = "application/json"
	return out
}

func writeOAuthHTML(w http.ResponseWriter, title, msgType string, r *http.Request) {
	code := jsEscape(r.URL.Query().Get("code"))
	state := jsEscape(r.URL.Query().Get("state"))
	errMsg := r.URL.Query().Get("error")
	if d := r.URL.Query().Get("error_description"); d != "" {
		if errMsg != "" {
			errMsg += ": " + d
		} else {
			errMsg = d
		}
	}
	errSafe := jsEscape(errMsg)
	closeDelay := "1500"
	if errSafe != "" {
		closeDelay = "8000"
	}
	html := fmt.Sprintf(`<!DOCTYPE html>
<html><head><title>%s Authorization</title>
<style>body{font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0;background:#0f172a;color:#f8fafc;text-align:center}
.card{padding:2rem;border-radius:12px;background:#1e293b;border:1px solid #334155;max-width:400px}h2{margin-top:0;color:#38bdf8}</style>
</head><body><div class="card"><h2>%s</h2><p id="msg">Completing authorization with Blink...</p></div>
<script>
const code="%s",state="%s",error="%s";
const title=error?"Authorization failed":"%s Connected";
document.querySelector('h2').innerText=title;
if(window.opener){window.opener.postMessage({type:'%s',code:code||null,state:state||null,error:error||null},'*');
document.getElementById('msg').innerText=error?error:'Closing popup window...';setTimeout(()=>window.close(),%s);}
else{document.getElementById('msg').innerText=error||'Authorization complete. You can close this window.';}
</script></body></html>`, title, title, code, state, errSafe, title, msgType, closeDelay)
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write([]byte(html))
}

func jsEscape(s string) string {
	s = strings.ReplaceAll(s, `\`, `\\`)
	s = strings.ReplaceAll(s, `"`, `\"`)
	s = strings.ReplaceAll(s, "'", `\'`)
	return s
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"message": msg})
}

func readJSON(r *http.Request, dest any) error {
	defer r.Body.Close()
	dec := json.NewDecoder(io.LimitReader(r.Body, 8<<20))
	if err := dec.Decode(dest); err != nil {
		return fmt.Errorf("invalid JSON: %w", err)
	}
	return nil
}

func str(v any) string {
	switch t := v.(type) {
	case nil:
		return ""
	case string:
		return t
	case float64:
		if t == float64(int64(t)) {
			return strconv.FormatInt(int64(t), 10)
		}
		return strconv.FormatFloat(t, 'f', -1, 64)
	case json.Number:
		return t.String()
	default:
		return fmt.Sprint(t)
	}
}

func parseID(v any) int64 {
	switch t := v.(type) {
	case nil:
		return 0
	case float64:
		return int64(t)
	case int64:
		return t
	case int:
		return int64(t)
	case string:
		n, _ := strconv.ParseInt(strings.TrimSpace(t), 10, 64)
		return n
	case json.Number:
		n, _ := t.Int64()
		return n
	default:
		n, _ := strconv.ParseInt(strings.TrimSpace(fmt.Sprint(t)), 10, 64)
		return n
	}
}

func firstNonEmpty(vals ...string) string {
	for _, v := range vals {
		if strings.TrimSpace(v) != "" {
			return strings.TrimSpace(v)
		}
	}
	return ""
}

func nullStr(s string) any {
	if strings.TrimSpace(s) == "" {
		return nil
	}
	return s
}

func jsonText(body string, keys ...string) string {
	var m map[string]any
	if err := json.Unmarshal([]byte(body), &m); err != nil {
		return ""
	}
	for _, k := range keys {
		if v := str(m[k]); v != "" && v != "<nil>" {
			return v
		}
	}
	return ""
}

func jsonInt(body, key string) int {
	var m map[string]any
	if err := json.Unmarshal([]byte(body), &m); err != nil {
		return 0
	}
	switch t := m[key].(type) {
	case float64:
		return int(t)
	case json.Number:
		n, _ := t.Int64()
		return int(n)
	default:
		return 0
	}
}

func jsonRaw(body, key string) any {
	var m map[string]any
	_ = json.Unmarshal([]byte(body), &m)
	return m[key]
}

func parseFigmaTeamID(v string) string {
	v = strings.TrimSpace(v)
	if v == "" {
		return ""
	}
	if regexp.MustCompile(`^\d+$`).MatchString(v) {
		return v
	}
	if m := figmaTeamRe.FindStringSubmatch(v); len(m) > 1 {
		return m[1]
	}
	return v
}

func atlassianHost(baseURL string) string {
	u, err := url.Parse(strings.TrimSpace(baseURL))
	if err != nil || u.Host == "" {
		if !strings.Contains(baseURL, "://") {
			u, err = url.Parse("https://" + strings.TrimSpace(baseURL))
		}
		if err != nil || u.Host == "" {
			return ""
		}
	}
	host := strings.ToLower(u.Hostname())
	if !strings.HasSuffix(host, ".atlassian.net") && host != "atlassian.net" {
		return ""
	}
	return u.Host
}
