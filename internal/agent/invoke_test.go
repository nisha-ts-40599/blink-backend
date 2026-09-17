package agent

import (
	"encoding/json"
	"strings"
	"testing"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

func TestShouldInvokeLambda(t *testing.T) {
	t.Parallel()
	cfg := config.Config{
		AgentLambdaName:    "blink-agent-runtime",
		AWSAccessKeyID:     "AKIA",
		AWSSecretAccessKey: "secret",
	}
	if !shouldInvokeLambda(cfg, "https://z5i3yybrx1.execute-api.us-west-2.amazonaws.com") {
		t.Fatal("production API Gateway URL should invoke Lambda")
	}
	if shouldInvokeLambda(cfg, "http://127.0.0.1:8000") {
		t.Fatal("localhost must stay on HTTP")
	}
	cfg.AWSAccessKeyID = ""
	if shouldInvokeLambda(cfg, "https://z5i3yybrx1.execute-api.us-west-2.amazonaws.com") {
		t.Fatal("missing AWS keys must not invoke")
	}
}

func TestUnwrapLambdaPayload(t *testing.T) {
	t.Parallel()
	status, body, err := unwrapLambdaPayload([]byte(`{"statusCode":200,"body":"{\"status\":\"ok\"}","isBase64Encoded":false}`))
	if err != nil {
		t.Fatal(err)
	}
	if status != 200 || string(body) != `{"status":"ok"}` {
		t.Fatalf("got status=%d body=%s", status, body)
	}
	status, body, err = unwrapLambdaPayload([]byte(`{"status":"ok","command":"grooming-stakeholder-pack"}`))
	if err != nil {
		t.Fatal(err)
	}
	if status != 200 || string(body) != `{"status":"ok","command":"grooming-stakeholder-pack"}` {
		t.Fatalf("direct payload unwrap failed: %s", body)
	}
}

func TestWrapAPIGWv2(t *testing.T) {
	t.Parallel()
	raw, err := wrapAPIGWv2([]byte(`{"command":"grooming-stakeholder-pack"}`), "token")
	if err != nil {
		t.Fatal(err)
	}
	var event map[string]any
	if err := json.Unmarshal(raw, &event); err != nil {
		t.Fatal(err)
	}
	if event["version"] != "2.0" {
		t.Fatalf("version=%v", event["version"])
	}
	headers, _ := event["headers"].(map[string]any)
	if headers["authorization"] != "Bearer token" {
		t.Fatalf("authorization=%v", headers["authorization"])
	}
	if !strings.Contains(fmtString(event["body"]), "grooming-stakeholder-pack") {
		t.Fatalf("body=%v", event["body"])
	}
}

func fmtString(v any) string {
	s, _ := v.(string)
	return s
}
