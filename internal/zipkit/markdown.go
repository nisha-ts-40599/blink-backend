package zipkit

import (
	"fmt"
	"strings"
	"unicode/utf8"
)

// ToMarkdown builds requirement.md from pasted text or an uploaded file.
func ToMarkdown(projectName string, fileName string, fileBytes []byte, pastedText string) (string, error) {
	title := strings.TrimSpace(projectName)
	if title == "" {
		title = "Project"
	}
	if strings.TrimSpace(pastedText) != "" {
		return withTitle(title, strings.TrimSpace(pastedText)), nil
	}
	if len(fileBytes) > 0 {
		if extracted := extractText(fileBytes); strings.TrimSpace(extracted) != "" {
			return withTitle(title, strings.TrimSpace(extracted)), nil
		}
		name := fileName
		if name == "" {
			name = "upload"
		}
		return fmt.Sprintf("# %s\n\nRequirement document uploaded: `%s`\n\nBlink could not extract text from this file. Replace this page with the full\nrequirements before running the SDLC workflow.\n", title, name), nil
	}
	return "", fmt.Errorf("Upload a document or paste requirements.")
}

func withTitle(title, body string) string {
	if strings.HasPrefix(body, "#") {
		return body
	}
	return "# " + title + "\n\n" + body + "\n"
}

func extractText(bytes []byte) string {
	if !looksLikeText(bytes) {
		return ""
	}
	if len(bytes) >= 3 && bytes[0] == 0xEF && bytes[1] == 0xBB && bytes[2] == 0xBF {
		bytes = bytes[3:]
	}
	if !utf8.Valid(bytes) {
		return ""
	}
	return string(bytes)
}

func looksLikeText(bytes []byte) bool {
	limit := len(bytes)
	if limit > 4096 {
		limit = 4096
	}
	suspicious := 0
	for i := 0; i < limit; i++ {
		b := bytes[i]
		if b == 0 {
			return false
		}
		if b < 0x09 {
			suspicious++
		}
	}
	return suspicious < limit/10
}
