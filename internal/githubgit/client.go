// Package githubgit commits overlay trees and opens draft PRs via the GitHub Git Data API.
package githubgit

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Client struct {
	http    *http.Client
	token   string
	baseURL string
}

func New(token string) *Client {
	return &Client{
		http:    &http.Client{Timeout: 90 * time.Second},
		token:   strings.TrimSpace(token),
		baseURL: "https://api.github.com",
	}
}

type File struct {
	Path    string
	Content string
}

type CommitResult struct {
	SHA       string `json:"sha"`
	URL       string `json:"url"`
	Branch    string `json:"branch"`
	Owner     string `json:"owner"`
	Repo      string `json:"repo"`
	TreeCount int    `json:"treeCount"`
}

type DraftPRResult struct {
	URL    string `json:"url"`
	Number int    `json:"number"`
	Branch string `json:"branch"`
	Owner  string `json:"owner"`
	Repo   string `json:"repo"`
	SHA    string `json:"sha"`
}

func ParseOwnerRepo(htmlURLOrSlug string) (owner, repo string, err error) {
	raw := strings.TrimSpace(htmlURLOrSlug)
	if raw == "" {
		return "", "", fmt.Errorf("empty repository reference")
	}
	if strings.Contains(raw, "://") {
		u, perr := url.Parse(raw)
		if perr != nil {
			return "", "", perr
		}
		parts := strings.Split(strings.Trim(u.Path, "/"), "/")
		if len(parts) < 2 {
			return "", "", fmt.Errorf("cannot parse owner/repo from %s", raw)
		}
		return parts[0], strings.TrimSuffix(parts[1], ".git"), nil
	}
	parts := strings.Split(raw, "/")
	if len(parts) == 2 {
		return parts[0], parts[1], nil
	}
	return "", "", fmt.Errorf("expected owner/repo or html_url, got %q", raw)
}

func (c *Client) do(ctx context.Context, method, path string, body any) (int, []byte, error) {
	if c.token == "" {
		return 0, nil, fmt.Errorf("GitHub token is empty")
	}
	var rdr io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return 0, nil, err
		}
		rdr = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, c.baseURL+path, rdr)
	if err != nil {
		return 0, nil, err
	}
	req.Header.Set("Authorization", "Bearer "+c.token)
	req.Header.Set("Accept", "application/vnd.github+json")
	req.Header.Set("X-GitHub-Api-Version", "2022-11-28")
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := c.http.Do(req)
	if err != nil {
		return 0, nil, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	return res.StatusCode, raw, nil
}

func (c *Client) DefaultBranch(ctx context.Context, owner, repo string) (string, error) {
	status, raw, err := c.do(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s", owner, repo), nil)
	if err != nil {
		return "", err
	}
	if status >= 400 {
		return "", fmt.Errorf("GitHub repo lookup HTTP %d: %s", status, truncate(string(raw), 200))
	}
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	branch, _ := m["default_branch"].(string)
	if strings.TrimSpace(branch) == "" {
		return "", fmt.Errorf("repository has no default_branch")
	}
	return branch, nil
}

func (c *Client) CommitFiles(ctx context.Context, owner, repo, branch, message string, files []File) (*CommitResult, error) {
	if len(files) == 0 {
		return nil, fmt.Errorf("no files to commit")
	}
	if branch == "" {
		b, err := c.DefaultBranch(ctx, owner, repo)
		if err != nil {
			return nil, err
		}
		branch = b
	}
	refPath := fmt.Sprintf("/repos/%s/%s/git/ref/heads/%s", owner, repo, branch)
	status, raw, err := c.do(ctx, http.MethodGet, refPath, nil)
	if err != nil {
		return nil, err
	}
	baseSHA := ""
	if status == 200 {
		var ref map[string]any
		_ = json.Unmarshal(raw, &ref)
		if obj, ok := ref["object"].(map[string]any); ok {
			baseSHA, _ = obj["sha"].(string)
		}
	} else if status == 404 {
		// Empty / missing branch: create from scratch after blob/tree/commit.
		baseSHA = ""
	} else {
		return nil, fmt.Errorf("GitHub ref lookup HTTP %d: %s", status, truncate(string(raw), 200))
	}

	baseTreeSHA := ""
	if baseSHA != "" {
		status, raw, err = c.do(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s/git/commits/%s", owner, repo, baseSHA), nil)
		if err != nil {
			return nil, err
		}
		if status >= 400 {
			return nil, fmt.Errorf("GitHub commit lookup HTTP %d: %s", status, truncate(string(raw), 200))
		}
		var commit map[string]any
		_ = json.Unmarshal(raw, &commit)
		if tree, ok := commit["tree"].(map[string]any); ok {
			baseTreeSHA, _ = tree["sha"].(string)
		}
	}

	treeEntries := make([]map[string]any, 0, len(files))
	for _, f := range files {
		path := strings.TrimSpace(strings.TrimPrefix(f.Path, "/"))
		if path == "" || strings.Contains(path, "..") {
			return nil, fmt.Errorf("invalid path %q", f.Path)
		}
		status, raw, err = c.do(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/git/blobs", owner, repo), map[string]any{
			"content":  base64.StdEncoding.EncodeToString([]byte(f.Content)),
			"encoding": "base64",
		})
		if err != nil {
			return nil, err
		}
		if status >= 400 {
			return nil, fmt.Errorf("GitHub blob create HTTP %d for %s: %s", status, path, truncate(string(raw), 200))
		}
		var blob map[string]any
		_ = json.Unmarshal(raw, &blob)
		sha, _ := blob["sha"].(string)
		if sha == "" {
			return nil, fmt.Errorf("blob create returned empty sha for %s", path)
		}
		treeEntries = append(treeEntries, map[string]any{
			"path": path,
			"mode": "100644",
			"type": "blob",
			"sha":  sha,
		})
	}

	treeBody := map[string]any{"tree": treeEntries}
	if baseTreeSHA != "" {
		treeBody["base_tree"] = baseTreeSHA
	}
	status, raw, err = c.do(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/git/trees", owner, repo), treeBody)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("GitHub tree create HTTP %d: %s", status, truncate(string(raw), 200))
	}
	var tree map[string]any
	_ = json.Unmarshal(raw, &tree)
	treeSHA, _ := tree["sha"].(string)
	if treeSHA == "" {
		return nil, fmt.Errorf("tree create returned empty sha")
	}

	commitBody := map[string]any{
		"message": message,
		"tree":    treeSHA,
	}
	if baseSHA != "" {
		commitBody["parents"] = []string{baseSHA}
	}
	status, raw, err = c.do(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/git/commits", owner, repo), commitBody)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("GitHub commit create HTTP %d: %s", status, truncate(string(raw), 200))
	}
	var commit map[string]any
	_ = json.Unmarshal(raw, &commit)
	commitSHA, _ := commit["sha"].(string)
	if commitSHA == "" {
		return nil, fmt.Errorf("commit create returned empty sha")
	}
	htmlURL, _ := commit["html_url"].(string)

	if baseSHA == "" {
		status, raw, err = c.do(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/git/refs", owner, repo), map[string]any{
			"ref": "refs/heads/" + branch,
			"sha": commitSHA,
		})
	} else {
		status, raw, err = c.do(ctx, http.MethodPatch, fmt.Sprintf("/repos/%s/%s/git/refs/heads/%s", owner, repo, branch), map[string]any{
			"sha":   commitSHA,
			"force": false,
		})
	}
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("GitHub ref update HTTP %d: %s", status, truncate(string(raw), 240))
	}

	return &CommitResult{
		SHA:       commitSHA,
		URL:       htmlURL,
		Branch:    branch,
		Owner:     owner,
		Repo:      repo,
		TreeCount: len(files),
	}, nil
}

