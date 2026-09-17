package setupproj

import (
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/nisha-ts-40599/blink-backend/internal/zipkit"
)

type Result struct {
	Status         string
	Message        string
	IdentitySource string
	NextCommand    string
	ContextReady   bool
	DeliveryReady  bool
	Errors         []string
	Overlay        map[string]string
}

func Apply(ctx context.Context, kit, python string, timeout time.Duration, payload map[string]any) (Result, error) {
	if timeout <= 0 {
		timeout = 90 * time.Second
	}
	script := filepath.Join(kit, "ai-sdlc", "tools", "setup", "run_hosted_greenfield_apply.py")
	if st, err := os.Stat(script); err != nil || st.IsDir() {
		return Result{}, fmt.Errorf("canonical workspace setup is unavailable because the framework projector is missing")
	}
	runRoot, err := os.MkdirTemp("", "blink-canonical-setup-")
	if err != nil {
		return Result{}, err
	}
	defer os.RemoveAll(runRoot)

	workspace := filepath.Join(runRoot, "workspace")
	if err := os.MkdirAll(workspace, 0o755); err != nil {
		return Result{}, err
	}
	input := filepath.Join(runRoot, "input.json")
	output := filepath.Join(runRoot, "manifest.json")
	raw, err := json.Marshal(payload)
	if err != nil {
		return Result{}, err
	}
	if err := os.WriteFile(input, raw, 0o644); err != nil {
		return Result{}, err
	}

	bin, prefix, err := resolvePython(kit, python)
	if err != nil {
		return Result{}, err
	}
	cmdCtx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()
	args := append(append([]string{}, prefix...), script, "--input", input, "--output", output, "--workspace-root", workspace)
	cmd := exec.CommandContext(cmdCtx, bin, args...)
	cmd.Dir = kit
	var combined bytes.Buffer
	cmd.Stdout = &combined
	cmd.Stderr = &combined
	if err := cmd.Run(); err != nil {
		if cmdCtx.Err() == context.DeadlineExceeded {
			return Result{}, fmt.Errorf("canonical workspace setup timed out")
		}
		msg := strings.TrimSpace(combined.String())
		if msg == "" {
			msg = err.Error()
		}
		return Result{}, fmt.Errorf("could not build the canonical workspace setup: %s", truncate(msg, 400))
	}
	body, err := os.ReadFile(output)
	if err != nil {
		return Result{}, fmt.Errorf("canonical workspace setup did not return a manifest")
	}
	return parseManifest(body)
}

func parseManifest(body []byte) (Result, error) {
	var root map[string]any
	if err := json.Unmarshal(body, &root); err != nil {
		return Result{}, fmt.Errorf("canonical workspace setup returned an invalid manifest")
	}
	status, _ := root["status"].(string)
	if status != "ok" && status != "overlay_ready" {
		return Result{}, fmt.Errorf("canonical workspace setup did not validate")
	}
	overlay := map[string]string{}
	files := root["overlayFiles"]
	if files == nil {
		files = root["files"]
	}
	arr, _ := files.([]any)
	for _, item := range arr {
		m, _ := item.(map[string]any)
		if m == nil {
			continue
		}
		path, _ := m["path"].(string)
		path = zipkit.SanitizeOverlayPath(path)
		content, _ := m["content"].(string)
		if path == "" {
			continue
		}
		overlay[path] = content
	}
	if len(overlay) == 0 {
		return Result{}, fmt.Errorf("canonical workspace setup returned no overlay files")
	}
	if !HasRequired(overlay) {
		return Result{}, fmt.Errorf("canonical workspace setup is incomplete")
	}
	next := nextCommand(root)
	msg, _ := root["message"].(string)
	if strings.TrimSpace(msg) == "" {
		msg = "Canonical workspace setup is ready."
	}
	ctxReady, _ := root["contextReady"].(bool)
	delivReady, _ := root["deliveryReady"].(bool)
	errs := []string{}
	if arr, ok := root["errors"].([]any); ok {
		for _, e := range arr {
			if s, ok := e.(string); ok && s != "" {
				errs = append(errs, s)
			}
		}
	}
	return Result{
		Status:         "overlay_ready",
		Message:        msg,
		IdentitySource: "requirement",
		NextCommand:    next,
		ContextReady:   ctxReady,
		DeliveryReady:  delivReady,
		Errors:         errs,
		Overlay:        overlay,
	}, nil
}

func nextCommand(root map[string]any) string {
	if action, ok := root["nextAction"].(map[string]any); ok {
		for _, key := range []string{"user_command", "command", "id"} {
			if s, _ := action[key].(string); strings.TrimSpace(s) != "" {
				return strings.TrimSpace(s)
			}
		}
	}
	if s, _ := root["nextAction"].(string); strings.TrimSpace(s) != "" {
		return strings.TrimSpace(s)
	}
	if s, _ := root["nextCommand"].(string); strings.TrimSpace(s) != "" {
		return strings.TrimSpace(s)
	}
	return "/configure-stakeholders"
}

func resolvePython(kit, configured string) (bin string, prefix []string, err error) {
	configured = strings.TrimSpace(configured)
	bundled := filepath.Join(kit, ".tools", "python312", "python.exe")
	type cand struct {
		bin    string
		prefix []string
	}
	candidates := []cand{}
	if configured == "" || configured == "python3" {
		if st, err := os.Stat(bundled); err == nil && !st.IsDir() {
			candidates = append(candidates, cand{bin: bundled})
		}
	}
	if configured != "" {
		candidates = append(candidates, cand{bin: configured})
	}
	candidates = append(candidates, cand{bin: "python"}, cand{bin: "python3"})
	if runtime.GOOS == "windows" {
		candidates = append(candidates, cand{bin: "py", prefix: []string{"-3"}})
	}
	var last error
	seen := map[string]struct{}{}
	for _, c := range candidates {
		key := c.bin + strings.Join(c.prefix, ",")
		if _, ok := seen[key]; ok {
			continue
		}
		seen[key] = struct{}{}
		args := append(append([]string{}, c.prefix...), "-c", "import sys; print(sys.version)")
		cmd := exec.Command(c.bin, args...)
		if runErr := cmd.Run(); runErr != nil {
			last = runErr
			continue
		}
		return c.bin, c.prefix, nil
	}
	if last != nil {
		return "", nil, fmt.Errorf("canonical setup python is not available (set BLINK_CANONICAL_SETUP_PYTHON): %w", last)
	}
	return "", nil, fmt.Errorf("canonical setup python is not available (set BLINK_CANONICAL_SETUP_PYTHON)")
}

func truncate(s string, n int) string {
	s = strings.TrimSpace(s)
	if n <= 0 || len(s) <= n {
		return s
	}
	return s[:n] + "…"
}
