package integrations

import "testing"

func TestParseFigmaFileKey(t *testing.T) {
	got := parseFigmaFileKey("https://www.figma.com/design/9oNaPi9jpg9Y0Xg4DsWpep/Untitled?m=auto")
	if got != "9oNaPi9jpg9Y0Xg4DsWpep" {
		t.Fatalf("key = %q", got)
	}
}

func TestStatusCategory(t *testing.T) {
	if statusCategory("new", "To Do") != "todo" {
		t.Fatal("todo")
	}
	if statusCategory("indeterminate", "In Progress") != "in-progress" {
		t.Fatal("progress")
	}
	if statusCategory("done", "Done") != "done" {
		t.Fatal("done")
	}
}
