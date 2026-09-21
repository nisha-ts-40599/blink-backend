package mailer

import (
	"context"
	"strings"
)

type QuestionItem struct {
	QuestionID     string `json:"question_id"`
	Question       string `json:"question"`
	RecipientEmail string `json:"recipient_email"`
	RecipientName  string `json:"recipient_name"`
	Role           string `json:"role"`
	ProjectName    string `json:"project_name"`
	ProposedAnswer string `json:"proposed_answer"`
}

type DeliveryResult struct {
	QuestionID     string `json:"question_id"`
	Status         string `json:"status"`
	DeliveryMethod string `json:"delivery_method"`
	Message        string `json:"message"`
}

type SendQuestionsResponse struct {
	Results      []DeliveryResult `json:"results"`
	DeliveryMode string           `json:"delivery_mode"`
	OutboxDir    *string          `json:"outbox_dir"`
}

func (s *Service) SendQuestions(ctx context.Context, questions []QuestionItem) SendQuestionsResponse {
	gmail := s.cfg.GmailConfigured() && !s.cfg.LocalMail()
	smtp := !gmail && strings.TrimSpace(s.cfg.SMTPHost) != "" && !s.cfg.LocalMail()
	mode := "outbox"
	if gmail {
		mode = "gmail"
	} else if smtp {
		mode = "smtp"
	}
	grouped := groupByRecipient(questions)
	results := make([]DeliveryResult, 0, len(questions))
	for _, batch := range grouped {
		email := batch.email
		subject := buildSubject(batch.items)
		body := buildBody(batch.items)
		method := mode
		message := "Sent via SMTP to " + email
		if gmail {
			message = "Sent via Gmail to " + email
		}
		if err := s.send(ctx, email, subject, body); err != nil {
			for _, item := range batch.items {
				results = append(results, DeliveryResult{
					QuestionID:     item.QuestionID,
					Status:         "failed",
					DeliveryMethod: mode,
					Message:        err.Error(),
				})
			}
			continue
		}
		if !gmail && !smtp {
			method = "outbox"
			message = "Saved to outbox"
		}
		for _, item := range batch.items {
			results = append(results, DeliveryResult{
				QuestionID:     item.QuestionID,
				Status:         "sent",
				DeliveryMethod: method,
				Message:        message,
			})
		}
	}
	resp := SendQuestionsResponse{Results: results, DeliveryMode: mode}
	if !gmail && !smtp {
		dir := s.cfg.SMTPOutboxDir
		resp.OutboxDir = &dir
	}
	return resp
}

func groupByRecipient(questions []QuestionItem) []recipientBatch {
	index := map[string]int{}
	var batches []recipientBatch
	for _, item := range questions {
		email := strings.ToLower(strings.TrimSpace(item.RecipientEmail))
		if email == "" {
			continue
		}
		if i, ok := index[email]; ok {
			batches[i].items = append(batches[i].items, item)
			continue
		}
		index[email] = len(batches)
		batches = append(batches, recipientBatch{email: email, items: []QuestionItem{item}})
	}
	return batches
}

type recipientBatch struct {
	email string
	items []QuestionItem
}

func buildSubject(batch []QuestionItem) string {
	project := "Blink project"
	for _, item := range batch {
		if name := strings.TrimSpace(item.ProjectName); name != "" {
			project = name
			break
		}
	}
	n := len(batch)
	suffix := "s"
	if n == 1 {
		suffix = ""
	}
	return "Blink clarification" + suffix + " for " + project + " (" + itoa(n) + ")"
}

func buildBody(batch []QuestionItem) string {
	name := "there"
	if len(batch) > 0 {
		if trimmed := strings.TrimSpace(batch[0].RecipientName); trimmed != "" {
			name = trimmed
		}
	}
	var b strings.Builder
	b.WriteString("Hi ")
	b.WriteString(name)
	b.WriteString(",\n\nBlink needs your input on the following clarification")
	if len(batch) == 1 {
		b.WriteString(":\n\n")
	} else {
		b.WriteString("s:\n\n")
	}
	for i, item := range batch {
		b.WriteString(itoa(i + 1))
		b.WriteString(". ")
		if role := strings.TrimSpace(item.Role); role != "" {
			b.WriteString("[")
			b.WriteString(role)
			b.WriteString("] ")
		}
		b.WriteString(strings.TrimSpace(item.Question))
		b.WriteByte('\n')
		if proposed := strings.TrimSpace(item.ProposedAnswer); proposed != "" {
			b.WriteString("   Proposed answer: ")
			b.WriteString(proposed)
			b.WriteByte('\n')
		}
		b.WriteByte('\n')
	}
	b.WriteString("Please reply with your confirmation or corrections.\n\n— Blink\n")
	return b.String()
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	var digits [12]byte
	i := len(digits)
	for n > 0 {
		i--
		digits[i] = byte('0' + n%10)
		n /= 10
	}
	return string(digits[i:])
}
