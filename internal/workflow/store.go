package workflow

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

type StakeholderRow struct {
	Name  string
	Email string
	Role  string
}

type StakeholderSource interface {
	ListStakeholders(ctx context.Context, projectID int64) ([]StakeholderRow, error)
}

type Service interface {
	EnsureHuman(ctx context.Context, email, name string) (Principal, error)
	EnsureAgent(ctx context.Context, agentID, name string) (Principal, error)
	ImportStakeholders(ctx context.Context, projectID int64, rows []StakeholderRow) error
	RegisterRunner(ctx context.Context, ownerEmail, name string, capabilities, allowlist json.RawMessage) (Runner, error)
	HeartbeatRunner(ctx context.Context, runnerID uuid.UUID, status string, allowlist json.RawMessage) (*Runner, error)
	RunnerByToken(ctx context.Context, token string) (*Runner, error)
	ListRunners(ctx context.Context, ownerEmail string) ([]Runner, error)
	StartRun(ctx context.Context, projectID int64, ownerEmail, requirementText string) (*RunDetail, error)
	GetRun(ctx context.Context, runID uuid.UUID) (*RunDetail, error)
	ListRuns(ctx context.Context, projectID int64) ([]Run, error)
	ListOpenTasks(ctx context.Context, email string, projectID int64) ([]HumanTask, error)
	AnswerTask(ctx context.Context, taskID uuid.UUID, email string, answer json.RawMessage) (*RunDetail, error)
	ClaimJob(ctx context.Context, runnerID uuid.UUID) (*Job, error)
	HeartbeatJob(ctx context.Context, jobID, runnerID uuid.UUID, conversationID string) (*Job, error)
	AppendJobEvent(ctx context.Context, jobID, runnerID uuid.UUID, eventType string, payload json.RawMessage) error
	CompleteJob(ctx context.Context, jobID, runnerID uuid.UUID, result json.RawMessage) (*Job, error)
	FailJob(ctx context.Context, jobID, runnerID uuid.UUID, message string) (*Job, error)
	Reconcile(ctx context.Context, now time.Time) error
	EnrichOpenMerge(ctx context.Context, runID uuid.UUID, extra map[string]any) error
}

type Memory struct {
	mu         sync.Mutex
	now        func() time.Time
	principals map[string]Principal // email lower or agent:id
	members    map[string]Member    // projectID|principalID
	runs       map[uuid.UUID]*Run
	steps      map[uuid.UUID]*Step
	tasks      map[uuid.UUID]*HumanTask
	runners    map[uuid.UUID]*Runner
	tokenIndex map[string]uuid.UUID
	jobs       map[uuid.UUID]*Job
	events     []JobEvent
	artifacts  []Artifact
	outbox     []string
	eventSeq   int64
}

func NewMemory() *Memory {
	return &Memory{
		now:        time.Now,
		principals: map[string]Principal{},
		members:    map[string]Member{},
		runs:       map[uuid.UUID]*Run{},
		steps:      map[uuid.UUID]*Step{},
		tasks:      map[uuid.UUID]*HumanTask{},
		runners:    map[uuid.UUID]*Runner{},
		tokenIndex: map[string]uuid.UUID{},
		jobs:       map[uuid.UUID]*Job{},
	}
}

func (m *Memory) EnsureHuman(_ context.Context, email, name string) (Principal, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.ensureHumanLocked(email, name)
}

func (m *Memory) ensureHumanLocked(email, name string) (Principal, error) {
	key := "human:" + strings.ToLower(strings.TrimSpace(email))
	if p, ok := m.principals[key]; ok {
		return p, nil
	}
	p := Principal{ID: uuid.New(), Kind: KindHuman, Email: strings.ToLower(strings.TrimSpace(email)), DisplayName: strings.TrimSpace(name)}
	if p.DisplayName == "" {
		p.DisplayName = p.Email
	}
	m.principals[key] = p
	return p, nil
}

func (m *Memory) EnsureAgent(_ context.Context, agentID, name string) (Principal, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.ensureAgentLocked(agentID, name)
}

func (m *Memory) ensureAgentLocked(agentID, name string) (Principal, error) {
	key := "agent:" + agentID
	if p, ok := m.principals[key]; ok {
		return p, nil
	}
	p := Principal{ID: uuid.New(), Kind: KindAgent, AgentID: agentID, DisplayName: name}
	m.principals[key] = p
	return p, nil
}

func (m *Memory) ImportStakeholders(_ context.Context, projectID int64, rows []StakeholderRow) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, row := range rows {
		email := strings.ToLower(strings.TrimSpace(row.Email))
		if email == "" {
			continue
		}
		p, _ := m.ensureHumanLocked(email, row.Name)
		m.members[fmt.Sprintf("%d|%s", projectID, p.ID)] = Member{ProjectID: projectID, PrincipalID: p.ID, RoleID: NormalizeRole(row.Role)}
	}
	agent, _ := m.ensureAgentLocked(AgentImplementation, "Implementation agent")
	m.members[fmt.Sprintf("%d|%s", projectID, agent.ID)] = Member{ProjectID: projectID, PrincipalID: agent.ID, RoleID: RoleBackendDeveloper}
	return nil
}

func (m *Memory) RegisterRunner(_ context.Context, ownerEmail, name string, capabilities, allowlist json.RawMessage) (Runner, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	token, err := NewToken()
	if err != nil {
		return Runner{}, err
	}
	if len(capabilities) == 0 {
		capabilities = json.RawMessage(`["implementation"]`)
	}
	if len(allowlist) == 0 {
		allowlist = json.RawMessage(`[]`)
	}
	r := &Runner{
		ID:           uuid.New(),
		OwnerEmail:   strings.ToLower(strings.TrimSpace(ownerEmail)),
		Name:         strings.TrimSpace(name),
		Status:       StatusOffline,
		Capabilities: capabilities,
		Allowlist:    allowlist,
		CreatedAt:    m.now(),
		Token:        token,
	}
	if r.Name == "" {
		r.Name = "local-docker"
	}
	m.runners[r.ID] = r
	m.tokenIndex[HashToken(token)] = r.ID
	out := *r
	return out, nil
}

func (m *Memory) HeartbeatRunner(_ context.Context, runnerID uuid.UUID, status string, allowlist json.RawMessage) (*Runner, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	r, ok := m.runners[runnerID]
	if !ok {
		return nil, ErrNotFound
	}
	if status == "" {
		status = StatusOnline
	}
	r.Status = status
	now := m.now()
	r.LastHeartbeat = &now
	if len(allowlist) > 0 {
		r.Allowlist = allowlist
	}
	out := *r
	out.Token = ""
	return &out, nil
}

func (m *Memory) RunnerByToken(_ context.Context, token string) (*Runner, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	id, ok := m.tokenIndex[HashToken(token)]
	if !ok {
		return nil, ErrNotFound
	}
	r := *m.runners[id]
	r.Token = ""
	return &r, nil
}

func (m *Memory) ListRunners(_ context.Context, ownerEmail string) ([]Runner, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	owner := strings.ToLower(strings.TrimSpace(ownerEmail))
	out := []Runner{}
	for _, r := range m.runners {
		if r.OwnerEmail == owner {
			copy := *r
			copy.Token = ""
			out = append(out, copy)
		}
	}
	return out, nil
}

