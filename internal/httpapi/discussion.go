package httpapi

import (
	"encoding/json"
	"fmt"
	"net/http"
	"regexp"
	"strings"
)

var (
	blinkSimReplyRe  = regexp.MustCompile(`(?i)\[blink-sim-reply\]`)
	blinkQuestionRe  = regexp.MustCompile(`(?i)\[blink-question:[^\]]+\]`)
	multiNewlineRe   = regexp.MustCompile(`\n{3,}`)
)

func cleanDiscussionText(s string) string {
	s = blinkSimReplyRe.ReplaceAllString(s, "")
	s = blinkQuestionRe.ReplaceAllString(s, "")
	s = multiNewlineRe.ReplaceAllString(s, "\n\n")
	return strings.TrimSpace(s)
}

func localDiscussionSummary(question, parentBody string, replies []map[string]any) (summary, resolved string) {
	var b strings.Builder
	speakers := make([]string, 0)
	seen := map[string]bool{}
	if q := strings.TrimSpace(question); q != "" {
		b.WriteString("Question: ")
		b.WriteString(q)
		b.WriteString("\n")
	}
	lines := make([]string, 0, len(replies))
	for _, row := range replies {
		author, _ := row["author"].(string)
		body, _ := row["body"].(string)
		author = strings.TrimSpace(author)
		body = cleanDiscussionText(body)
		if body == "" {
			continue
		}
		if author == "" {
			author = "Someone"
		}
		if !seen[author] {
			seen[author] = true
			speakers = append(speakers, author)
		}
		lines = append(lines, fmt.Sprintf("- %s: %s", author, body))
		resolved = body
	}
	if len(speakers) > 0 {
		b.WriteString("Participants: ")
		b.WriteString(strings.Join(speakers, ", "))
		b.WriteString("\n")
	}
	b.WriteString("Discussion:\n")
	b.WriteString(strings.Join(lines, "\n"))
	if resolved == "" {
		resolved = cleanDiscussionText(parentBody)
	}
	if resolved == "" {
		resolved = "No clear decision was captured in the thread yet."
	}
	b.WriteString("\n\nProposed resolution (latest reply): ")
	b.WriteString(resolved)
	return strings.TrimSpace(b.String()), resolved
}

// summarizeDiscussion turns a parent clarification + child replies into a digest and resolved answer.
func (s *Server) summarizeDiscussion(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Question   string `json:"question"`
		ParentBody string `json:"parentBody"`
		IssueKey   string `json:"issueKey"`
		Replies    []struct {
			CommentID string `json:"commentId"`
			Author    string `json:"author"`
			Body      string `json:"body"`
			Created   string `json:"created"`
			ParentID  string `json:"parentId"`
		} `json:"replies"`
	}
	if err := readJSON(r, &req); err != nil {
		writeErr(w, err)
		return
	}

	replyMaps := make([]map[string]any, 0, len(req.Replies))
	for _, row := range req.Replies {
		body := cleanDiscussionText(row.Body)
		if body == "" {
			continue
		}
		replyMaps = append(replyMaps, map[string]any{
			"commentId": row.CommentID,
			"author":    row.Author,
			"body":      body,
			"created":   row.Created,
			"parentId":  row.ParentID,
		})
	}
	fallbackSummary, fallbackResolved := localDiscussionSummary(req.Question, req.ParentBody, replyMaps)

	payload := map[string]any{
		"command":  "summarize-discussion",
		"question": req.Question,
		"discussion": map[string]any{
			"question":   req.Question,
			"parentBody": cleanDiscussionText(req.ParentBody),
			"issueKey":   req.IssueKey,
			"replies":    replyMaps,
		},
	}
	raw, err := s.agent.SummarizeDiscussion(r.Context(), payload)
	if err != nil {
		writeJSON(w, http.StatusOK, map[string]any{
			"status":          "ok",
			"message":         "Structured discussion summary (local).",
			"summary":         fallbackSummary,
			"resolvedAnswer":  fallbackResolved,
			"source":          "local",
			"agentError":      err.Error(),
		})
		return
	}

	var root map[string]any
	_ = json.Unmarshal(raw, &root)
	summary, _ := root["summary"].(string)
	resolved, _ := root["resolvedAnswer"].(string)
	summary = strings.TrimSpace(summary)
	resolved = strings.TrimSpace(resolved)
	if summary == "" {
		summary = fallbackSummary
	}
	if resolved == "" {
		resolved = fallbackResolved
	}
	msg, _ := root["message"].(string)
	if strings.TrimSpace(msg) == "" {
		msg = "Discussion summarized."
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"status":         "ok",
		"message":        msg,
		"summary":        summary,
		"resolvedAnswer": resolved,
		"source":         "agent",
	})
}
