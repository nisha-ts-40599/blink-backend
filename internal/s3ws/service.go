package s3ws

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strings"
	"sync"

	"github.com/aws/aws-sdk-go-v2/aws"
	"github.com/aws/aws-sdk-go-v2/credentials"
	"github.com/aws/aws-sdk-go-v2/service/s3"
	"github.com/aws/aws-sdk-go-v2/service/s3/types"
	"github.com/aws/smithy-go"
	"github.com/go-chi/chi/v5"
	"github.com/nisha-ts-40599/blink-backend/internal/config"
	"github.com/nisha-ts-40599/blink-backend/internal/project"
)

const (
	workspaceManifest = ".blink-workspace.json"
	kitComplete       = "automation_sdlc/.blink-kit-complete"
)

type Service struct {
	cfg    config.Config
	client *s3.Client
	once   sync.Once
	jobs   sync.Map // folder -> status string
}

func New(cfg config.Config) *Service {
	return &Service{cfg: cfg}
}

func (s *Service) Enabled() bool {
	return strings.TrimSpace(s.cfg.S3BucketName) != "" &&
		strings.TrimSpace(s.cfg.AWSAccessKeyID) != "" &&
		strings.TrimSpace(s.cfg.AWSSecretAccessKey) != ""
}

func (s *Service) enabled() bool { return s.Enabled() }

// FolderStatus returns the workspace status string for X-Blink-Folder-Status.
func (s *Service) FolderStatus(name string, id *int64) string {
	st := s.Status(name, id)
	if v, ok := st["workspaceStatus"].(string); ok && v != "" {
		return v
	}
	return ""
}

func (s *Service) s3() *s3.Client {
	s.once.Do(func() {
		s.client = s3.New(s3.Options{
			Region: s.cfg.AWSRegion,
			Credentials: credentials.NewStaticCredentialsProvider(
				strings.TrimSpace(s.cfg.AWSAccessKeyID),
				strings.TrimSpace(s.cfg.AWSSecretAccessKey),
				"",
			),
		})
	})
	return s.client
}

func (s *Service) bucket() string { return strings.TrimSpace(s.cfg.S3BucketName) }

func (s *Service) publicURL(folder string) string {
	base := strings.TrimSpace(s.cfg.S3PublicBaseURL)
	if base == "" {
		return folder + "/"
	}
	if !strings.HasSuffix(base, "/") {
		base += "/"
	}
	return base + folder + "/"
}

// ProvisionAsync uploads a .blink-workspace.json marker when S3 is configured; otherwise no-ops.
func (s *Service) ProvisionAsync(ctx context.Context, projectName string, id *int64) {
	if !s.enabled() || strings.TrimSpace(projectName) == "" {
		return
	}
	folder := project.Folder(projectName, id)
	s.jobs.Store(folder, "preparing")
	go func() {
		key := folder + "/" + workspaceManifest
		body, _ := json.Marshal(map[string]string{
			"projectName": projectName,
			"workspace":   folder,
		})
		_, err := s.s3().PutObject(context.Background(), &s3.PutObjectInput{
			Bucket:      aws.String(s.bucket()),
			Key:         aws.String(key),
			Body:        bytes.NewReader(body),
			ContentType: aws.String("application/json"),
		})
		if err != nil {
			s.jobs.Store(folder, "failed")
			return
		}
		s.jobs.Store(folder, "ready")
	}()
}

// Status returns workspaceKey, workspaceUrl, and workspaceStatus (ready/preparing/failed).
func (s *Service) Status(name string, id *int64) map[string]any {
	folder := project.Folder(name, id)
	out := map[string]any{
		"workspaceKey":    folder,
		"workspaceUrl":    s.publicURL(folder),
		"workspaceStatus": nil,
	}
	if !s.enabled() {
		return out
	}
	if v, ok := s.jobs.Load(folder); ok {
		if st, _ := v.(string); st != "" {
			out["workspaceStatus"] = st
			return out
		}
	}
	if s.hasObject(context.Background(), folder+"/"+kitComplete) || s.hasObject(context.Background(), folder+"/"+workspaceManifest) {
		out["workspaceStatus"] = "ready"
	}
	return out
}

