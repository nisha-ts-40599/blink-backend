package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/go-chi/chi/v5"
	"github.com/google/uuid"
	"github.com/nisha-ts-40599/blink-backend/internal/auth"
	"github.com/nisha-ts-40599/blink-backend/internal/githubgit"
	"github.com/nisha-ts-40599/blink-backend/internal/workflow"
)

const runnerKey ctxKey = "runner"

func (s *Server) requireRunner(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if s.wf == nil {
			writeJSON(w, http.StatusServiceUnavailable, map[string]string{"message": "workflow store is not configured"})
			return
		}
		token := strings.TrimSpace(strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer"))
		token = strings.TrimSpace(token)
		runner, err := s.wf.RunnerByToken(r.Context(), token)
		if err != nil {
			writeJSON(w, http.StatusUnauthorized, map[string]string{"message": "invalid runner token"})
			return
		}
		ctx := context.WithValue(r.Context(), runnerKey, runner)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func runnerFrom(r *http.Request) *workflow.Runner {
	v, _ := r.Context().Value(runnerKey).(*workflow.Runner)
	return v
}

func (s *Server) importProjectMembers(ctx context.Context, projectID int64) {
	if s.wf == nil || s.proj == nil {
		return
	}
	p, err := s.proj.Get(ctx, projectID)
	if err != nil {
		return
	}
	rows := make([]workflow.StakeholderRow, 0, len(p.Stakeholders))
	for _, st := range p.Stakeholders {
		rows = append(rows, workflow.StakeholderRow{Name: st.Name, Email: st.Email, Role: st.RoleCode})
	}
	_ = s.wf.ImportStakeholders(ctx, projectID, rows)
}

func (s *Server) registerRunner(w http.ResponseWriter, r *http.Request) {
	if s.wf == nil {
		writeJSON(w, http.StatusServiceUnavailable, map[string]string{"message": "workflow store is not configured"})
		return
	}
	var body struct {
		Name         string          `json:"name"`
		Capabilities json.RawMessage `json:"capabilities"`
		Allowlist    json.RawMessage `json:"allowlist"`
	}
	_ = readJSON(r, &body)
	runner, err := s.wf.RegisterRunner(r.Context(), sessionEmail(r), body.Name, body.Capabilities, body.Allowlist)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, runner)
}

func (s *Server) listMyRunners(w http.ResponseWriter, r *http.Request) {
	list, err := s.wf.ListRunners(r.Context(), sessionEmail(r))
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"runners": list})
}

