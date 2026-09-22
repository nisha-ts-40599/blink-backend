package workflow

import (
	"context"
	"encoding/json"
	"errors"
	"sync"
	"testing"
	"time"

	"github.com/google/uuid"
)

func TestStartRunCreatesApprovalTask(t *testing.T) {
	m := NewMemory()
	detail, err := m.StartRun(context.Background(), 7, "po@example.com", "Athletes can log a daily walk.")
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.Status != StatusWaitingForHuman {
		t.Fatalf("status=%s", detail.Run.Status)
	}
	if detail.Run.WorkTier != 1 {
		t.Fatalf("workTier=%d", detail.Run.WorkTier)
	}
	if len(detail.Run.RequiredGates) != 2 {
		t.Fatalf("requiredGates=%v", detail.Run.RequiredGates)
	}
	if len(detail.Tasks) != 1 || detail.Tasks[0].Kind != TaskGateApproval {
		t.Fatalf("tasks=%+v", detail.Tasks)
	}
	if detail.Tasks[0].BoundHash != HashText("Athletes can log a daily walk.") {
		t.Fatal("bound hash mismatch")
	}
}

func TestApproveEnqueuesLocalDockerJob(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, err := m.StartRun(ctx, 7, "po@example.com", "Ship a draft PR for walk logging.")
	if err != nil {
		t.Fatal(err)
	}
	after, err := m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{"approved":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if after.Run.Status != StatusWaitingForRunner {
		t.Fatalf("status=%s", after.Run.Status)
	}
	if len(after.Jobs) != 1 || after.Jobs[0].Placement != PlacementLocalDocker {
		t.Fatalf("jobs=%+v", after.Jobs)
	}
}

func TestClaimIsExclusive(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "exclusive claim")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	a, err := m.RegisterRunner(ctx, "po@example.com", "a", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, err := m.RegisterRunner(ctx, "po@example.com", "b", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	var gotA, gotB int
	var wg sync.WaitGroup
	wg.Add(2)
	go func() {
		defer wg.Done()
		if _, err := m.ClaimJob(ctx, a.ID); err == nil {
			gotA++
		}
	}()
	go func() {
		defer wg.Done()
		if _, err := m.ClaimJob(ctx, b.ID); err == nil {
			gotB++
		}
	}()
	wg.Wait()
	if gotA+gotB != 1 {
		t.Fatalf("expected one claim, got a=%d b=%d", gotA, gotB)
	}
}

func TestExpiredLeaseWaitsForSameRunner(t *testing.T) {
	m := NewMemory()
	now := time.Date(2026, 9, 18, 12, 0, 0, 0, time.UTC)
	m.now = func() time.Time { return now }
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "resume after sleep")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	first, _ := m.RegisterRunner(ctx, "po@example.com", "laptop-1", nil, nil)
	other, _ := m.RegisterRunner(ctx, "dev2@example.com", "laptop-2", nil, nil)
	job, err := m.ClaimJob(ctx, first.ID)
	if err != nil {
		t.Fatal(err)
	}
	now = now.Add(2 * time.Minute)
	m.now = func() time.Time { return now }
	if err := m.Reconcile(ctx, now); err != nil {
		t.Fatal(err)
	}
	if _, err := m.ClaimJob(ctx, other.ID); !errors.Is(err, ErrNoJob) {
		t.Fatalf("other runner should not steal job: %v", err)
	}
	resumed, err := m.ClaimJob(ctx, first.ID)
	if err != nil {
		t.Fatal(err)
	}
	if resumed.ID != job.ID {
		t.Fatal("same runner should resume the same job")
	}
	run, _ := m.GetRun(ctx, detail.Run.ID)
	if run.Run.Status != StatusRunning {
		t.Fatalf("expected RUNNING after resume, got %s", run.Run.Status)
	}
}

func TestStaleBoundHashInvalidatesApproval(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "original wording")
	m.mu.Lock()
	m.runs[detail.Run.ID].RequirementHash = HashText("changed later")
	m.mu.Unlock()
	_, err := m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	if !errors.Is(err, ErrStaleBound) {
		t.Fatalf("got %v", err)
	}
}

