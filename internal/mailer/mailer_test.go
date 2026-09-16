package mailer

import (
	"context"
	"net"
	"os"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

func TestSendOTPUsesOutboxWhenLocalMailEvenIfHostSet(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	svc := New(config.Config{
		SMTPHost:      "smtp.office365.com",
		SMTPPort:      587,
		SMTPFrom:      "blink@localhost",
		SMTPOutboxDir: dir,
		OTPReveal:     true,
		LoginGate:     "gate",
	})
	if err := svc.SendOTP(context.Background(), "ada@talentserv.co.in", "123456"); err != nil {
		t.Fatal(err)
	}
	files, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 1 {
		t.Fatalf("outbox files=%d", len(files))
	}
}

func TestSendOTPTimesOutWhenServerNeverReplies(t *testing.T) {
	t.Parallel()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		conn, acceptErr := ln.Accept()
		if acceptErr != nil {
			return
		}
		defer conn.Close()
		time.Sleep(30 * time.Second)
	}()
	_, portStr, err := net.SplitHostPort(ln.Addr().String())
	if err != nil {
		t.Fatal(err)
	}
	port, err := strconv.Atoi(portStr)
	if err != nil {
		t.Fatal(err)
	}
	svc := New(config.Config{
		SMTPHost:     "127.0.0.1",
		SMTPPort:     port,
		SMTPFrom:     "blink@localhost",
		SMTPStartTLS: false,
	})
	ctx, cancel := context.WithTimeout(context.Background(), 400*time.Millisecond)
	defer cancel()
	start := time.Now()
	err = svc.SendOTP(ctx, "ada@talentserv.co.in", "123456")
	elapsed := time.Since(start)
	if err == nil {
		t.Fatal("expected timeout, got nil")
	}
	if elapsed > 2*time.Second {
		t.Fatalf("SMTP hang was not bounded, elapsed=%s err=%v", elapsed, err)
	}
}

func TestGroupByRecipientKeepsOrderAndFoldsCase(t *testing.T) {
	t.Parallel()
	batches := groupByRecipient([]QuestionItem{
		{QuestionID: "q1", RecipientEmail: "alice@Example.com", Question: "Alpha?"},
		{QuestionID: "q2", RecipientEmail: "bob@example.com", Question: "Gamma?"},
		{QuestionID: "q3", RecipientEmail: "alice@example.com", Question: "Beta?"},
	})
	if len(batches) != 2 {
		t.Fatalf("got %d batches", len(batches))
	}
	if batches[0].email != "alice@example.com" || len(batches[0].items) != 2 {
		t.Fatalf("alice batch: %+v", batches[0])
	}
	if batches[0].items[0].QuestionID != "q1" || batches[0].items[1].QuestionID != "q3" {
		t.Fatalf("alice ids: %+v", batches[0].items)
	}
	if batches[1].email != "bob@example.com" {
		t.Fatalf("bob batch: %+v", batches[1])
	}
}

func TestBuildBodyIncludesProposedAnswer(t *testing.T) {
	t.Parallel()
	body := buildBody([]QuestionItem{{
		RecipientName:  "Ada",
		Role:           "BA",
		Question:       "Need SSO?",
		ProposedAnswer: "Yes",
	}})
	if !strings.Contains(body, "Hi Ada") || !strings.Contains(body, "[BA] Need SSO?") || !strings.Contains(body, "Proposed answer: Yes") {
		t.Fatalf("body=%q", body)
	}
}