func (c *Client) CommitBranchAndDraftPR(ctx context.Context, owner, repo, branch, baseBranch, title, body, message string, files []File) (*DraftPRResult, error) {
	if baseBranch == "" {
		b, err := c.DefaultBranch(ctx, owner, repo)
		if err != nil {
			return nil, err
		}
		baseBranch = b
	}
	// Create branch from default tip first.
	status, raw, err := c.do(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s/git/ref/heads/%s", owner, repo, baseBranch), nil)
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("base branch lookup HTTP %d: %s", status, truncate(string(raw), 200))
	}
	var ref map[string]any
	_ = json.Unmarshal(raw, &ref)
	baseSHA := ""
	if obj, ok := ref["object"].(map[string]any); ok {
		baseSHA, _ = obj["sha"].(string)
	}
	if baseSHA == "" {
		return nil, fmt.Errorf("base branch has empty sha")
	}
	status, raw, err = c.do(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/git/refs", owner, repo), map[string]any{
		"ref": "refs/heads/" + branch,
		"sha": baseSHA,
	})
	if err != nil {
		return nil, err
	}
	if status == 422 {
		// Branch may already exist — continue and commit onto it.
	} else if status >= 400 {
		return nil, fmt.Errorf("create branch HTTP %d: %s", status, truncate(string(raw), 200))
	}

	commit, err := c.CommitFiles(ctx, owner, repo, branch, message, files)
	if err != nil {
		return nil, err
	}

	status, raw, err = c.do(ctx, http.MethodPost, fmt.Sprintf("/repos/%s/%s/pulls", owner, repo), map[string]any{
		"title": title,
		"head":  branch,
		"base":  baseBranch,
		"body":  body,
		"draft": true,
	})
	if err != nil {
		return nil, err
	}
	if status >= 400 {
		return nil, fmt.Errorf("create draft PR HTTP %d: %s", status, truncate(string(raw), 240))
	}
	var pr map[string]any
	_ = json.Unmarshal(raw, &pr)
	prURL, _ := pr["html_url"].(string)
	num := 0
	switch n := pr["number"].(type) {
	case float64:
		num = int(n)
	}
	return &DraftPRResult{
		URL:    prURL,
		Number: num,
		Branch: branch,
		Owner:  owner,
		Repo:   repo,
		SHA:    commit.SHA,
	}, nil
}

func (c *Client) CombinedStatus(ctx context.Context, owner, repo, ref string) (string, string, error) {
	status, raw, err := c.do(ctx, http.MethodGet, fmt.Sprintf("/repos/%s/%s/commits/%s/status", owner, repo, url.PathEscape(ref)), nil)
	if err != nil {
		return "", "", err
	}
	if status >= 400 {
		return "unknown", truncate(string(raw), 200), nil
	}
	var m map[string]any
	_ = json.Unmarshal(raw, &m)
	state, _ := m["state"].(string)
	return state, string(raw), nil
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