func (m *Memory) StartRun(_ context.Context, projectID int64, ownerEmail, requirementText string) (*RunDetail, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	text := strings.TrimSpace(requirementText)
	if text == "" {
		return nil, fmt.Errorf("%w: requirementText is required", ErrInvalid)
	}
	now := m.now()
	owner, _ := m.ensureHumanLocked(ownerEmail, ownerEmail)
	m.members[fmt.Sprintf("%d|%s", projectID, owner.ID)] = Member{ProjectID: projectID, PrincipalID: owner.ID, RoleID: RoleProductOwner}
	tier := ClassifyWorkTier(text)
	run := &Run{
		ID:              uuid.New(),
		ProjectID:       projectID,
		Status:          StatusWaitingForHuman,
		CurrentStage:    StageRequirement,
		RequirementHash: HashText(text),
		RequirementText: text,
		WorkTier:        tier,
		RequiredGates:   RequiredGates(tier),
		CreatedByEmail:  strings.ToLower(strings.TrimSpace(ownerEmail)),
		CreatedAt:       now,
		UpdatedAt:       now,
	}
	m.runs[run.ID] = run
	payload, _ := json.Marshal(map[string]any{
		"gate":            "G-GROOM",
		"requirementHash": run.RequirementHash,
		"workTier":        run.WorkTier,
		"requiredGates":   run.RequiredGates,
		"summary":         "Approve the requirement wording before implementation.",
	})
	task := &HumanTask{
		ID:           uuid.New(),
		RunID:        run.ID,
		Kind:         TaskGateApproval,
		Status:       StatusOpen,
		AssignedRole: RoleProductOwner,
		BoundHash:    run.RequirementHash,
		Payload:      payload,
		CreatedAt:    now,
	}
	m.tasks[task.ID] = task
	m.outbox = append(m.outbox, "RUN_STARTED")
	return m.detailLocked(run.ID)
}

func (m *Memory) GetRun(_ context.Context, runID uuid.UUID) (*RunDetail, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.detailLocked(runID)
}

func (m *Memory) ListRuns(_ context.Context, projectID int64) ([]Run, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := []Run{}
	for _, r := range m.runs {
		if r.ProjectID == projectID {
			copy := *r
			out = append(out, copy)
		}
	}
	return out, nil
}

func (m *Memory) ListOpenTasks(_ context.Context, email string, projectID int64) ([]HumanTask, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	email = strings.ToLower(strings.TrimSpace(email))
	out := []HumanTask{}
	for _, t := range m.tasks {
		if t.Status != StatusOpen {
			continue
		}
		run := m.runs[t.RunID]
		if run == nil {
			continue
		}
		if projectID != 0 && run.ProjectID != projectID {
			continue
		}
		if run.CreatedByEmail != email {
			continue
		}
		out = append(out, *t)
	}
	return out, nil
}

func (m *Memory) AnswerTask(_ context.Context, taskID uuid.UUID, email string, answer json.RawMessage) (*RunDetail, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	task, ok := m.tasks[taskID]
	if !ok {
		return nil, ErrNotFound
	}
	if task.Status != StatusOpen {
		return nil, fmt.Errorf("%w: task is not open", ErrConflict)
	}
	run := m.runs[task.RunID]
	if run == nil {
		return nil, ErrNotFound
	}
	if !strings.EqualFold(run.CreatedByEmail, email) {
		return nil, ErrForbidden
	}
	if task.Kind == TaskGateApproval && task.BoundHash != "" && task.BoundHash != run.RequirementHash {
		task.Status = StatusInvalidated
		return nil, ErrStaleBound
	}
	if task.Kind == TaskMergeAttest && task.BoundHash != "" {
		current := HeadSHA(task.Payload)
		if current == "" {
			current = m.headSHALocked(run.ID)
		}
		if current != "" && current != task.BoundHash {
			task.Status = StatusInvalidated
			return nil, ErrStaleBound
		}
	}
	now := m.now()
	task.Status = StatusAnswered
	task.AnsweredByEmail = strings.ToLower(strings.TrimSpace(email))
	task.Answer = answer
	if task.Kind == TaskConfirmRisky {
		run.Status = StatusRunning
		run.UpdatedAt = now
		m.outbox = append(m.outbox, "CONFIRMATION_ANSWERED")
		return m.detailLocked(run.ID)
	}
	if task.Kind == TaskMergeAttest {
		run.Status = StatusCompleted
		run.CurrentStage = StageMerge
		run.UpdatedAt = now
		m.outbox = append(m.outbox, "MERGE_ATTESTED")
		return m.detailLocked(run.ID)
	}
	if task.Kind == TaskGateApproval {
		next := NextRequiredGate(run.RequiredGates, m.answeredGatesLocked(run.ID))
		if next != "" {
			m.openGateLocked(run, next, now)
			return m.detailLocked(run.ID)
		}
	}
	m.enqueueImplementationLocked(run, now)
	return m.detailLocked(run.ID)
}

func (m *Memory) answeredGatesLocked(runID uuid.UUID) []string {
	var out []string
	for _, t := range m.tasks {
		if t.RunID == runID && t.Kind == TaskGateApproval && t.Status == StatusAnswered {
			out = append(out, GateFromPayload(t.Payload))
		}
	}
	return out
}

func (m *Memory) openGateLocked(run *Run, gate string, now time.Time) {
	payload, _ := json.Marshal(map[string]any{
		"gate":            gate,
		"requirementHash": run.RequirementHash,
		"workTier":        run.WorkTier,
		"requiredGates":   run.RequiredGates,
		"summary":         GateSummary(gate),
	})
	id := uuid.New()
	m.tasks[id] = &HumanTask{
		ID:           id,
		RunID:        run.ID,
		Kind:         TaskGateApproval,
		Status:       StatusOpen,
		AssignedRole: RoleForGate(gate),
		BoundHash:    run.RequirementHash,
		Payload:      payload,
		CreatedAt:    now,
	}
	run.Status = StatusWaitingForHuman
	run.CurrentStage = StageForGate(gate)
	run.UpdatedAt = now
	m.outbox = append(m.outbox, "GATE_OPENED")
}

func (m *Memory) headSHALocked(runID uuid.UUID) string {
	for _, job := range m.jobs {
		if job.RunID == runID {
			if sha := HeadSHA(job.Result); sha != "" {
				return sha
			}
		}
	}
	return ""
}

func (m *Memory) enqueueImplementationLocked(run *Run, now time.Time) {
	step := &Step{
		ID:          uuid.New(),
		RunID:       run.ID,
		Stage:       StageImplementation,
		Status:      StatusQueued,
		OperationID: OpImplement,
	}
	m.steps[step.ID] = step
	job := &Job{
		ID:            uuid.New(),
		RunID:         run.ID,
		StepID:        step.ID,
		Status:        StatusQueued,
		Placement:     PlacementLocalDocker,
		Prompt:        run.RequirementText,
		RepoAllowlist: json.RawMessage(`[]`),
		OperationID:   OpImplement,
	}
	m.jobs[job.ID] = job
	run.Status = StatusWaitingForRunner
	run.CurrentStage = StageImplementation
	run.UpdatedAt = now
	m.outbox = append(m.outbox, "IMPLEMENTATION_QUEUED")
}

func (m *Memory) Reconcile(_ context.Context, now time.Time) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.reconcileLocked(now)
	return nil
}

func (m *Memory) reconcileLocked(now time.Time) {
	for _, job := range m.jobs {
		if (job.Status == StatusClaimed || job.Status == StatusRunning) && job.LeaseUntil != nil && now.After(*job.LeaseUntil) {
			job.Status = StatusWaitingForRunner
			if run := m.runs[job.RunID]; run != nil {
				run.Status = StatusWaitingForRunner
				run.UpdatedAt = now
			}
		}
	}
	for _, t := range m.tasks {
		if t.Kind != TaskConfirmRisky || t.Status != StatusOpen {
			continue
		}
		if now.Sub(t.CreatedAt) <= ConfirmRiskyTTL {
			continue
		}
		t.Status = StatusInvalidated
		for _, job := range m.jobs {
			if job.RunID != t.RunID {
				continue
			}
			if job.Status == StatusRunning || job.Status == StatusClaimed || job.Status == StatusWaitingForRunner {
				job.Status = StatusFailed
				job.Result = json.RawMessage(`{"message":"ConfirmRisky expired"}`)
			}
		}
		if run := m.runs[t.RunID]; run != nil {
			run.Status = StatusFailed
			run.UpdatedAt = now
		}
	}
}

