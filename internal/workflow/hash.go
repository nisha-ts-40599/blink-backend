package workflow

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"strings"
	"time"
)

const (
	KindHuman = "human"
	KindAgent = "agent"

	StatusCreated          = "CREATED"
	StatusWaitingForHuman  = "WAITING_FOR_HUMAN"
	StatusWaitingForRunner = "WAITING_FOR_RUNNER"
	StatusRunning          = "RUNNING"
	StatusCompleted        = "COMPLETED"
	StatusFailed           = "FAILED"
	StatusCancelled        = "CANCELLED"
	StatusQueued           = "QUEUED"
	StatusClaimed          = "CLAIMED"
	StatusOpen             = "OPEN"
	StatusAnswered         = "ANSWERED"
	StatusInvalidated      = "INVALIDATED"
	StatusOffline          = "OFFLINE"
	StatusOnline           = "ONLINE"

	StageRequirement    = "REQUIREMENT"
	StagePlan           = "PLAN"
	StageSecurity       = "SECURITY"
	StageImplementation = "IMPLEMENTATION"
	StageMerge          = "MERGE"

	TaskGateApproval   = "GATE_APPROVAL"
	TaskConfirmRisky   = "CONFIRM_RISKY"
	TaskMergeAttest    = "MERGE_ATTESTATION"

	EventConfirmRisky = "confirm_risky"

	PlacementLocalDocker = "LOCAL_DOCKER"

	AgentImplementation = "implementation"

	RoleProductOwner      = "product_owner"
	RoleTechLead          = "tech_lead"
	RoleSecurityChampion  = "security_champion"
	RoleBackendDeveloper  = "backend_developer"
	OpStartRequirement    = "start-requirement"
	OpApproveRequirement  = "approve-requirement"
	OpImplement           = "implement"

	LeaseTTL         = 90 * time.Second
	ConfirmRiskyTTL  = 24 * time.Hour
)

var (
	ErrNotFound   = errors.New("not found")
	ErrConflict   = errors.New("conflict")
	ErrForbidden  = errors.New("forbidden")
	ErrInvalid    = errors.New("invalid")
	ErrNoJob      = errors.New("no job")
	ErrStaleBound = errors.New("bound hash changed; approval invalidated")
)

func HashText(s string) string {
	sum := sha256.Sum256([]byte(strings.TrimSpace(s)))
	return hex.EncodeToString(sum[:])
}

func HashToken(raw string) string {
	sum := sha256.Sum256([]byte(raw))
	return hex.EncodeToString(sum[:])
}

func NewToken() (string, error) {
	buf := make([]byte, 32)
	if _, err := rand.Read(buf); err != nil {
		return "", err
	}
	return base64.RawURLEncoding.EncodeToString(buf), nil
}

func NormalizeRole(code string) string {
	s := strings.ToLower(strings.TrimSpace(code))
	s = strings.ReplaceAll(s, "-", "_")
	switch s {
	case "product_owner", "po":
		return RoleProductOwner
	case "tech_lead", "techlead":
		return RoleTechLead
	case "qa_lead", "qalead":
		return "qa_lead"
	case "security_champion", "security":
		return RoleSecurityChampion
	case "backend_developer", "backend":
		return RoleBackendDeveloper
	default:
		return s
	}
}
