package mailer

import (
	"context"
	"fmt"
	"net/smtp"
	"os"
	"path/filepath"
	"strings"
	"time"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

type Service struct {
	cfg config.Config
}

func New(cfg config.Config) *Service { return &Service{cfg: cfg} }

func (s *Service) SendOTP(ctx context.Context, to, otp string) error {
	subject := "Blink sign-in code"
	body := fmt.Sprintf("Your Blink sign-in code is %s\nIt expires in a few minutes.\n", otp)
	return s.send(to, subject, body)
}

func (s *Service) SendStakeholder(ctx context.Context, to, subject, body string) error {
	return s.send(to, subject, body)
}

func (s *Service) send(to, subject, body string) error {
	if strings.TrimSpace(s.cfg.SMTPHost) == "" {
		_ = os.MkdirAll(s.cfg.SMTPOutboxDir, 0o755)
		name := fmt.Sprintf("%s_%s.txt", time.Now().UTC().Format("2006-01-02T15-04-05.000000Z"), sanitize(to))
		content := fmt.Sprintf("To: %s\nSubject: %s\n\n%s\n", to, subject, body)
		return os.WriteFile(filepath.Join(s.cfg.SMTPOutboxDir, name), []byte(content), 0o600)
	}
	addr := fmt.Sprintf("%s:%d", s.cfg.SMTPHost, s.cfg.SMTPPort)
	msg := []byte("To: " + to + "\r\nSubject: " + subject + "\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body + "\r\n")
	var auth smtp.Auth
	if s.cfg.SMTPUsername != "" {
		auth = smtp.PlainAuth("", s.cfg.SMTPUsername, s.cfg.SMTPPassword, s.cfg.SMTPHost)
	}
	return smtp.SendMail(addr, auth, s.cfg.SMTPFrom, []string{to}, msg)
}

func sanitize(s string) string {
	return strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '@' || r == '.' || r == '-' || r == '_' {
			return r
		}
		return '_'
	}, s)
}