func (m *Memory) ClaimJob(_ context.Context, runnerID uuid.UUID) (*Job, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if _, ok := m.runners[runnerID]; !ok {
		return nil, ErrNotFound
	}
	now := m.now()
	m.reconcileLocked(now)
	var candidate *Job
	for _, job := range m.jobs {
		if !claimableBy(job, runnerID) {
			continue
		}
		candidate = job
		break
	}
	if candidate == nil {
		return nil, ErrNoJob
	}
	lease := now.Add(LeaseTTL)
	candidate.Status = StatusClaimed
	candidate.RunnerID = runnerID
	candidate.LeaseUntil = &lease
	if run := m.runs[candidate.RunID]; run != nil {
		run.Status = StatusRunning
		run.UpdatedAt = now
	}
	out := *candidate
	out.ConfirmationAnswer = m.latestConfirmAnswerLocked(candidate.RunID)
	return &out, nil
}

func claimableBy(job *Job, runnerID uuid.UUID) bool {
	switch job.Status {
	case StatusQueued:
		return job.RunnerID == uuid.Nil
	case StatusWaitingForRunner:
		return job.RunnerID == runnerID
	case StatusClaimed, StatusRunning:
		return job.RunnerID == runnerID
	default:
		return false
	}
}

func (m *Memory) HeartbeatJob(_ context.Context, jobID, runnerID uuid.UUID, conversationID string) (*Job, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	job, ok := m.jobs[jobID]
	if !ok {
		return nil, ErrNotFound
	}
	if job.RunnerID != runnerID {
		return nil, ErrForbidden
	}
	now := m.now()
	lease := now.Add(LeaseTTL)
	job.LeaseUntil = &lease
	job.Status = StatusRunning
	if conversationID != "" {
		if step := m.steps[job.StepID]; step != nil {
			step.ConversationID = conversationID
			job.ConversationID = conversationID
		}
	}
	out := *job
	out.ConfirmationAnswer = m.latestConfirmAnswerLocked(job.RunID)
	return &out, nil
}

func (m *Memory) latestConfirmAnswerLocked(runID uuid.UUID) json.RawMessage {
	var best *HumanTask
	for _, t := range m.tasks {
		if t.RunID != runID || t.Kind != TaskConfirmRisky || t.Status != StatusAnswered {
			continue
		}
		if best == nil || t.CreatedAt.After(best.CreatedAt) {
			best = t
		}
	}
	if best == nil {
		return nil
	}
	return best.Answer
}

func (m *Memory) AppendJobEvent(_ context.Context, jobID, runnerID uuid.UUID, eventType string, payload json.RawMessage) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	job, ok := m.jobs[jobID]
	if !ok {
		return ErrNotFound
	}
	if job.RunnerID != runnerID {
		return ErrForbidden
	}
	m.eventSeq++
	if len(payload) == 0 {
		payload = json.RawMessage(`{}`)
	}
	if eventType == EventConfirmRisky {
		payload = EnrichConfirmRisky(payload, job.ConversationID)
	}
	m.events = append(m.events, JobEvent{ID: m.eventSeq, JobID: jobID, EventType: eventType, Payload: payload, CreatedAt: m.now()})
	if eventType == EventConfirmRisky {
		m.openConfirmRiskyLocked(job, payload)
	}
	return nil
}

func (m *Memory) openConfirmRiskyLocked(job *Job, payload json.RawMessage) {
	for _, t := range m.tasks {
		if t.RunID == job.RunID && t.Kind == TaskConfirmRisky && t.Status == StatusOpen {
			return
		}
	}
	now := m.now()
	id := uuid.New()
	m.tasks[id] = &HumanTask{
		ID:           id,
		RunID:        job.RunID,
		Kind:         TaskConfirmRisky,
		Status:       StatusOpen,
		AssignedRole: RoleProductOwner,
		Payload:      payload,
		CreatedAt:    now,
	}
	if run := m.runs[job.RunID]; run != nil {
		run.Status = StatusWaitingForHuman
		run.UpdatedAt = now
	}
	m.outbox = append(m.outbox, "CONFIRM_RISKY")
}

func (m *Memory) CompleteJob(_ context.Context, jobID, runnerID uuid.UUID, result json.RawMessage) (*Job, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	job, ok := m.jobs[jobID]
	if !ok {
		return nil, ErrNotFound
	}
	if job.RunnerID != runnerID {
		return nil, ErrForbidden
	}
	now := m.now()
	job.Status = StatusCompleted
	job.Result = result
	if step := m.steps[job.StepID]; step != nil {
		step.Status = StatusCompleted
		step.Result = result
	}
	if run := m.runs[job.RunID]; run != nil {
		m.openMergeAttestLocked(run, result, now)
		m.artifacts = append(m.artifacts, artifactFromResult(run.ID, result, now))
	}
	out := *job
	return &out, nil
}

func (m *Memory) openMergeAttestLocked(run *Run, result json.RawMessage, now time.Time) {
	for _, t := range m.tasks {
		if t.RunID == run.ID && t.Kind == TaskMergeAttest && t.Status == StatusOpen {
			return
		}
	}
	sha := HeadSHA(result)
	payload := MergeAttestPayload(PRURL(result), sha, "", nil)
	id := uuid.New()
	m.tasks[id] = &HumanTask{
		ID:           id,
		RunID:        run.ID,
		Kind:         TaskMergeAttest,
		Status:       StatusOpen,
		AssignedRole: RoleProductOwner,
		BoundHash:    sha,
		Payload:      payload,
		CreatedAt:    now,
	}
	run.Status = StatusWaitingForHuman
	run.CurrentStage = StageMerge
	run.UpdatedAt = now
	m.outbox = append(m.outbox, "MERGE_ATTESTATION")
}

func (m *Memory) FailJob(_ context.Context, jobID, runnerID uuid.UUID, message string) (*Job, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	job, ok := m.jobs[jobID]
	if !ok {
		return nil, ErrNotFound
	}
	if job.RunnerID != runnerID {
		return nil, ErrForbidden
	}
	now := m.now()
	job.Status = StatusFailed
	payload, _ := json.Marshal(map[string]string{"message": message})
	job.Result = payload
	if run := m.runs[job.RunID]; run != nil {
		run.Status = StatusFailed
		run.UpdatedAt = now
	}
	out := *job
	return &out, nil
}

func (m *Memory) detailLocked(runID uuid.UUID) (*RunDetail, error) {
	run, ok := m.runs[runID]
	if !ok {
		return nil, ErrNotFound
	}
	d := &RunDetail{Run: *run, Tasks: []HumanTask{}, Jobs: []Job{}, Steps: []Step{}, Events: []JobEvent{}}
	open := 0
	waiting := false
	for _, t := range m.tasks {
		if t.RunID == runID {
			d.Tasks = append(d.Tasks, *t)
			if t.Status == StatusOpen {
				open++
			}
		}
	}
	for _, j := range m.jobs {
		if j.RunID == runID {
			copy := *j
			if step := m.steps[j.StepID]; step != nil {
				copy.ConversationID = step.ConversationID
			}
			d.Jobs = append(d.Jobs, copy)
			if j.Status == StatusQueued || j.Status == StatusWaitingForRunner {
				waiting = true
			}
		}
	}
	for _, s := range m.steps {
		if s.RunID == runID {
			d.Steps = append(d.Steps, *s)
		}
	}
	jobIDs := map[uuid.UUID]struct{}{}
	for _, j := range d.Jobs {
		jobIDs[j.ID] = struct{}{}
	}
	for _, ev := range m.events {
		if _, ok := jobIDs[ev.JobID]; ok {
			d.Events = append(d.Events, ev)
		}
	}
	d.Run.OpenTaskCount = open
	d.Run.WaitingForRunner = waiting || d.Run.Status == StatusWaitingForRunner
	for _, a := range m.artifacts {
		if a.RunID == runID {
			d.Artifacts = append(d.Artifacts, a)
		}
	}
	return d, nil
}

