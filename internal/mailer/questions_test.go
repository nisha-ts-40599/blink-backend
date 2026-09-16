package mailer

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/nisha-ts-40599/blink-backend/internal/config"
)

func TestSendQuestionsWritesOneOutboxFilePerPerson(t *testing.T) {
	t.Parallel()
	dir := t.TempDir()
	svc := New(config.Config{SMTPOutboxDir: dir})
	resp := svc.SendQuestions(context.Background(), []QuestionItem{
		{QuestionID: "q1", Question: "Alpha?", RecipientEmail: "alice@example.com", RecipientName: "Alice", ProjectName: "Demo"},
		{QuestionID: "q2", Question: "Beta?", RecipientEmail: "alice@example.com", RecipientName: "Alice", ProjectName: "Demo"},
		{QuestionID: "q3", Question: "Gamma?", RecipientEmail: "bob@example.com", RecipientName: "Bob", ProjectName: "Demo"},
	})
	if resp.DeliveryMode != "outbox" {
		t.Fatalf("mode=%s", resp.DeliveryMode)
	}
	if len(resp.Results) != 3 {
		t.Fatalf("results=%d", len(resp.Results))
	}
	for _, row := range resp.Results {
		if row.Status != "sent" {
			t.Fatalf("row=%+v", row)
		}
	}
	files, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	if len(files) != 2 {
		t.Fatalf("files=%d", len(files))
	}
	alice, err := os.ReadFile(filepath.Join(dir, firstNameContaining(files, "alice")))
	if err != nil {
		t.Fatal(err)
	}
	text := string(alice)
	if !strings.Contains(text, "Alpha?") || !strings.Contains(text, "Beta?") {
		t.Fatalf("alice body=%s", text)
	}
}

func firstNameContaining(files []os.DirEntry, needle string) string {
	for _, f := range files {
		if strings.Contains(strings.ToLower(f.Name()), needle) {
			return f.Name()
		}
	}
	return files[0].Name()
}
