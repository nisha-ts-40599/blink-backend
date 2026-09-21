package mailer

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

func TestSendOTPUsesGmailAPIWhenConfigured(t *testing.T) {
	var gotAuth, gotRaw string
	mux := http.NewServeMux()
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		if !strings.Contains(string(body), "grant_type=refresh_token") {
			t.Errorf("token body=%s", body)
		}
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "ya29.test", "expires_in": 3600})
	})
	mux.HandleFunc("/send", func(w http.ResponseWriter, r *http.Request) {
		gotAuth = r.Header.Get("Authorization")
		var payload struct {
			Raw string `json:"raw"`
		}
		_ = json.NewDecoder(r.Body).Decode(&payload)
		gotRaw = payload.Raw
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"id":"msg-1"}`))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	prevToken, prevSend := gmailTokenURL, gmailSendURL
	gmailTokenURL = srv.URL + "/token"
	gmailSendURL = srv.URL + "/send"
	t.Cleanup(func() {
		gmailTokenURL, gmailSendURL = prevToken, prevSend
	})

	svc := New(config.Config{
		SMTPHost:          "smtp.office365.com",
		GmailClientID:     "client-id",
		GmailClientSecret: "client-secret",
		GmailRefreshToken: "1//refresh",
		GmailFrom:         "talentservblink@gmail.com",
	})
	if err := svc.SendOTP(context.Background(), "ada@talentserv.co.in", "123456"); err != nil {
		t.Fatal(err)
	}
	if gotAuth != "Bearer ya29.test" {
		t.Fatalf("auth=%q", gotAuth)
	}
	if gotRaw == "" {
		t.Fatal("expected raw MIME")
	}
}

func TestSendOTPGmailSurfacesAPIError(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("/token", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{"access_token": "ya29.test", "expires_in": 3600})
	})
	mux.HandleFunc("/send", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden)
		_, _ = w.Write([]byte(`{"error":{"status":"PERMISSION_DENIED","message":"Gmail API has not been used"}}`))
	})
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	prevToken, prevSend := gmailTokenURL, gmailSendURL
	gmailTokenURL = srv.URL + "/token"
	gmailSendURL = srv.URL + "/send"
	t.Cleanup(func() {
		gmailTokenURL, gmailSendURL = prevToken, prevSend
	})
	svc := New(config.Config{
		GmailClientID:     "client-id",
		GmailClientSecret: "client-secret",
		GmailRefreshToken: "1//refresh",
		GmailFrom:         "talentservblink@gmail.com",
	})
	err := svc.SendOTP(context.Background(), "ada@talentserv.co.in", "123456")
	if err == nil || !strings.Contains(err.Error(), "Gmail API has not been used") {
		t.Fatalf("err=%v", err)
	}
}