func artifactFromResult(runID uuid.UUID, result json.RawMessage, now time.Time) Artifact {
	sum := sha256.Sum256(result)
	return Artifact{
		ID:          uuid.New(),
		RunID:       runID,
		Kind:        "implementation",
		ContentHash: hex.EncodeToString(sum[:]),
		URI:         PRURL(result),
		CreatedAt:   now,
	}
}

func (m *Memory) EnrichOpenMerge(_ context.Context, runID uuid.UUID, extra map[string]any) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, t := range m.tasks {
		if t.RunID == runID && t.Kind == TaskMergeAttest && t.Status == StatusOpen {
			t.Payload = mergeJSON(t.Payload, extra)
			if sha := ExtraHeadSHA(extra); sha != "" {
				t.BoundHash = sha
			}
			return nil
		}
	}
	return ErrNotFound
}

func mergeJSON(raw json.RawMessage, extra map[string]any) json.RawMessage {
	body := map[string]any{}
	if len(raw) > 0 {
		_ = json.Unmarshal(raw, &body)
	}
	for k, v := range extra {
		if v != nil {
			body[k] = v
		}
	}
	out, err := json.Marshal(body)
	if err != nil {
		return raw
	}
	return out
}

// Postgres is the Neon-backed implementation.

type Postgres struct {
	pool *pgxpool.Pool
	now  func() time.Time
}

func NewPostgres(pool *pgxpool.Pool) *Postgres {
	return &Postgres{pool: pool, now: time.Now}
}

func (p *Postgres) EnsureHuman(ctx context.Context, email, name string) (Principal, error) {
	email = strings.ToLower(strings.TrimSpace(email))
	name = strings.TrimSpace(name)
	if name == "" {
		name = email
	}
	var pr Principal
	err := p.scanPrincipal(ctx, `SELECT id, kind, COALESCE(email,''), display_name, COALESCE(agent_id,'') FROM principal WHERE lower(email)=lower($1)`, email, &pr)
	if err == nil {
		return pr, nil
	}
	if !errorsIsNoRows(err) {
		return Principal{}, err
	}
	_, err = p.pool.Exec(ctx, `INSERT INTO principal (id, kind, email, display_name) VALUES ($1,'human',$2,$3)`, uuid.New(), email, name)
	if err != nil {
		if scanErr := p.scanPrincipal(ctx, `SELECT id, kind, COALESCE(email,''), display_name, COALESCE(agent_id,'') FROM principal WHERE lower(email)=lower($1)`, email, &pr); scanErr == nil {
			return pr, nil
		}
		return Principal{}, err
	}
	if err := p.scanPrincipal(ctx, `SELECT id, kind, COALESCE(email,''), display_name, COALESCE(agent_id,'') FROM principal WHERE lower(email)=lower($1)`, email, &pr); err != nil {
		return Principal{}, err
	}
	return pr, nil
}

func (p *Postgres) scanPrincipal(ctx context.Context, q string, arg any, pr *Principal) error {
	return p.pool.QueryRow(ctx, q, arg).Scan(&pr.ID, &pr.Kind, &pr.Email, &pr.DisplayName, &pr.AgentID)
}

func (p *Postgres) EnsureAgent(ctx context.Context, agentID, name string) (Principal, error) {
	id := uuid.New()
	_, err := p.pool.Exec(ctx, `
		INSERT INTO principal (id, kind, display_name, agent_id)
		VALUES ($1,'agent',$2,$3)
		ON CONFLICT (agent_id) WHERE agent_id IS NOT NULL DO NOTHING
	`, id, name, agentID)
	if err != nil {
		_, _ = p.pool.Exec(ctx, `INSERT INTO principal (id, kind, display_name, agent_id) VALUES ($1,'agent',$2,$3) ON CONFLICT DO NOTHING`, id, name, agentID)
	}
	var pr Principal
	err = p.pool.QueryRow(ctx, `
		SELECT id, kind, COALESCE(email,''), display_name, COALESCE(agent_id,'')
		FROM principal WHERE agent_id=$1
	`, agentID).Scan(&pr.ID, &pr.Kind, &pr.Email, &pr.DisplayName, &pr.AgentID)
	return pr, err
}

func (p *Postgres) ImportStakeholders(ctx context.Context, projectID int64, rows []StakeholderRow) error {
	for _, row := range rows {
		email := strings.ToLower(strings.TrimSpace(row.Email))
		if email == "" {
			continue
		}
		pr, err := p.EnsureHuman(ctx, email, row.Name)
		if err != nil {
			return err
		}
		_, err = p.pool.Exec(ctx, `
			INSERT INTO project_member (project_id, principal_id, role_id)
			VALUES ($1,$2,$3)
			ON CONFLICT (project_id, principal_id) DO UPDATE SET role_id=EXCLUDED.role_id
		`, projectID, pr.ID, NormalizeRole(row.Role))
		if err != nil {
			return err
		}
	}
	agent, err := p.EnsureAgent(ctx, AgentImplementation, "Implementation agent")
	if err != nil {
		return err
	}
	_, err = p.pool.Exec(ctx, `
		INSERT INTO project_member (project_id, principal_id, role_id)
		VALUES ($1,$2,$3)
		ON CONFLICT (project_id, principal_id) DO NOTHING
	`, projectID, agent.ID, RoleBackendDeveloper)
	return err
}

func (p *Postgres) RegisterRunner(ctx context.Context, ownerEmail, name string, capabilities, allowlist json.RawMessage) (Runner, error) {
	token, err := NewToken()
	if err != nil {
		return Runner{}, err
	}
	if len(capabilities) == 0 {
		capabilities = json.RawMessage(`["implementation"]`)
	}
	if len(allowlist) == 0 {
		allowlist = json.RawMessage(`[]`)
	}
	if strings.TrimSpace(name) == "" {
		name = "local-docker"
	}
	r := Runner{
		ID:           uuid.New(),
		OwnerEmail:   strings.ToLower(strings.TrimSpace(ownerEmail)),
		Name:         name,
		Status:       StatusOffline,
		Capabilities: capabilities,
		Allowlist:    allowlist,
		CreatedAt:    p.now(),
		Token:        token,
	}
	_, err = p.pool.Exec(ctx, `
		INSERT INTO runner (id, owner_email, name, token_hash, status, capabilities, allowlist, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
	`, r.ID, r.OwnerEmail, r.Name, HashToken(token), r.Status, []byte(capabilities), []byte(allowlist), r.CreatedAt)
	return r, err
}

func (p *Postgres) HeartbeatRunner(ctx context.Context, runnerID uuid.UUID, status string, allowlist json.RawMessage) (*Runner, error) {
	if status == "" {
		status = StatusOnline
	}
	if len(allowlist) == 0 {
		_, err := p.pool.Exec(ctx, `UPDATE runner SET status=$2, last_heartbeat_at=NOW() WHERE id=$1`, runnerID, status)
		if err != nil {
			return nil, err
		}
	} else {
		_, err := p.pool.Exec(ctx, `UPDATE runner SET status=$2, last_heartbeat_at=NOW(), allowlist=$3 WHERE id=$1`, runnerID, status, []byte(allowlist))
		if err != nil {
			return nil, err
		}
	}
	return p.getRunner(ctx, runnerID)
}

