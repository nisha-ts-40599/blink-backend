package zipkit

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
)

var ensureMu sync.Mutex

// LooksReal reports whether dir is an automation_sdlc kit, not an empty folder.
func LooksReal(dir string) bool {
	dir = strings.TrimSpace(dir)
	if dir == "" {
		return false
	}
	st, err := os.Stat(dir)
	if err != nil || !st.IsDir() {
		return false
	}
	for _, marker := range []string{"framework_prompts", "app", "Makefile", "pyproject.toml", "prompts"} {
		if _, err := os.Stat(filepath.Join(dir, marker)); err == nil {
			return true
		}
	}
	return false
}

// Resolve returns a real kit directory from the configured path, or "".
func Resolve(configured string) string {
	p := resolveAutomationSDLC(configured)
	if LooksReal(p) {
		return p
	}
	return ""
}

// Ensure makes dest a real automation_sdlc kit, cloning gitURL when the path is empty.
func Ensure(dest, gitURL string) (string, error) {
	ensureMu.Lock()
	defer ensureMu.Unlock()

	if resolved := resolveAutomationSDLC(dest); LooksReal(resolved) {
		return resolved, nil
	}

	gitURL = strings.TrimSpace(gitURL)
	if gitURL == "" {
		return "", fmt.Errorf("automation_sdlc kit is missing and BLINK_AUTOMATION_SDLC_GIT_URL is empty")
	}

	target := strings.TrimSpace(dest)
	if target == "" {
		target = "automation_sdlc"
	}
	abs, err := filepath.Abs(target)
	if err != nil {
		return "", err
	}
	tmp := abs + ".partial"
	_ = os.RemoveAll(tmp)
	if err := os.MkdirAll(filepath.Dir(abs), 0o755); err != nil {
		return "", err
	}

	cmd := exec.Command("git", "clone", "--depth", "1", gitURL, tmp)
	cmd.Env = append(os.Environ(), "GIT_TERMINAL_PROMPT=0")
	out, err := cmd.CombinedOutput()
	if err != nil {
		_ = os.RemoveAll(tmp)
		return "", fmt.Errorf("git clone automation_sdlc failed: %w (%s)", err, strings.TrimSpace(string(out)))
	}
	if !LooksReal(tmp) {
		_ = os.RemoveAll(tmp)
		return "", fmt.Errorf("cloned %s but it does not look like automation_sdlc", gitURL)
	}
	_ = os.RemoveAll(abs)
	if err := os.Rename(tmp, abs); err != nil {
		_ = os.RemoveAll(tmp)
		return "", err
	}
	return abs, nil
}

// ListKitFiles returns relative slash-separated files to copy from a kit root.
func ListKitFiles(root string) ([]string, error) {
	if !LooksReal(root) {
		return nil, fmt.Errorf("automation_sdlc kit is missing at %s", root)
	}
	var files []string
	err := filepath.WalkDir(root, func(path string, d os.DirEntry, err error) error {
		if err != nil {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil || rel == "." {
			return nil
		}
		name := d.Name()
		parent := ""
		if parentRel := filepath.Dir(rel); parentRel != "." {
			parent = filepath.Base(parentRel)
		}
		if d.IsDir() {
			if SkipDirectory(name, parent) {
				return filepath.SkipDir
			}
			return nil
		}
		if SkipFile(name) {
			return nil
		}
		files = append(files, filepath.ToSlash(rel))
		return nil
	})
	return files, err
}

// ListAutomationSdlcFiles is the kit minus .cursor (uploaded under automation_sdlc/).
func ListAutomationSdlcFiles(root string) ([]string, error) {
	all, err := ListKitFiles(root)
	if err != nil {
		return nil, err
	}
	out := make([]string, 0, len(all))
	for _, rel := range all {
		if rel == ".cursor" || strings.HasPrefix(rel, ".cursor/") {
			continue
		}
		out = append(out, rel)
	}
	return out, nil
}

// ListCursorCommandFiles returns .cursor/commands/* relative paths for the workspace .cursor tree.
func ListCursorCommandFiles(root string) ([]string, error) {
	commands := filepath.Join(root, ".cursor", "commands")
	st, err := os.Stat(commands)
	if err != nil || !st.IsDir() {
		return nil, nil
	}
	var files []string
	err = filepath.WalkDir(commands, func(path string, d os.DirEntry, walkErr error) error {
		if walkErr != nil || d.IsDir() {
			return nil
		}
		if SkipFile(d.Name()) {
			return nil
		}
		rel, relErr := filepath.Rel(root, path)
		if relErr != nil {
			return nil
		}
		files = append(files, filepath.ToSlash(rel))
		return nil
	})
	return files, err
}
