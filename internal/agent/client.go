package agent

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

type Client struct {
	cfg    config.Config
	client *http.Client
}

func New(cfg config.Config) *Client {
	return &Client{
		cfg: cfg,
		client: &http.Client{
			Timeout: 90 * time.Second,
		},
	}
}

func (c *Client) Invoke(ctx context.Context, payload map[string]any) (json.RawMessage, error) {
	if strings.TrimSpace(c.cfg.AgentRuntimeToken) == "" {
		return nil, fmt.Errorf("BLINK_AGENT_RUNTIME_TOKEN is not configured")
	}
	body, err := json.Marshal(payload)
	if err != nil {
		return nil, err
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, c.cfg.AgentRuntimeURL, bytes.NewReader(body))
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	req.Header.Set("Content-Type", "application/json")
	req.Header.Set("User-Agent", "Blink-Backend-Go/1.0")
	req.Header.Set("Authorization", "Bearer "+c.cfg.AgentRuntimeToken)
	res, err := c.client.Do(req)
	if err != nil {
		return nil, err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(res.Body)
	if res.StatusCode >= 400 {
		return nil, fmt.Errorf("agent runtime HTTP %d: %s", res.StatusCode, truncate(string(raw), 400))
	}
	return json.RawMessage(raw), nil
}

func (c *Client) Clarify(ctx context.Context, body map[string]any) (json.RawMessage, error) {
	body["command"] = "clarify-requirement"
	if _, ok := body["mode"]; !ok {
		body["mode"] = "discovery"
	}
	return c.Invoke(ctx, body)
}

func (c *Client) PlanProductScope(ctx context.Context, projectName, projectID, requirementText, actor string) (json.RawMessage, error) {
	payload := map[string]any{
		"command":     "plan-product-scope",
		"projectName": projectName,
	}
	if projectID != "" {
		payload["projectId"] = projectID
	}
	if requirementText != "" {
		payload["requirementText"] = requirementText
	}
	if actor != "" {
		payload["actor"] = actor
	}
	return c.Invoke(ctx, payload)
}

func (c *Client) ConfigureStakeholders(ctx context.Context, projectName, projectID string, stakeholders []map[string]string) (json.RawMessage, error) {
	payload := map[string]any{
		"command":      "configure-stakeholders",
		"mode":         "apply",
		"projectName":  projectName,
		"stakeholders": stakeholders,
	}
	if projectID != "" {
		payload["projectId"] = projectID
	}
	return c.Invoke(ctx, payload)
}

// SetupNewWorkspace invokes setup-new-workspace apply on the agent runtime.
func (c *Client) SetupNewWorkspace(ctx context.Context, payload map[string]any) (json.RawMessage, error) {
	if payload == nil {
		payload = map[string]any{}
	}
	payload["command"] = "setup-new-workspace"
	if _, ok := payload["mode"]; !ok {
		payload["mode"] = "apply"
	}
	return c.Invoke(ctx, payload)
}

func truncate(s string, n int) string {
	if len(s) <= n {
		return s
	}
	return s[:n]
}