func (p *Postgres) getRunner(ctx context.Context, id uuid.UUID) (*Runner, error) {
	var r Runner
	var caps, allow []byte
	var hb *time.Time
	err := p.pool.QueryRow(ctx, `
		SELECT id, owner_email, name, status, capabilities, allowlist, last_heartbeat_at, created_at
		FROM runner WHERE id=$1
	`, id).Scan(&r.ID, &r.OwnerEmail, &r.Name, &r.Status, &caps, &allow, &hb, &r.CreatedAt)
	if errorsIsNoRows(err) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	r.Capabilities = caps
	r.Allowlist = allow
	r.LastHeartbeat = hb
	return &r, nil
}

func (p *Postgres) RunnerByToken(ctx context.Context, token string) (*Runner, error) {
	var id uuid.UUID
	err := p.pool.QueryRow(ctx, `SELECT id FROM runner WHERE token_hash=$1`, HashToken(token)).Scan(&id)
	if errorsIsNoRows(err) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	return p.getRunner(ctx, id)
}

func (p *Postgres) ListRunners(ctx context.Context, ownerEmail string) ([]Runner, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT id, owner_email, name, status, capabilities, allowlist, last_heartbeat_at, created_at
		FROM runner WHERE lower(owner_email)=lower($1) ORDER BY created_at
	`, ownerEmail)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Runner{}
	for rows.Next() {
		var r Runner
		var caps, allow []byte
		if err := rows.Scan(&r.ID, &r.OwnerEmail, &r.Name, &r.Status, &caps, &allow, &r.LastHeartbeat, &r.CreatedAt); err != nil {
			return nil, err
		}
		r.Capabilities = caps
		r.Allowlist = allow
		out = append(out, r)
	}
	return out, rows.Err()
}

func (p *Postgres) StartRun(ctx context.Context, projectID int64, ownerEmail, requirementText string) (*RunDetail, error) {
	text := strings.TrimSpace(requirementText)
	if text == "" {
		return nil, fmt.Errorf("%w: requirementText is required", ErrInvalid)
	}
	owner, err := p.EnsureHuman(ctx, ownerEmail, ownerEmail)
	if err != nil {
		return nil, err
	}
	_, _ = p.pool.Exec(ctx, `
		INSERT INTO project_member (project_id, principal_id, role_id)
		VALUES ($1,$2,$3)
		ON CONFLICT (project_id, principal_id) DO NOTHING
	`, projectID, owner.ID, RoleProductOwner)
	_ = p.ImportStakeholders(ctx, projectID, nil)
	runID := uuid.New()
	hash := HashText(text)
	now := p.now()
	tier := ClassifyWorkTier(text)
	gates := RequiredGates(tier)
	gatesJSON, _ := json.Marshal(gates)
	_, err = p.pool.Exec(ctx, `
		INSERT INTO sdlc_run (id, project_id, status, current_stage, requirement_hash, requirement_text, work_tier, required_gates, created_by_email, created_at, updated_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$10)
	`, runID, projectID, StatusWaitingForHuman, StageRequirement, hash, text, tier, gatesJSON, strings.ToLower(strings.TrimSpace(ownerEmail)), now)
	if err != nil {
		return nil, err
	}
	payload, _ := json.Marshal(map[string]any{
		"gate":            "G-GROOM",
		"requirementHash": hash,
		"workTier":        tier,
		"requiredGates":   gates,
		"summary":         "Approve the requirement wording before implementation.",
	})
	_, err = p.pool.Exec(ctx, `
		INSERT INTO human_task (id, run_id, kind, status, assigned_role, bound_hash, payload_json, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
	`, uuid.New(), runID, TaskGateApproval, StatusOpen, RoleProductOwner, hash, payload, now)
	if err != nil {
		return nil, err
	}
	_, _ = p.pool.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'RUN_STARTED','{}')`, runID)
	return p.GetRun(ctx, runID)
}

func (p *Postgres) GetRun(ctx context.Context, runID uuid.UUID) (*RunDetail, error) {
	var run Run
	var gates []byte
	err := p.pool.QueryRow(ctx, `
		SELECT id, project_id, status, current_stage, COALESCE(requirement_hash,''), COALESCE(requirement_text,''), COALESCE(work_tier,1), COALESCE(required_gates, '[]'::jsonb), created_by_email, created_at, updated_at
		FROM sdlc_run WHERE id=$1
	`, runID).Scan(&run.ID, &run.ProjectID, &run.Status, &run.CurrentStage, &run.RequirementHash, &run.RequirementText, &run.WorkTier, &gates, &run.CreatedByEmail, &run.CreatedAt, &run.UpdatedAt)
	if errorsIsNoRows(err) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if run.WorkTier <= 0 {
		run.WorkTier = 1
	}
	run.RequiredGates = DecodeGates(gates)
	d := &RunDetail{Run: run, Tasks: []HumanTask{}, Jobs: []Job{}, Steps: []Step{}, Events: []JobEvent{}}
	trows, err := p.pool.Query(ctx, `
		SELECT id, run_id, kind, status, COALESCE(assigned_role,''), COALESCE(bound_hash,''), payload_json, answer_json, COALESCE(answered_by_email,''), created_at
		FROM human_task WHERE run_id=$1 ORDER BY created_at
	`, runID)
	if err != nil {
		return nil, err
	}
	defer trows.Close()
	open := 0
	for trows.Next() {
		var t HumanTask
		var payload, answer []byte
		if err := trows.Scan(&t.ID, &t.RunID, &t.Kind, &t.Status, &t.AssignedRole, &t.BoundHash, &payload, &answer, &t.AnsweredByEmail, &t.CreatedAt); err != nil {
			return nil, err
		}
		t.Payload = payload
		t.Answer = answer
		if t.Status == StatusOpen {
			open++
		}
		d.Tasks = append(d.Tasks, t)
	}
	trows.Close()
	d.Run.OpenTaskCount = open

	srows, err := p.pool.Query(ctx, `
		SELECT id, run_id, stage, status, COALESCE(conversation_id,''), COALESCE(workspace_ref,''), operation_id, result_json
		FROM sdlc_run_step WHERE run_id=$1
	`, runID)
	if err != nil {
		return nil, err
	}
	defer srows.Close()
	for srows.Next() {
		var s Step
		var result []byte
		if err := srows.Scan(&s.ID, &s.RunID, &s.Stage, &s.Status, &s.ConversationID, &s.WorkspaceRef, &s.OperationID, &result); err != nil {
			return nil, err
		}
		s.Result = result
		d.Steps = append(d.Steps, s)
	}
	srows.Close()

	jrows, err := p.pool.Query(ctx, `
		SELECT j.id, j.run_id, COALESCE(j.step_id, '00000000-0000-0000-0000-000000000000'), COALESCE(j.runner_id, '00000000-0000-0000-0000-000000000000'),
		       j.status, j.placement, COALESCE(j.prompt,''), j.repo_allowlist, j.lease_until, j.result_json, j.operation_id,
		       COALESCE(s.conversation_id,'')
		FROM runner_job j
		LEFT JOIN sdlc_run_step s ON s.id = j.step_id
		WHERE j.run_id=$1
	`, runID)
	if err != nil {
		return nil, err
	}
	defer jrows.Close()
	waiting := run.Status == StatusWaitingForRunner
	for jrows.Next() {
		var j Job
		var allow, result []byte
		if err := jrows.Scan(&j.ID, &j.RunID, &j.StepID, &j.RunnerID, &j.Status, &j.Placement, &j.Prompt, &allow, &j.LeaseUntil, &result, &j.OperationID, &j.ConversationID); err != nil {
			return nil, err
		}
		j.RepoAllowlist = allow
		j.Result = result
		if j.Status == StatusQueued || j.Status == StatusWaitingForRunner {
			waiting = true
		}
		d.Jobs = append(d.Jobs, j)
	}
	d.Run.WaitingForRunner = waiting
	if err := jrows.Err(); err != nil {
		return nil, err
	}
	erows, err := p.pool.Query(ctx, `
		SELECT e.id, e.job_id, e.event_type, e.payload_json, e.created_at
		FROM job_event e
		JOIN runner_job j ON j.id = e.job_id
		WHERE j.run_id=$1
		ORDER BY e.id
	`, runID)
	if err != nil {
		return nil, err
	}
	defer erows.Close()
	for erows.Next() {
		var ev JobEvent
		var payload []byte
		if err := erows.Scan(&ev.ID, &ev.JobID, &ev.EventType, &payload, &ev.CreatedAt); err != nil {
			return nil, err
		}
		ev.Payload = payload
		d.Events = append(d.Events, ev)
	}
	if err := erows.Err(); err != nil {
		return nil, err
	}
	arows, err := p.pool.Query(ctx, `
		SELECT id, run_id, kind, content_hash, COALESCE(uri,''), created_at
		FROM artifact WHERE run_id=$1 ORDER BY created_at
	`, runID)
	if err != nil {
		return nil, err
	}
	defer arows.Close()
	for arows.Next() {
		var a Artifact
		if err := arows.Scan(&a.ID, &a.RunID, &a.Kind, &a.ContentHash, &a.URI, &a.CreatedAt); err != nil {
			return nil, err
		}
		d.Artifacts = append(d.Artifacts, a)
	}
	return d, arows.Err()
}

