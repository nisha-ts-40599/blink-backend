package workflow

import (
	"encoding/json"
	"time"

	"github.com/google/uuid"
)

type Principal struct {
	ID          uuid.UUID `json:"id"`
	Kind        string    `json:"kind"`
	Email       string    `json:"email,omitempty"`
	DisplayName string    `json:"displayName"`
	AgentID     string    `json:"agentId,omitempty"`
}

type Member struct {
	ProjectID   int64     `json:"projectId"`
	PrincipalID uuid.UUID `json:"principalId"`
	RoleID      string    `json:"roleId"`
}

type Run struct {
	ID               uuid.UUID `json:"id"`
	ProjectID        int64     `json:"projectId"`
	Status           string    `json:"status"`
	CurrentStage     string    `json:"currentStage"`
	RequirementHash  string    `json:"requirementHash,omitempty"`
	RequirementText  string    `json:"requirementText,omitempty"`
	WorkTier         int       `json:"workTier"`
	RequiredGates    []string  `json:"requiredGates,omitempty"`
	CreatedByEmail   string    `json:"createdByEmail"`
	CreatedAt        time.Time `json:"createdAt"`
	UpdatedAt        time.Time `json:"updatedAt"`
	OpenTaskCount    int       `json:"openTaskCount,omitempty"`
	WaitingForRunner bool      `json:"waitingForRunner,omitempty"`
}

type Step struct {
	ID             uuid.UUID       `json:"id"`
	RunID          uuid.UUID       `json:"runId"`
	Stage          string          `json:"stage"`
	Status         string          `json:"status"`
	ConversationID string          `json:"conversationId,omitempty"`
	WorkspaceRef   string          `json:"workspaceRef,omitempty"`
	OperationID    string          `json:"operationId"`
	Result         json.RawMessage `json:"result,omitempty"`
}

type HumanTask struct {
	ID              uuid.UUID       `json:"id"`
	RunID           uuid.UUID       `json:"runId"`
	Kind            string          `json:"kind"`
	Status          string          `json:"status"`
	AssignedRole    string          `json:"assignedRole,omitempty"`
	BoundHash       string          `json:"boundHash,omitempty"`
	Payload         json.RawMessage `json:"payload"`
	Answer          json.RawMessage `json:"answer,omitempty"`
	AnsweredByEmail string          `json:"answeredByEmail,omitempty"`
	CreatedAt       time.Time       `json:"createdAt"`
}

type Runner struct {
	ID            uuid.UUID       `json:"id"`
	OwnerEmail    string          `json:"ownerEmail"`
	Name          string          `json:"name"`
	Status        string          `json:"status"`
	Capabilities  json.RawMessage `json:"capabilities"`
	Allowlist     json.RawMessage `json:"allowlist"`
	LastHeartbeat *time.Time      `json:"lastHeartbeatAt,omitempty"`
	CreatedAt     time.Time       `json:"createdAt"`
	Token         string          `json:"token,omitempty"`
}

type Job struct {
	ID             uuid.UUID       `json:"id"`
	RunID          uuid.UUID       `json:"runId"`
	StepID         uuid.UUID       `json:"stepId,omitempty"`
	RunnerID       uuid.UUID       `json:"runnerId,omitempty"`
	Status         string          `json:"status"`
	Placement      string          `json:"placement"`
	Prompt         string          `json:"prompt,omitempty"`
	RepoAllowlist  json.RawMessage `json:"repoAllowlist"`
	LeaseUntil     *time.Time      `json:"leaseUntil,omitempty"`
	Result         json.RawMessage `json:"result,omitempty"`
	OperationID          string          `json:"operationId"`
	ConversationID       string          `json:"conversationId,omitempty"`
	ConfirmationAnswer   json.RawMessage `json:"confirmationAnswer,omitempty"`
}

type JobEvent struct {
	ID        int64           `json:"id"`
	JobID     uuid.UUID       `json:"jobId"`
	EventType string          `json:"eventType"`
	Payload   json.RawMessage `json:"payload"`
	CreatedAt time.Time       `json:"createdAt"`
}

type Artifact struct {
	ID          uuid.UUID `json:"id"`
	RunID       uuid.UUID `json:"runId"`
	Kind        string    `json:"kind"`
	ContentHash string    `json:"contentHash"`
	URI         string    `json:"uri,omitempty"`
	CreatedAt   time.Time `json:"createdAt"`
}

type RunDetail struct {
	Run       Run         `json:"run"`
	Tasks     []HumanTask `json:"tasks"`
	Jobs      []Job       `json:"jobs"`
	Steps     []Step      `json:"steps"`
	Events    []JobEvent  `json:"events,omitempty"`
	Artifacts []Artifact  `json:"artifacts,omitempty"`
}