func (s *Service) List(w http.ResponseWriter, r *http.Request) {
	if !s.enabled() {
		writeJSON(w, http.StatusOK, map[string]any{"enabled": false, "bucket": nil, "workspaces": []any{}})
		return
	}
	ctx := r.Context()
	var folders []string
	var token *string
	for {
		out, err := s.s3().ListObjectsV2(ctx, &s3.ListObjectsV2Input{
			Bucket:            aws.String(s.bucket()),
			Delimiter:         aws.String("/"),
			ContinuationToken: token,
		})
		if err != nil {
			writeErr(w, http.StatusBadGateway, "Could not list Blink S3 workspaces.")
			return
		}
		for _, p := range out.CommonPrefixes {
			prefix := aws.ToString(p.Prefix)
			if !strings.HasSuffix(prefix, "/") {
				continue
			}
			folder := strings.TrimSuffix(prefix, "/")
			if project.IsBlinkWorkspaceFolder(folder) {
				folders = append(folders, folder)
			}
		}
		if !aws.ToBool(out.IsTruncated) {
			break
		}
		token = out.NextContinuationToken
	}

	workspaces := make([]map[string]any, 0)
	for _, folder := range folders {
		if !s.isBlinkOwned(ctx, folder) {
			continue
		}
		kit := s.hasObject(ctx, folder+"/"+kitComplete)
		workspaces = append(workspaces, map[string]any{
			"folder":      folder,
			"projectId":   parseProjectID(folder),
			"url":         s.publicURL(folder),
			"objectCount": 0,
			"totalBytes":  0,
			"kitComplete": kit,
		})
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"enabled":    true,
		"bucket":     s.bucket(),
		"workspaces": workspaces,
	})
}

func (s *Service) DeleteOne(w http.ResponseWriter, r *http.Request) {
	if !s.enabled() {
		writeErr(w, http.StatusServiceUnavailable, "S3 workspace storage is not configured.")
		return
	}
	folder := strings.Trim(chi.URLParam(r, "folder"), "/")
	if !project.IsBlinkWorkspaceFolder(folder) {
		writeErr(w, http.StatusBadRequest, "Not a Blink workspace folder.")
		return
	}
	if !s.isBlinkOwned(r.Context(), folder) {
		writeErr(w, http.StatusBadRequest, "Folder is not a Blink-owned workspace (missing .blink-workspace.json / kit marker).")
		return
	}
	n, err := s.deletePrefix(r.Context(), folder+"/")
	if err != nil {
		writeErr(w, http.StatusBadGateway, "Could not delete workspace.")
		return
	}
	s.jobs.Delete(folder)
	writeJSON(w, http.StatusOK, map[string]any{
		"deletedFolders": 1,
		"deletedObjects": n,
		"folders":        []string{folder},
	})
}

func (s *Service) DeleteAll(w http.ResponseWriter, r *http.Request) {
	if !s.enabled() {
		writeErr(w, http.StatusServiceUnavailable, "S3 workspace storage is not configured.")
		return
	}
	// Reuse List logic by collecting owned folders.
	ctx := r.Context()
	var folders []string
	var token *string
	for {
		out, err := s.s3().ListObjectsV2(ctx, &s3.ListObjectsV2Input{
			Bucket:            aws.String(s.bucket()),
			Delimiter:         aws.String("/"),
			ContinuationToken: token,
		})
		if err != nil {
			writeErr(w, http.StatusBadGateway, "Could not list Blink S3 workspaces.")
			return
		}
		for _, p := range out.CommonPrefixes {
			prefix := aws.ToString(p.Prefix)
			folder := strings.TrimSuffix(prefix, "/")
			if project.IsBlinkWorkspaceFolder(folder) && s.isBlinkOwned(ctx, folder) {
				folders = append(folders, folder)
			}
		}
		if !aws.ToBool(out.IsTruncated) {
			break
		}
		token = out.NextContinuationToken
	}

	var objects int64
	deleted := make([]string, 0, len(folders))
	for _, folder := range folders {
		if !s.isBlinkOwned(ctx, folder) {
			continue
		}
		n, err := s.deletePrefix(ctx, folder+"/")
		if err != nil {
			continue
		}
		objects += n
		deleted = append(deleted, folder)
		s.jobs.Delete(folder)
	}
	writeJSON(w, http.StatusOK, map[string]any{
		"deletedFolders": len(deleted),
		"deletedObjects": objects,
		"folders":        deleted,
	})
}


func (s *Service) isBlinkOwned(ctx context.Context, folder string) bool {
	return s.hasObject(ctx, folder+"/"+workspaceManifest) || s.hasObject(ctx, folder+"/"+kitComplete)
}

func (s *Service) hasObject(ctx context.Context, key string) bool {
	_, err := s.s3().HeadObject(ctx, &s3.HeadObjectInput{
		Bucket: aws.String(s.bucket()),
		Key:    aws.String(key),
	})
	if err == nil {
		return true
	}
	var nf *types.NotFound
	if errors.As(err, &nf) {
		return false
	}
	var api smithy.APIError
	if errors.As(err, &api) {
		code := api.ErrorCode()
		if code == "NotFound" || code == "NoSuchKey" || code == "Forbidden" || code == "AccessDenied" {
			return false
		}
	}
	var httpErr interface{ HTTPStatusCode() int }
	if errors.As(err, &httpErr) {
		sc := httpErr.HTTPStatusCode()
		if sc == 403 || sc == 404 {
			return false
		}
	}
	return false
}