func (p *Postgres) ListRuns(ctx context.Context, projectID int64) ([]Run, error) {
	rows, err := p.pool.Query(ctx, `
		SELECT id, project_id, status, current_stage, COALESCE(requirement_hash,''), COALESCE(work_tier,1), COALESCE(required_gates, '[]'::jsonb), created_by_email, created_at, updated_at
		FROM sdlc_run WHERE project_id=$1 ORDER BY created_at DESC
	`, projectID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []Run{}
	for rows.Next() {
		var r Run
		var gates []byte
		if err := rows.Scan(&r.ID, &r.ProjectID, &r.Status, &r.CurrentStage, &r.RequirementHash, &r.WorkTier, &gates, &r.CreatedByEmail, &r.CreatedAt, &r.UpdatedAt); err != nil {
			return nil, err
		}
		if r.WorkTier <= 0 {
			r.WorkTier = 1
		}
		r.RequiredGates = DecodeGates(gates)
		out = append(out, r)
	}
	return out, rows.Err()
}

func (p *Postgres) ListOpenTasks(ctx context.Context, email string, projectID int64) ([]HumanTask, error) {
	q := `
		SELECT t.id, t.run_id, t.kind, t.status, COALESCE(t.assigned_role,''), COALESCE(t.bound_hash,''), t.payload_json, t.created_at
		FROM human_task t
		JOIN sdlc_run r ON r.id = t.run_id
		WHERE t.status=$1 AND lower(r.created_by_email)=lower($2)
	`
	args := []any{StatusOpen, email}
	if projectID != 0 {
		q += ` AND r.project_id=$3`
		args = append(args, projectID)
	}
	q += ` ORDER BY t.created_at`
	rows, err := p.pool.Query(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := []HumanTask{}
	for rows.Next() {
		var t HumanTask
		var payload []byte
		if err := rows.Scan(&t.ID, &t.RunID, &t.Kind, &t.Status, &t.AssignedRole, &t.BoundHash, &payload, &t.CreatedAt); err != nil {
			return nil, err
		}
		t.Payload = payload
		out = append(out, t)
	}
	return out, rows.Err()
}

func (p *Postgres) AnswerTask(ctx context.Context, taskID uuid.UUID, email string, answer json.RawMessage) (*RunDetail, error) {
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	var runID uuid.UUID
	var status, kind, bound, createdBy, reqHash, requirementText string
	var workTier int
	var payload []byte
	err = tx.QueryRow(ctx, `
		SELECT t.run_id, t.status, t.kind, COALESCE(t.bound_hash,''), r.created_by_email, COALESCE(r.requirement_hash,''), COALESCE(r.requirement_text,''), COALESCE(r.work_tier,1), t.payload_json
		FROM human_task t JOIN sdlc_run r ON r.id=t.run_id
		WHERE t.id=$1 FOR UPDATE
	`, taskID).Scan(&runID, &status, &kind, &bound, &createdBy, &reqHash, &requirementText, &workTier, &payload)
	if errorsIsNoRows(err) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	if !strings.EqualFold(createdBy, email) {
		return nil, ErrForbidden
	}
	if status != StatusOpen {
		return nil, fmt.Errorf("%w: task is not open", ErrConflict)
	}
	stale := false
	switch kind {
	case TaskGateApproval:
		stale = bound != "" && bound != reqHash
	case TaskMergeAttest:
		if bound != "" {
			headSha := HeadSHA(payload)
			if headSha == "" {
				_ = tx.QueryRow(ctx, `
					SELECT COALESCE(result_json->>'headSha', result_json->>'head_sha', '')
					FROM runner_job WHERE run_id=$1 AND status=$2
					ORDER BY updated_at DESC LIMIT 1
				`, runID, StatusCompleted).Scan(&headSha)
			}
			if headSha != "" && headSha != bound {
				stale = true
			}
		}
	}
	if stale {
		_, _ = tx.Exec(ctx, `UPDATE human_task SET status=$2 WHERE id=$1`, taskID, StatusInvalidated)
		_ = tx.Commit(ctx)
		return nil, ErrStaleBound
	}
	if len(answer) == 0 {
		answer = json.RawMessage(`{"approved":true}`)
	}
	now := p.now()
	if _, err := tx.Exec(ctx, `
		UPDATE human_task SET status=$2, answered_by_email=$3, answer_json=$4, answered_at=$5 WHERE id=$1
	`, taskID, StatusAnswered, strings.ToLower(strings.TrimSpace(email)), []byte(answer), now); err != nil {
		return nil, err
	}
	if kind == TaskConfirmRisky {
		if _, err := tx.Exec(ctx, `UPDATE sdlc_run SET status=$2, updated_at=$3 WHERE id=$1`, runID, StatusRunning, now); err != nil {
			return nil, err
		}
		_, _ = tx.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'CONFIRMATION_ANSWERED','{}')`, runID)
		if err := tx.Commit(ctx); err != nil {
			return nil, err
		}
		return p.GetRun(ctx, runID)
	}
	if kind == TaskMergeAttest {
		if _, err := tx.Exec(ctx, `UPDATE sdlc_run SET status=$2, current_stage=$3, updated_at=$4 WHERE id=$1`, runID, StatusCompleted, StageMerge, now); err != nil {
			return nil, err
		}
		_, _ = tx.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'MERGE_ATTESTED','{}')`, runID)
		if err := tx.Commit(ctx); err != nil {
			return nil, err
		}
		return p.GetRun(ctx, runID)
	}
	var gates []byte
	_ = tx.QueryRow(ctx, `SELECT COALESCE(required_gates, '[]'::jsonb) FROM sdlc_run WHERE id=$1`, runID).Scan(&gates)
	if kind == TaskGateApproval {
		var answered []string
		rows, qerr := tx.Query(ctx, `
			SELECT COALESCE(payload_json->>'gate','G-GROOM') FROM human_task
			WHERE run_id=$1 AND kind=$2 AND status=$3
		`, runID, TaskGateApproval, StatusAnswered)
		if qerr == nil {
			defer rows.Close()
			for rows.Next() {
				var g string
				if rows.Scan(&g) == nil {
					answered = append(answered, g)
				}
			}
			rows.Close()
		}
		next := NextRequiredGate(DecodeGates(gates), answered)
		if next != "" {
			gatePayload, _ := json.Marshal(map[string]any{
				"gate":            next,
				"requirementHash": reqHash,
				"workTier":        workTier,
				"requiredGates":   DecodeGates(gates),
				"summary":         GateSummary(next),
			})
			if _, err := tx.Exec(ctx, `
				INSERT INTO human_task (id, run_id, kind, status, assigned_role, bound_hash, payload_json, created_at)
				VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
			`, uuid.New(), runID, TaskGateApproval, StatusOpen, RoleForGate(next), reqHash, gatePayload, now); err != nil {
				return nil, err
			}
			if _, err := tx.Exec(ctx, `UPDATE sdlc_run SET status=$2, current_stage=$3, updated_at=$4 WHERE id=$1`, runID, StatusWaitingForHuman, StageForGate(next), now); err != nil {
				return nil, err
			}
			_, _ = tx.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'GATE_OPENED','{}')`, runID)
			if err := tx.Commit(ctx); err != nil {
				return nil, err
			}
			return p.GetRun(ctx, runID)
		}
	}
	stepID := uuid.New()
	if _, err := tx.Exec(ctx, `
		INSERT INTO sdlc_run_step (id, run_id, stage, status, operation_id, created_at, updated_at)
		VALUES ($1,$2,$3,$4,$5,$6,$6)
	`, stepID, runID, StageImplementation, StatusQueued, OpImplement, now); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `
		INSERT INTO runner_job (id, run_id, step_id, status, placement, prompt, repo_allowlist, operation_id, created_at, updated_at)
		VALUES ($1,$2,$3,$4,$5,$6,'[]',$7,$8,$8)
	`, uuid.New(), runID, stepID, StatusQueued, PlacementLocalDocker, requirementText, OpImplement, now); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `
		UPDATE sdlc_run SET status=$2, current_stage=$3, updated_at=$4 WHERE id=$1
	`, runID, StatusWaitingForRunner, StageImplementation, now); err != nil {
		return nil, err
	}
	_, _ = tx.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'IMPLEMENTATION_QUEUED','{}')`, runID)
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return p.GetRun(ctx, runID)
}