func TestRunnerTokenLookup(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	r, err := m.RegisterRunner(ctx, "po@example.com", "devbox", nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if r.Token == "" {
		t.Fatal("plaintext token must be returned once")
	}
	got, err := m.RunnerByToken(ctx, r.Token)
	if err != nil || got.ID != r.ID {
		t.Fatalf("lookup: %+v %v", got, err)
	}
	if _, err := m.RunnerByToken(ctx, "nope"); !errors.Is(err, ErrNotFound) {
		t.Fatalf("expected not found, got %v", err)
	}
}

func TestCompleteJobMarksRunDone(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "draft pr")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	runner, _ := m.RegisterRunner(ctx, "po@example.com", "devbox", nil, nil)
	job, _ := m.ClaimJob(ctx, runner.ID)
	_, err := m.HeartbeatJob(ctx, job.ID, runner.ID, "conv-1")
	if err != nil {
		t.Fatal(err)
	}
	done, err := m.CompleteJob(ctx, job.ID, runner.ID, json.RawMessage(`{"prUrl":"https://github.com/acme/app/pull/1","headSha":"abc123"}`))
	if err != nil {
		t.Fatal(err)
	}
	if done.Status != StatusCompleted {
		t.Fatalf("job=%s", done.Status)
	}
	run, _ := m.GetRun(ctx, detail.Run.ID)
	if run.Run.Status != StatusWaitingForHuman {
		t.Fatalf("run=%s", run.Run.Status)
	}
	if run.Run.CurrentStage != StageMerge {
		t.Fatalf("stage=%s", run.Run.CurrentStage)
	}
	if run.Jobs[0].ConversationID != "conv-1" {
		t.Fatalf("conversation=%s", run.Jobs[0].ConversationID)
	}
	var merge *HumanTask
	for i := range run.Tasks {
		if run.Tasks[i].Kind == TaskMergeAttest && run.Tasks[i].Status == StatusOpen {
			merge = &run.Tasks[i]
		}
	}
	if merge == nil {
		t.Fatal("expected open MERGE_ATTESTATION")
	}
	if merge.BoundHash != "abc123" {
		t.Fatalf("bound=%s", merge.BoundHash)
	}
	if len(run.Artifacts) != 1 || run.Artifacts[0].URI != "https://github.com/acme/app/pull/1" {
		t.Fatalf("artifacts=%+v", run.Artifacts)
	}
	if err := m.EnrichOpenMerge(ctx, detail.Run.ID, map[string]any{
		"registered_pr":         "https://github.com/acme/app/pull/1",
		"headSha":               "def456",
		"merge_readiness_state": "DRAFT_PR_OPEN",
	}); err != nil {
		t.Fatal(err)
	}
	enriched, _ := m.GetRun(ctx, detail.Run.ID)
	var enrichedMerge *HumanTask
	for i := range enriched.Tasks {
		if enriched.Tasks[i].Kind == TaskMergeAttest && enriched.Tasks[i].Status == StatusOpen {
			enrichedMerge = &enriched.Tasks[i]
		}
	}
	if enrichedMerge == nil || enrichedMerge.BoundHash != "def456" {
		t.Fatalf("github head should rebind attestation, bound=%v", enrichedMerge)
	}
	attested, err := m.AnswerTask(ctx, enrichedMerge.ID, "po@example.com", json.RawMessage(`{"attested":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if attested.Run.Status != StatusCompleted {
		t.Fatalf("attested run=%s", attested.Run.Status)
	}
	if len(attested.Jobs) != 1 {
		t.Fatalf("merge attestation must not enqueue another job, got %d", len(attested.Jobs))
	}
}

func TestStaleMergeCommitInvalidates(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "draft pr")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	runner, _ := m.RegisterRunner(ctx, "po@example.com", "devbox", nil, nil)
	job, _ := m.ClaimJob(ctx, runner.ID)
	_, _ = m.CompleteJob(ctx, job.ID, runner.ID, json.RawMessage(`{"prUrl":"https://github.com/acme/app/pull/1","headSha":"abc123"}`))
	run, _ := m.GetRun(ctx, detail.Run.ID)
	var merge *HumanTask
	for i := range run.Tasks {
		if run.Tasks[i].Kind == TaskMergeAttest {
			merge = &run.Tasks[i]
		}
	}
	if merge == nil {
		t.Fatal("missing merge task")
	}
	m.mu.Lock()
	m.tasks[merge.ID].Payload = MergeAttestPayload("https://github.com/acme/app/pull/1", "zzz999", "", nil)
	m.mu.Unlock()
	if _, err := m.AnswerTask(ctx, merge.ID, "po@example.com", json.RawMessage(`{"attested":true}`)); !errors.Is(err, ErrStaleBound) {
		t.Fatalf("got %v", err)
	}
}

func TestForeignRunnerCannotComplete(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "draft pr")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	a, _ := m.RegisterRunner(ctx, "po@example.com", "a", nil, nil)
	b, _ := m.RegisterRunner(ctx, "other@example.com", "b", nil, nil)
	job, _ := m.ClaimJob(ctx, a.ID)
	if _, err := m.CompleteJob(ctx, job.ID, b.ID, json.RawMessage(`{}`)); !errors.Is(err, ErrForbidden) {
		t.Fatalf("got %v", err)
	}
}

func TestHashStable(t *testing.T) {
	if HashText(" a ") != HashText("a") {
		t.Fatal("hash should trim")
	}
	_ = uuid.Nil
}

func TestConfirmRiskyEventOpensInboxTask(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "draft pr")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	runner, _ := m.RegisterRunner(ctx, "po@example.com", "devbox", nil, nil)
	job, _ := m.ClaimJob(ctx, runner.ID)
	if err := m.AppendJobEvent(ctx, job.ID, runner.ID, EventConfirmRisky, json.RawMessage(`{"conversationId":"conv-1"}`)); err != nil {
		t.Fatal(err)
	}
	run, err := m.GetRun(ctx, detail.Run.ID)
	if err != nil {
		t.Fatal(err)
	}
	if run.Run.Status != StatusWaitingForHuman {
		t.Fatalf("status=%s", run.Run.Status)
	}
	var risky *HumanTask
	for i := range run.Tasks {
		if run.Tasks[i].Kind == TaskConfirmRisky && run.Tasks[i].Status == StatusOpen {
			risky = &run.Tasks[i]
		}
	}
	if risky == nil {
		t.Fatal("expected open CONFIRM_RISKY task")
	}
	var payload map[string]any
	if err := json.Unmarshal(risky.Payload, &payload); err != nil {
		t.Fatal(err)
	}
	if payload["conversationId"] != "conv-1" {
		t.Fatalf("payload=%s", risky.Payload)
	}
	if payload["risk"] != "HIGH" {
		t.Fatalf("risk=%v", payload["risk"])
	}
	if len(run.Events) == 0 {
		t.Fatal("expected job events on the run")
	}
	if len(run.Jobs) != 1 {
		t.Fatalf("jobs=%d", len(run.Jobs))
	}
	after, err := m.AnswerTask(ctx, risky.ID, "po@example.com", json.RawMessage(`{"accept":true,"reason":"ok"}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(after.Jobs) != 1 {
		t.Fatalf("answering CONFIRM_RISKY must not enqueue a second job, got %d", len(after.Jobs))
	}
	if after.Run.Status != StatusRunning {
		t.Fatalf("status=%s", after.Run.Status)
	}
	hb, err := m.HeartbeatJob(ctx, job.ID, runner.ID, "conv-1")
	if err != nil {
		t.Fatal(err)
	}
	if string(hb.ConfirmationAnswer) != `{"accept":true,"reason":"ok"}` {
		t.Fatalf("confirmationAnswer=%s", hb.ConfirmationAnswer)
	}
}

func TestTier2OpensPlanGateBeforeJob(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, err := m.StartRun(ctx, 1, "po@example.com", "Add a REST API for walk logs")
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.WorkTier != 2 {
		t.Fatalf("tier=%d", detail.Run.WorkTier)
	}
	after, err := m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{"approved":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(after.Jobs) != 0 {
		t.Fatalf("plan gate must precede the job, jobs=%d", len(after.Jobs))
	}
	var plan *HumanTask
	for i := range after.Tasks {
		if after.Tasks[i].Kind == TaskGateApproval && after.Tasks[i].Status == StatusOpen && GateFromPayload(after.Tasks[i].Payload) == "G-PLAN" {
			plan = &after.Tasks[i]
		}
	}
	if plan == nil {
		t.Fatal("expected open G-PLAN")
	}
	ready, err := m.AnswerTask(ctx, plan.ID, "po@example.com", json.RawMessage(`{"approved":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(ready.Jobs) != 1 {
		t.Fatalf("jobs after G-PLAN=%d", len(ready.Jobs))
	}
}

func TestTier3OpensSecurityGateBeforeJob(t *testing.T) {
	m := NewMemory()
	ctx := context.Background()
	detail, err := m.StartRun(ctx, 1, "po@example.com", "Store payment tokens and PII")
	if err != nil {
		t.Fatal(err)
	}
	if detail.Run.WorkTier != 3 {
		t.Fatalf("tier=%d", detail.Run.WorkTier)
	}
	afterGroom, err := m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{"approved":true}`))
	if err != nil {
		t.Fatal(err)
	}
	var plan *HumanTask
	for i := range afterGroom.Tasks {
		if afterGroom.Tasks[i].Status == StatusOpen && GateFromPayload(afterGroom.Tasks[i].Payload) == "G-PLAN" {
			plan = &afterGroom.Tasks[i]
		}
	}
	if plan == nil {
		t.Fatal("expected G-PLAN before G-SEC")
	}
	afterPlan, err := m.AnswerTask(ctx, plan.ID, "po@example.com", json.RawMessage(`{"approved":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(afterPlan.Jobs) != 0 {
		t.Fatalf("security gate must precede the job, jobs=%d", len(afterPlan.Jobs))
	}
	var sec *HumanTask
	for i := range afterPlan.Tasks {
		if afterPlan.Tasks[i].Status == StatusOpen && GateFromPayload(afterPlan.Tasks[i].Payload) == "G-SEC" {
			sec = &afterPlan.Tasks[i]
		}
	}
	if sec == nil {
		t.Fatal("expected open G-SEC")
	}
	if sec.AssignedRole != RoleSecurityChampion {
		t.Fatalf("role=%s", sec.AssignedRole)
	}
	ready, err := m.AnswerTask(ctx, sec.ID, "po@example.com", json.RawMessage(`{"approved":true}`))
	if err != nil {
		t.Fatal(err)
	}
	if len(ready.Jobs) != 1 {
		t.Fatalf("jobs after G-SEC=%d", len(ready.Jobs))
	}
}

func TestConfirmRiskyExpiryFailsJob(t *testing.T) {
	m := NewMemory()
	now := time.Date(2026, 9, 18, 12, 0, 0, 0, time.UTC)
	m.now = func() time.Time { return now }
	ctx := context.Background()
	detail, _ := m.StartRun(ctx, 1, "po@example.com", "draft pr")
	_, _ = m.AnswerTask(ctx, detail.Tasks[0].ID, "po@example.com", json.RawMessage(`{}`))
	runner, _ := m.RegisterRunner(ctx, "po@example.com", "devbox", nil, nil)
	job, _ := m.ClaimJob(ctx, runner.ID)
	_ = m.AppendJobEvent(ctx, job.ID, runner.ID, EventConfirmRisky, json.RawMessage(`{"conversationId":"c1"}`))
	now = now.Add(25 * time.Hour)
	m.now = func() time.Time { return now }
	if err := m.Reconcile(ctx, now); err != nil {
		t.Fatal(err)
	}
	run, _ := m.GetRun(ctx, detail.Run.ID)
	if run.Run.Status != StatusFailed {
		t.Fatalf("run=%s", run.Run.Status)
	}
	if len(run.Jobs) != 1 || run.Jobs[0].Status != StatusFailed {
		t.Fatalf("job=%+v", run.Jobs)
	}
	var risky *HumanTask
	for i := range run.Tasks {
		if run.Tasks[i].Kind == TaskConfirmRisky {
			risky = &run.Tasks[i]
		}
	}
	if risky == nil || risky.Status != StatusInvalidated {
		t.Fatalf("confirm=%+v", risky)
	}
}
