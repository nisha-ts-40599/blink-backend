package agent

import (
	"encoding/base64"
	"encoding/json"
	"strings"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

func shouldInvokeLambda(cfg config.Config, url string) bool {
	lowered := strings.ToLower(strings.TrimSpace(url))
	if lowered == "" {
		return false
	}
	if strings.Contains(lowered, "127.0.0.1") || strings.Contains(lowered, "localhost") {
		return false
	}
	if strings.TrimSpace(cfg.AgentLambdaName) == "" {
		return false
	}
	if strings.TrimSpace(cfg.AWSAccessKeyID) == "" || strings.TrimSpace(cfg.AWSSecretAccessKey) == "" {
		return false
	}
	return strings.Contains(lowered, "execute-api") || strings.Contains(lowered, "lambda-url") || strings.Contains(lowered, "amazonaws.com")
}

func wrapAPIGWv2(payload []byte, token string) ([]byte, error) {
	event := map[string]any{
		"version":         "2.0",
		"routeKey":        "$default",
		"rawPath":         "/",
		"rawQueryString":  "",
		"headers": map[string]string{
			"authorization": "Bearer " + token,
			"content-type":  "application/json",
			"accept":        "application/json",
		},
		"requestContext": map[string]any{
			"http": map[string]string{
				"method":   "POST",
				"path":     "/",
				"protocol": "HTTP/1.1",
				"sourceIp": "127.0.0.1",
			},
		},
		"body":            string(payload),
		"isBase64Encoded": false,
	}
	return json.Marshal(event)
}

func unwrapLambdaPayload(raw []byte) (status int, body []byte, err error) {
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" {
		return 200, []byte("{}"), nil
	}
	var envelope struct {
		StatusCode      int             `json:"statusCode"`
		Body            json.RawMessage `json:"body"`
		IsBase64Encoded bool            `json:"isBase64Encoded"`
	}
	if err := json.Unmarshal(raw, &envelope); err != nil || envelope.StatusCode == 0 && envelope.Body == nil {
		return 200, raw, nil
	}
	if envelope.StatusCode == 0 && len(envelope.Body) == 0 {
		return 200, raw, nil
	}
	status = envelope.StatusCode
	if status == 0 {
		status = 200
	}
	body = envelope.Body
	if len(body) == 0 {
		return status, []byte("{}"), nil
	}
	if envelope.IsBase64Encoded {
		quoted := strings.TrimSpace(string(body))
		src := quoted
		if len(quoted) >= 2 && quoted[0] == '"' {
			var s string
			if err := json.Unmarshal(body, &s); err == nil {
				src = s
			}
		}
		decoded, decErr := base64.StdEncoding.DecodeString(src)
		if decErr != nil {
			return status, body, nil
		}
		return status, decoded, nil
	}
	if len(body) > 0 && body[0] == '"' {
		var s string
		if err := json.Unmarshal(body, &s); err == nil {
			return status, []byte(s), nil
		}
	}
	return status, body, nil
}