func (p *Postgres) Reconcile(ctx context.Context, now time.Time) error {
	_, err := p.pool.Exec(ctx, `
		UPDATE runner_job SET status=$1, updated_at=$2
		WHERE status IN ($3,$4) AND lease_until IS NOT NULL AND lease_until < $2
	`, StatusWaitingForRunner, now, StatusClaimed, StatusRunning)
	if err != nil {
		return err
	}
	_, err = p.pool.Exec(ctx, `
		UPDATE sdlc_run r SET status=$1, updated_at=$2
		FROM runner_job j
		WHERE j.run_id=r.id AND j.status=$1 AND r.status=$3
	`, StatusWaitingForRunner, now, StatusRunning)
	if err != nil {
		return err
	}
	expired, err := p.pool.Query(ctx, `
		UPDATE human_task SET status=$1
		WHERE kind=$2 AND status=$3 AND created_at < $4
		RETURNING run_id
	`, StatusInvalidated, TaskConfirmRisky, StatusOpen, now.Add(-ConfirmRiskyTTL))
	if err != nil {
		return err
	}
	defer expired.Close()
	seen := map[uuid.UUID]struct{}{}
	for expired.Next() {
		var runID uuid.UUID
		if expired.Scan(&runID) != nil {
			continue
		}
		if _, ok := seen[runID]; ok {
			continue
		}
		seen[runID] = struct{}{}
		failMsg, _ := json.Marshal(map[string]string{"message": "ConfirmRisky expired"})
		_, _ = p.pool.Exec(ctx, `
			UPDATE runner_job SET status=$2, result_json=$3, updated_at=$4
			WHERE run_id=$1 AND status IN ($5,$6,$7)
		`, runID, StatusFailed, failMsg, now, StatusRunning, StatusClaimed, StatusWaitingForRunner)
		_, _ = p.pool.Exec(ctx, `UPDATE sdlc_run SET status=$2, updated_at=$3 WHERE id=$1`, runID, StatusFailed, now)
	}
	return expired.Err()
}

func (p *Postgres) ClaimJob(ctx context.Context, runnerID uuid.UUID) (*Job, error) {
	now := p.now()
	_ = p.Reconcile(ctx, now)
	tx, err := p.pool.Begin(ctx)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback(ctx)
	var job Job
	var allow, result []byte
	err = tx.QueryRow(ctx, `
		SELECT id, run_id, COALESCE(step_id, '00000000-0000-0000-0000-000000000000'), COALESCE(runner_id, '00000000-0000-0000-0000-000000000000'),
		       status, placement, COALESCE(prompt,''), repo_allowlist, result_json, operation_id
		FROM runner_job
		WHERE (status=$1 AND runner_id IS NULL)
		   OR (status=$2 AND runner_id=$3)
		   OR (status IN ($4,$5) AND runner_id=$3 AND lease_until < $6)
		ORDER BY created_at
		FOR UPDATE SKIP LOCKED
		LIMIT 1
	`, StatusQueued, StatusWaitingForRunner, runnerID, StatusClaimed, StatusRunning, now).Scan(
		&job.ID, &job.RunID, &job.StepID, &job.RunnerID, &job.Status, &job.Placement, &job.Prompt, &allow, &result, &job.OperationID)
	if errorsIsNoRows(err) {
		return nil, ErrNoJob
	}
	if err != nil {
		return nil, err
	}
	lease := now.Add(LeaseTTL)
	if _, err := tx.Exec(ctx, `
		UPDATE runner_job SET status=$2, runner_id=$3, claimed_at=COALESCE(claimed_at,$4), heartbeat_at=$4, lease_until=$5, updated_at=$4
		WHERE id=$1
	`, job.ID, StatusClaimed, runnerID, now, lease); err != nil {
		return nil, err
	}
	if _, err := tx.Exec(ctx, `UPDATE sdlc_run SET status=$2, updated_at=$3 WHERE id=$1`, job.RunID, StatusRunning, now); err != nil {
		return nil, err
	}
	if err := tx.Commit(ctx); err != nil {
		return nil, err
	}
	return p.getJob(ctx, job.ID)
}

func (p *Postgres) HeartbeatJob(ctx context.Context, jobID, runnerID uuid.UUID, conversationID string) (*Job, error) {
	now := p.now()
	lease := now.Add(LeaseTTL)
	tag, err := p.pool.Exec(ctx, `
		UPDATE runner_job SET status=$3, heartbeat_at=$4, lease_until=$5, updated_at=$4
		WHERE id=$1 AND runner_id=$2
	`, jobID, runnerID, StatusRunning, now, lease)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 0 {
		return nil, ErrForbidden
	}
	if conversationID != "" {
		_, _ = p.pool.Exec(ctx, `
			UPDATE sdlc_run_step SET conversation_id=$2, updated_at=$3
			WHERE id=(SELECT step_id FROM runner_job WHERE id=$1)
		`, jobID, conversationID, now)
	}
	return p.getJob(ctx, jobID)
}

func (p *Postgres) getJob(ctx context.Context, id uuid.UUID) (*Job, error) {
	var j Job
	var allow, result []byte
	err := p.pool.QueryRow(ctx, `
		SELECT j.id, j.run_id, COALESCE(j.step_id, '00000000-0000-0000-0000-000000000000'), COALESCE(j.runner_id, '00000000-0000-0000-0000-000000000000'),
		       j.status, j.placement, COALESCE(j.prompt,''), j.repo_allowlist, j.lease_until, j.result_json, j.operation_id,
		       COALESCE(s.conversation_id,'')
		FROM runner_job j
		LEFT JOIN sdlc_run_step s ON s.id=j.step_id
		WHERE j.id=$1
	`, id).Scan(&j.ID, &j.RunID, &j.StepID, &j.RunnerID, &j.Status, &j.Placement, &j.Prompt, &allow, &j.LeaseUntil, &result, &j.OperationID, &j.ConversationID)
	if errorsIsNoRows(err) {
		return nil, ErrNotFound
	}
	if err != nil {
		return nil, err
	}
	j.RepoAllowlist = allow
	j.Result = result
	var confirm []byte
	_ = p.pool.QueryRow(ctx, `
		SELECT t.answer_json FROM human_task t
		WHERE t.run_id=$1 AND t.kind=$2 AND t.status=$3 AND t.answer_json IS NOT NULL
		ORDER BY t.answered_at DESC NULLS LAST, t.created_at DESC
		LIMIT 1
	`, j.RunID, TaskConfirmRisky, StatusAnswered).Scan(&confirm)
	if len(confirm) > 0 {
		j.ConfirmationAnswer = confirm
	}
	return &j, err
}