func (s *Service) deletePrefix(ctx context.Context, prefix string) (int64, error) {
	var deleted int64
	var token *string
	for {
		out, err := s.s3().ListObjectsV2(ctx, &s3.ListObjectsV2Input{
			Bucket:            aws.String(s.bucket()),
			Prefix:            aws.String(prefix),
			ContinuationToken: token,
		})
		if err != nil {
			return deleted, err
		}
		var objs []types.ObjectIdentifier
		for _, o := range out.Contents {
			if o.Key == nil || strings.TrimSpace(*o.Key) == "" {
				continue
			}
			objs = append(objs, types.ObjectIdentifier{Key: o.Key})
			if len(objs) == 1000 {
				if err := s.deleteBatch(ctx, objs); err != nil {
					return deleted, err
				}
				deleted += int64(len(objs))
				objs = objs[:0]
			}
		}
		if len(objs) > 0 {
			if err := s.deleteBatch(ctx, objs); err != nil {
				return deleted, err
			}
			deleted += int64(len(objs))
		}
		if !aws.ToBool(out.IsTruncated) {
			break
		}
		token = out.NextContinuationToken
	}
	return deleted, nil
}

func (s *Service) deleteBatch(ctx context.Context, objs []types.ObjectIdentifier) error {
	_, err := s.s3().DeleteObjects(ctx, &s3.DeleteObjectsInput{
		Bucket: aws.String(s.bucket()),
		Delete: &types.Delete{Objects: objs, Quiet: aws.Bool(true)},
	})
	return err
}

// OverlayFile is a relative workspace path + text content from the agent runtime.
type OverlayFile struct {
	Path    string
	Content string
}

// PutOverlayFiles writes agent overlay files under the project workspace prefix.
// Safe no-op when S3 is not configured. Skips empty/unsafe paths.
func (s *Service) PutOverlayFiles(ctx context.Context, projectName string, id *int64, files []OverlayFile) (int, error) {
	if !s.enabled() || strings.TrimSpace(projectName) == "" || len(files) == 0 {
		return 0, nil
	}
	folder := project.Folder(projectName, id)
	written := 0
	for _, f := range files {
		rel := strings.TrimSpace(strings.ReplaceAll(f.Path, "\\", "/"))
		rel = strings.TrimPrefix(rel, "/")
		if rel == "" || strings.Contains(rel, "..") {
			continue
		}
		key := folder + "/" + rel
		ct := "text/plain; charset=utf-8"
		lower := strings.ToLower(rel)
		switch {
		case strings.HasSuffix(lower, ".json"):
			ct = "application/json"
		case strings.HasSuffix(lower, ".yaml"), strings.HasSuffix(lower, ".yml"):
			ct = "application/yaml"
		case strings.HasSuffix(lower, ".md"):
			ct = "text/markdown; charset=utf-8"
		}
		body := []byte(f.Content)
		_, err := s.s3().PutObject(ctx, &s3.PutObjectInput{
			Bucket:      aws.String(s.bucket()),
			Key:         aws.String(key),
			Body:        bytes.NewReader(body),
			ContentType: aws.String(ct),
		})
		if err != nil {
			return written, fmt.Errorf("put %s: %w", key, err)
		}
		written++
	}
	return written, nil
}

// EnsureProvisioned kicks workspace provisioning if status is empty.
func (s *Service) EnsureProvisioned(ctx context.Context, projectName string, id *int64) {
	st := s.Status(projectName, id)
	status, _ := st["workspaceStatus"].(string)
	if status == "" || status == "failed" {
		s.ProvisionAsync(ctx, projectName, id)
	}
}

func parseProjectID(folder string) any {
	if !project.IsBlinkWorkspaceFolder(folder) {
		return nil
	}
	base := strings.TrimSuffix(folder, "_workspace")
	i := strings.LastIndex(base, "_")
	if i < 0 || i == len(base)-1 {
		return nil
	}
	var id int64
	if _, err := fmt.Sscanf(base[i+1:], "%d", &id); err != nil || id <= 0 {
		return nil
	}
	return id
}

func writeJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(v)
}

func writeErr(w http.ResponseWriter, status int, msg string) {
	writeJSON(w, status, map[string]string{"message": msg})
}