func (s *Server) startProjectRun(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeErr(w, err)
		return
	}
	if _, err := s.proj.RequireOwned(r.Context(), id, sessionEmail(r)); err != nil {
		writeErr(w, err)
		return
	}
	var body struct {
		RequirementText string `json:"requirementText"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	s.importProjectMembers(r.Context(), id)
	detail, err := s.wf.StartRun(r.Context(), id, sessionEmail(r), body.RequirementText)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, detail)
}

func (s *Server) listProjectRuns(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeErr(w, err)
		return
	}
	if _, err := s.proj.RequireOwned(r.Context(), id, sessionEmail(r)); err != nil {
		writeErr(w, err)
		return
	}
	list, err := s.wf.ListRuns(r.Context(), id)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"runs": list})
}

func (s *Server) getProjectRun(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(chi.URLParam(r, "id"), 10, 64)
	if err != nil {
		writeErr(w, err)
		return
	}
	if _, err := s.proj.RequireOwned(r.Context(), id, sessionEmail(r)); err != nil {
		writeErr(w, err)
		return
	}
	runID, err := uuid.Parse(chi.URLParam(r, "runId"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": "invalid run id"})
		return
	}
	detail, err := s.wf.GetRun(r.Context(), runID)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	if detail.Run.ProjectID != id {
		writeJSON(w, http.StatusNotFound, map[string]string{"message": "run not found"})
		return
	}
	s.refreshMergeFromGitHub(r.Context(), detail)
	if refreshed, err := s.wf.GetRun(r.Context(), runID); err == nil {
		detail = refreshed
	}
	writeJSON(w, http.StatusOK, detail)
}

func (s *Server) listMyTasks(w http.ResponseWriter, r *http.Request) {
	var projectID int64
	if raw := strings.TrimSpace(r.URL.Query().Get("projectId")); raw != "" {
		projectID, _ = strconv.ParseInt(raw, 10, 64)
	}
	list, err := s.wf.ListOpenTasks(r.Context(), sessionEmail(r), projectID)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, map[string]any{"tasks": list})
}

func (s *Server) answerTask(w http.ResponseWriter, r *http.Request) {
	taskID, err := uuid.Parse(chi.URLParam(r, "taskId"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": "invalid task id"})
		return
	}
	var body json.RawMessage
	_ = readJSON(r, &body)
	detail, err := s.wf.AnswerTask(r.Context(), taskID, sessionEmail(r), body)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, detail)
}

func (s *Server) runnerHeartbeat(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Status    string          `json:"status"`
		Allowlist json.RawMessage `json:"allowlist"`
	}
	_ = readJSON(r, &body)
	out, err := s.wf.HeartbeatRunner(r.Context(), runnerFrom(r).ID, body.Status, body.Allowlist)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	_ = s.wf.Reconcile(r.Context(), time.Now().UTC())
	writeJSON(w, http.StatusOK, out)
}

func (s *Server) runnerClaim(w http.ResponseWriter, r *http.Request) {
	_ = s.wf.Reconcile(r.Context(), time.Now().UTC())
	job, err := s.wf.ClaimJob(r.Context(), runnerFrom(r).ID)
	if errors.Is(err, workflow.ErrNoJob) {
		w.WriteHeader(http.StatusNoContent)
		return
	}
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func (s *Server) runnerJobHeartbeat(w http.ResponseWriter, r *http.Request) {
	jobID, err := uuid.Parse(chi.URLParam(r, "jobId"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": "invalid job id"})
		return
	}
	var body struct {
		ConversationID string `json:"conversationId"`
	}
	_ = readJSON(r, &body)
	job, err := s.wf.HeartbeatJob(r.Context(), jobID, runnerFrom(r).ID, body.ConversationID)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func (s *Server) runnerJobEvent(w http.ResponseWriter, r *http.Request) {
	jobID, err := uuid.Parse(chi.URLParam(r, "jobId"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": "invalid job id"})
		return
	}
	var body struct {
		EventType string          `json:"eventType"`
		Payload   json.RawMessage `json:"payload"`
	}
	if err := readJSON(r, &body); err != nil {
		writeErr(w, err)
		return
	}
	if strings.TrimSpace(body.EventType) == "" {
		body.EventType = "progress"
	}
	if err := s.wf.AppendJobEvent(r.Context(), jobID, runnerFrom(r).ID, body.EventType, body.Payload); err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusAccepted, map[string]string{"status": "ok"})
}

func (s *Server) runnerJobComplete(w http.ResponseWriter, r *http.Request) {
	jobID, err := uuid.Parse(chi.URLParam(r, "jobId"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": "invalid job id"})
		return
	}
	var body json.RawMessage
	_ = readJSON(r, &body)
	job, err := s.wf.CompleteJob(r.Context(), jobID, runnerFrom(r).ID, body)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	s.enrichMergeFromGitHub(r.Context(), job)
	writeJSON(w, http.StatusOK, job)
}

func (s *Server) refreshMergeFromGitHub(ctx context.Context, detail *workflow.RunDetail) {
	if detail == nil {
		return
	}
	var result json.RawMessage
	for _, task := range detail.Tasks {
		if task.Kind == workflow.TaskMergeAttest && task.Status == workflow.StatusOpen && len(task.Payload) > 0 {
			result = task.Payload
			break
		}
	}
	if len(result) == 0 {
		for _, job := range detail.Jobs {
			if u := workflow.PRURL(job.Result); u != "" {
				result = job.Result
				break
			}
		}
	}
	if len(result) == 0 {
		return
	}
	s.enrichMergeSnapshot(ctx, detail.Run.ID, detail.Run.ProjectID, result)
}

func (s *Server) enrichMergeFromGitHub(ctx context.Context, job *workflow.Job) {
	if job == nil {
		return
	}
	detail, err := s.wf.GetRun(ctx, job.RunID)
	if err != nil {
		return
	}
	s.enrichMergeSnapshot(ctx, job.RunID, detail.Run.ProjectID, job.Result)
}

func (s *Server) enrichMergeSnapshot(ctx context.Context, runID uuid.UUID, projectID int64, result json.RawMessage) {
	if s.integ == nil {
		return
	}
	prURL := workflow.PRURL(result)
	if prURL == "" {
		return
	}
	owner, repo, number, err := githubgit.ParsePullURL(prURL)
	if err != nil {
		return
	}
	token, _, ok := s.integ.GitHubCreds(ctx, projectID)
	if !ok {
		return
	}
	gh := githubgit.New(token)
	snap, err := gh.PullSnapshot(ctx, owner, repo, number)
	if err != nil || snap == nil {
		return
	}
	sha := snap.HeadSHA
	if sha == "" {
		sha = workflow.HeadSHA(result)
	}
	ci, _, _ := gh.CombinedStatus(ctx, owner, repo, sha)
	_ = s.wf.EnrichOpenMerge(ctx, runID, map[string]any{
		"registered_pr":         snap.URL,
		"reviewed_commit":       sha,
		"tested_commit":         sha,
		"headSha":               sha,
		"draft":                 snap.Draft,
		"mergeable_state":       snap.MergeableState,
		"ciStatus":              ci,
		"merge_readiness_state": workflow.MergeReadinessState(snap.Draft, snap.MergeableState, ci),
	})
}

func (s *Server) runnerJobFail(w http.ResponseWriter, r *http.Request) {
	jobID, err := uuid.Parse(chi.URLParam(r, "jobId"))
	if err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": "invalid job id"})
		return
	}
	var body struct {
		Message string `json:"message"`
	}
	_ = readJSON(r, &body)
	job, err := s.wf.FailJob(r.Context(), jobID, runnerFrom(r).ID, body.Message)
	if err != nil {
		writeWorkflowErr(w, err)
		return
	}
	writeJSON(w, http.StatusOK, job)
}

func writeWorkflowErr(w http.ResponseWriter, err error) {
	switch {
	case errors.Is(err, workflow.ErrNotFound):
		writeJSON(w, http.StatusNotFound, map[string]string{"message": err.Error()})
	case errors.Is(err, workflow.ErrForbidden), errors.Is(err, auth.ErrForbidden):
		writeJSON(w, http.StatusForbidden, map[string]string{"message": err.Error()})
	case errors.Is(err, workflow.ErrConflict), errors.Is(err, workflow.ErrStaleBound):
		writeJSON(w, http.StatusConflict, map[string]string{"message": err.Error()})
	case errors.Is(err, workflow.ErrInvalid):
		writeJSON(w, http.StatusBadRequest, map[string]string{"message": err.Error()})
	default:
		writeErr(w, err)
	}
}