func (p *Postgres) AppendJobEvent(ctx context.Context, jobID, runnerID uuid.UUID, eventType string, payload json.RawMessage) error {
	var owner, runID uuid.UUID
	err := p.pool.QueryRow(ctx, `
		SELECT COALESCE(runner_id, '00000000-0000-0000-0000-000000000000'), run_id
		FROM runner_job WHERE id=$1
	`, jobID).Scan(&owner, &runID)
	if errorsIsNoRows(err) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	if owner != runnerID {
		return ErrForbidden
	}
	if len(payload) == 0 {
		payload = json.RawMessage(`{}`)
	}
	if eventType == EventConfirmRisky {
		var conv string
		_ = p.pool.QueryRow(ctx, `
			SELECT COALESCE(s.conversation_id,'')
			FROM runner_job j
			LEFT JOIN sdlc_run_step s ON s.id = j.step_id
			WHERE j.id=$1
		`, jobID).Scan(&conv)
		payload = EnrichConfirmRisky(payload, conv)
	}
	if _, err = p.pool.Exec(ctx, `INSERT INTO job_event (job_id, event_type, payload_json) VALUES ($1,$2,$3)`, jobID, eventType, []byte(payload)); err != nil {
		return err
	}
	if eventType != EventConfirmRisky {
		return nil
	}
	var existing uuid.UUID
	err = p.pool.QueryRow(ctx, `
		SELECT id FROM human_task WHERE run_id=$1 AND kind=$2 AND status=$3 LIMIT 1
	`, runID, TaskConfirmRisky, StatusOpen).Scan(&existing)
	if err == nil {
		return nil
	}
	if !errorsIsNoRows(err) {
		return err
	}
	now := p.now()
	if _, err = p.pool.Exec(ctx, `
		INSERT INTO human_task (id, run_id, kind, status, assigned_role, payload_json, created_at)
		VALUES ($1,$2,$3,$4,$5,$6,$7)
	`, uuid.New(), runID, TaskConfirmRisky, StatusOpen, RoleProductOwner, []byte(payload), now); err != nil {
		return err
	}
	_, _ = p.pool.Exec(ctx, `UPDATE sdlc_run SET status=$2, updated_at=$3 WHERE id=$1`, runID, StatusWaitingForHuman, now)
	_, _ = p.pool.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'CONFIRM_RISKY','{}')`, runID)
	return nil
}

func (p *Postgres) CompleteJob(ctx context.Context, jobID, runnerID uuid.UUID, result json.RawMessage) (*Job, error) {
	now := p.now()
	if len(result) == 0 {
		result = json.RawMessage(`{}`)
	}
	tag, err := p.pool.Exec(ctx, `
		UPDATE runner_job SET status=$3, result_json=$4, updated_at=$5
		WHERE id=$1 AND runner_id=$2
	`, jobID, runnerID, StatusCompleted, []byte(result), now)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 0 {
		return nil, ErrForbidden
	}
	_, _ = p.pool.Exec(ctx, `
		UPDATE sdlc_run_step SET status=$2, result_json=$3, updated_at=$4
		WHERE id=(SELECT step_id FROM runner_job WHERE id=$1)
	`, jobID, StatusCompleted, []byte(result), now)
	var runID uuid.UUID
	if err := p.pool.QueryRow(ctx, `SELECT run_id FROM runner_job WHERE id=$1`, jobID).Scan(&runID); err != nil {
		return p.getJob(ctx, jobID)
	}
	var existing uuid.UUID
	err = p.pool.QueryRow(ctx, `
		SELECT id FROM human_task WHERE run_id=$1 AND kind=$2 AND status=$3 LIMIT 1
	`, runID, TaskMergeAttest, StatusOpen).Scan(&existing)
	if errorsIsNoRows(err) {
		sha := HeadSHA(result)
		payload := MergeAttestPayload(PRURL(result), sha, "", nil)
		_, _ = p.pool.Exec(ctx, `
			INSERT INTO human_task (id, run_id, kind, status, assigned_role, bound_hash, payload_json, created_at)
			VALUES ($1,$2,$3,$4,$5,$6,$7,$8)
		`, uuid.New(), runID, TaskMergeAttest, StatusOpen, RoleProductOwner, sha, payload, now)
		_, _ = p.pool.Exec(ctx, `UPDATE sdlc_run SET status=$2, current_stage=$3, updated_at=$4 WHERE id=$1`, runID, StatusWaitingForHuman, StageMerge, now)
		_, _ = p.pool.Exec(ctx, `INSERT INTO event_outbox (run_id, event_type, payload_json) VALUES ($1,'MERGE_ATTESTATION','{}')`, runID)
		art := artifactFromResult(runID, result, now)
		_, _ = p.pool.Exec(ctx, `
			INSERT INTO artifact (id, run_id, kind, content_hash, uri, created_at)
			VALUES ($1,$2,$3,$4,$5,$6)
		`, art.ID, art.RunID, art.Kind, art.ContentHash, art.URI, art.CreatedAt)
	} else if err != nil {
		return nil, err
	}
	return p.getJob(ctx, jobID)
}

func (p *Postgres) FailJob(ctx context.Context, jobID, runnerID uuid.UUID, message string) (*Job, error) {
	now := p.now()
	payload, _ := json.Marshal(map[string]string{"message": message})
	tag, err := p.pool.Exec(ctx, `
		UPDATE runner_job SET status=$3, result_json=$4, updated_at=$5
		WHERE id=$1 AND runner_id=$2
	`, jobID, runnerID, StatusFailed, payload, now)
	if err != nil {
		return nil, err
	}
	if tag.RowsAffected() == 0 {
		return nil, ErrForbidden
	}
	_, _ = p.pool.Exec(ctx, `
		UPDATE sdlc_run SET status=$2, updated_at=$3
		WHERE id=(SELECT run_id FROM runner_job WHERE id=$1)
	`, jobID, StatusFailed, now)
	return p.getJob(ctx, jobID)
}

func (p *Postgres) EnrichOpenMerge(ctx context.Context, runID uuid.UUID, extra map[string]any) error {
	var payload []byte
	var taskID uuid.UUID
	err := p.pool.QueryRow(ctx, `
		SELECT id, payload_json FROM human_task
		WHERE run_id=$1 AND kind=$2 AND status=$3
		ORDER BY created_at DESC LIMIT 1
	`, runID, TaskMergeAttest, StatusOpen).Scan(&taskID, &payload)
	if errorsIsNoRows(err) {
		return ErrNotFound
	}
	if err != nil {
		return err
	}
	merged := mergeJSON(payload, extra)
	if sha := ExtraHeadSHA(extra); sha != "" {
		_, err = p.pool.Exec(ctx, `UPDATE human_task SET payload_json=$2, bound_hash=$3 WHERE id=$1`, taskID, []byte(merged), sha)
		return err
	}
	_, err = p.pool.Exec(ctx, `UPDATE human_task SET payload_json=$2 WHERE id=$1`, taskID, []byte(merged))
	return err
}

func errorsIsNoRows(err error) bool {
	return err != nil && (err.Error() == "no rows in result set" || err == pgx.ErrNoRows)
}
