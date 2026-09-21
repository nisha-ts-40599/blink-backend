package mailer

import (
	"context"
	"crypto/tls"
	"fmt"
	"net"
	"net/http"
	"net/smtp"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

const smtpTimeout = 10 * time.Second

type Service struct {
	cfg     config.Config
	http    *http.Client
	gmailMu sync.Mutex
	gmailTok gmailToken
}

func New(cfg config.Config) *Service {
	return &Service{
		cfg:  cfg,
		http: &http.Client{Timeout: 15 * time.Second},
	}
}

func (s *Service) SendOTP(ctx context.Context, to, otp string) error {
	subject := "Blink sign-in code"
	body := fmt.Sprintf("Your Blink sign-in code is %s\nIt expires in a few minutes.\n", otp)
	return s.send(ctx, to, subject, body)
}

func (s *Service) SendStakeholder(ctx context.Context, to, subject, body string) error {
	return s.send(ctx, to, subject, body)
}

func (s *Service) send(ctx context.Context, to, subject, body string) error {
	if s.cfg.GmailConfigured() && !s.cfg.LocalMail() {
		return s.sendGmail(ctx, to, subject, body)
	}
	if strings.TrimSpace(s.cfg.SMTPHost) == "" || s.cfg.LocalMail() {
		_ = os.MkdirAll(s.cfg.SMTPOutboxDir, 0o755)
		name := fmt.Sprintf("%s_%s.txt", time.Now().UTC().Format("2006-01-02T15-04-05.000000Z"), sanitize(to))
		content := fmt.Sprintf("To: %s\nSubject: %s\n\n%s\n", to, subject, body)
		return os.WriteFile(filepath.Join(s.cfg.SMTPOutboxDir, name), []byte(content), 0o600)
	}
	return s.sendSMTP(ctx, to, subject, body)
}

func (s *Service) sendSMTP(ctx context.Context, to, subject, body string) error {
	ctx, cancel := withSMTPTimeout(ctx)
	defer cancel()

	host := strings.TrimSpace(s.cfg.SMTPHost)
	port := s.cfg.SMTPPort
	if port <= 0 {
		port = 587
	}
	addr := net.JoinHostPort(host, strconv.Itoa(port))

	dialer := &net.Dialer{}
	raw, err := dialer.DialContext(ctx, "tcp", addr)
	if err != nil {
		return fmt.Errorf("could not reach mail server: %w", err)
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = raw.SetDeadline(deadline)
	}

	tlsCfg := &tls.Config{ServerName: host, MinVersion: tls.VersionTLS12}
	conn := net.Conn(raw)
	implicitTLS := port == 465
	if implicitTLS {
		tlsConn := tls.Client(raw, tlsCfg)
		if err := tlsConn.HandshakeContext(ctx); err != nil {
			_ = raw.Close()
			return fmt.Errorf("mail TLS handshake failed: %w", err)
		}
		conn = tlsConn
	}

	client, err := smtp.NewClient(conn, host)
	if err != nil {
		_ = conn.Close()
		return fmt.Errorf("mail server handshake failed: %w", err)
	}
	defer func() { _ = client.Close() }()

	if !implicitTLS && s.cfg.SMTPStartTLS {
		ok, _ := client.Extension("STARTTLS")
		if !ok {
			return fmt.Errorf("mail server did not offer STARTTLS")
		}
		if err := client.StartTLS(tlsCfg); err != nil {
			return fmt.Errorf("mail STARTTLS failed: %w", err)
		}
	}

	from := strings.TrimSpace(s.cfg.SMTPFrom)
	if from == "" {
		from = "blink@localhost"
	}
	if user := strings.TrimSpace(s.cfg.SMTPUsername); user != "" {
		if ok, _ := client.Extension("AUTH"); ok {
			auth := smtp.PlainAuth("", user, s.cfg.SMTPPassword, host)
			if err := client.Auth(auth); err != nil {
				return fmt.Errorf("mail authentication failed: %w", err)
			}
		}
	}
	if err := client.Mail(from); err != nil {
		return fmt.Errorf("mail from rejected: %w", err)
	}
	if err := client.Rcpt(to); err != nil {
		return fmt.Errorf("mail recipient rejected: %w", err)
	}
	wc, err := client.Data()
	if err != nil {
		return fmt.Errorf("mail data failed: %w", err)
	}
	msg := "From: " + from + "\r\nTo: " + to + "\r\nSubject: " + subject +
		"\r\nMIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\n\r\n" + body + "\r\n"
	if _, err := wc.Write([]byte(msg)); err != nil {
		_ = wc.Close()
		return fmt.Errorf("mail write failed: %w", err)
	}
	if err := wc.Close(); err != nil {
		return fmt.Errorf("mail close failed: %w", err)
	}
	if err := client.Quit(); err != nil {
		return fmt.Errorf("mail quit failed: %w", err)
	}
	return nil
}

func withSMTPTimeout(ctx context.Context) (context.Context, context.CancelFunc) {
	if ctx == nil {
		ctx = context.Background()
	}
	if _, ok := ctx.Deadline(); ok {
		return context.WithCancel(ctx)
	}
	return context.WithTimeout(ctx, smtpTimeout)
}

func sanitize(s string) string {
	return strings.Map(func(r rune) rune {
		if (r >= 'a' && r <= 'z') || (r >= 'A' && r <= 'Z') || (r >= '0' && r <= '9') || r == '@' || r == '.' || r == '-' || r == '_' {
			return r
		}
		return '_'
	}, s)
}
