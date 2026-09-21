package mailer

import (
	"bytes"
	"context"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

var (
	gmailTokenURL = "https://oauth2.googleapis.com/token"
	gmailSendURL  = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send"
)

type gmailToken struct {
	access string
	expiry time.Time
}

func (s *Service) sendGmail(ctx context.Context, to, subject, body string) error {
	token, err := s.gmailAccessToken(ctx)
	if err != nil {
		return err
	}
	from := strings.TrimSpace(s.cfg.GmailFrom)
	if from == "" {
		from = "talentservblink@gmail.com"
	}
	raw := base64.RawURLEncoding.EncodeToString([]byte(rfc822(from, to, subject, body)))
	payload, err := json.Marshal(map[string]string{"raw": raw})
	if err != nil {
		return fmt.Errorf("gmail encode: %w", err)
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, gmailSendURL, bytes.NewReader(payload))
	if err != nil {
		return err
	}
	req.Header.Set("Authorization", "Bearer "+token)
	req.Header.Set("Content-Type", "application/json")
	res, err := s.httpClient().Do(req)
	if err != nil {
		return fmt.Errorf("gmail send: %w", err)
	}
	defer res.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return fmt.Errorf("gmail send HTTP %d: %s", res.StatusCode, compactGoogleError(respBody))
	}
	return nil
}

func (s *Service) gmailAccessToken(ctx context.Context) (string, error) {
	s.gmailMu.Lock()
	defer s.gmailMu.Unlock()
	if s.gmailTok.access != "" && time.Now().Add(time.Minute).Before(s.gmailTok.expiry) {
		return s.gmailTok.access, nil
	}
	form := url.Values{}
	form.Set("grant_type", "refresh_token")
	form.Set("refresh_token", strings.TrimSpace(s.cfg.GmailRefreshToken))
	form.Set("client_id", strings.TrimSpace(s.cfg.GmailClientID))
	form.Set("client_secret", strings.TrimSpace(s.cfg.GmailClientSecret))
	req, err := http.NewRequestWithContext(ctx, http.MethodPost, gmailTokenURL, strings.NewReader(form.Encode()))
	if err != nil {
		return "", err
	}
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	res, err := s.httpClient().Do(req)
	if err != nil {
		return "", fmt.Errorf("gmail token: %w", err)
	}
	defer res.Body.Close()
	respBody, _ := io.ReadAll(io.LimitReader(res.Body, 4096))
	if res.StatusCode < 200 || res.StatusCode >= 300 {
		return "", fmt.Errorf("gmail token HTTP %d: %s", res.StatusCode, compactGoogleError(respBody))
	}
	var parsed struct {
		AccessToken string `json:"access_token"`
		ExpiresIn   int    `json:"expires_in"`
	}
	if err := json.Unmarshal(respBody, &parsed); err != nil {
		return "", fmt.Errorf("gmail token parse: %w", err)
	}
	if strings.TrimSpace(parsed.AccessToken) == "" {
		return "", fmt.Errorf("gmail token missing access_token")
	}
	exp := parsed.ExpiresIn
	if exp <= 0 {
		exp = 3500
	}
	s.gmailTok = gmailToken{access: parsed.AccessToken, expiry: time.Now().Add(time.Duration(exp) * time.Second)}
	return s.gmailTok.access, nil
}

func (s *Service) httpClient() *http.Client {
	if s.http != nil {
		return s.http
	}
	return &http.Client{Timeout: 15 * time.Second}
}

func rfc822(from, to, subject, body string) string {
	fromHeader := from
	if !strings.Contains(from, "<") {
		fromHeader = "Blink <" + from + ">"
	}
	text := strings.ReplaceAll(body, "\r\n", "\n")
	text = strings.ReplaceAll(text, "\n", "\r\n")
	return "From: " + fromHeader + "\r\n" +
		"To: " + to + "\r\n" +
		"Subject: " + subject + "\r\n" +
		"MIME-Version: 1.0\r\n" +
		"Content-Type: text/plain; charset=UTF-8\r\n" +
		"\r\n" + text
}

func compactGoogleError(body []byte) string {
	trimmed := strings.TrimSpace(string(body))
	if trimmed == "" {
		return ""
	}
	var parsed struct {
		Error json.RawMessage `json:"error"`
	}
	if err := json.Unmarshal(body, &parsed); err != nil || len(parsed.Error) == 0 {
		if len(trimmed) > 240 {
			return trimmed[:240]
		}
		return trimmed
	}
	if parsed.Error[0] == '"' {
		var msg string
		_ = json.Unmarshal(parsed.Error, &msg)
		return msg
	}
	var obj struct {
		Message string `json:"message"`
		Status  string `json:"status"`
	}
	if err := json.Unmarshal(parsed.Error, &obj); err != nil {
		return trimmed
	}
	if obj.Status != "" && obj.Message != "" {
		return obj.Status + ": " + obj.Message
	}
	if obj.Message != "" {
		return obj.Message
	}
	return trimmed
}
