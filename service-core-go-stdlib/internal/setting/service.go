package setting

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"time"

	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting/entity"
	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting/repo"
)

// Service encapsulates business logic for settings and depends on a repo.
type Service struct {
	repo *repo.Repo
}

// NewService constructs a Service with the provided repository.
func NewService(r *repo.Repo) *Service {
	return &Service{repo: r}
}

// sentinel errors for common failure modes
var (
	ErrNotFound        = errors.New("not found")
	ErrVersionConflict = errors.New("version conflict")
)

// List returns settings by category/parent/root (optional) with pagination.
func (s *Service) List(ctx context.Context, category string, parentID string, rootID string, limit, offset int) ([]*entity.Setting, error) {
	return s.repo.List(ctx, category, parentID, rootID, limit, offset)
}

// Get returns a setting by id.
func (s *Service) Get(ctx context.Context, id string) (*entity.Setting, error) {
	st, err := s.repo.GetByID(ctx, id)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, ErrNotFound
		}
		return nil, err
	}
	return st, nil
}

// Create creates a new setting. It applies sensible defaults when fields are omitted.
func (s *Service) Create(ctx context.Context, in *entity.Setting) (*entity.Setting, error) {
	now := time.Now().UTC()
	if in.ID == "" {
		return nil, errors.New("id is required")
	}
	if in.Key == "" {
		in.Key = in.ID
	}
	if in.ValueType == "" {
		in.ValueType = "json"
	}
	if in.Version == 0 {
		in.Version = 1
	}
	if in.Status == "" {
		in.Status = "active"
	}
	in.CreatedAt = now
	in.UpdatedAt = now
	if in.RecordMeta == nil {
		in.RecordMeta = jsonRawEmpty()
	}
	if in.Value == nil {
		in.Value = jsonRawEmpty()
	}
	if in.Ancestors == nil {
		in.Ancestors = jsonRawEmptyArray()
	}
	if err := s.repo.Create(ctx, in); err != nil {
		return nil, err
	}
	return in, nil
}

// Update updates an existing setting using optimistic locking on version.
func (s *Service) Update(ctx context.Context, in *entity.Setting) (*entity.Setting, error) {
	// Load existing to get current version
	existing, err := s.repo.GetByID(ctx, in.ID)
	if err != nil {
		if err == sql.ErrNoRows {
			return nil, ErrNotFound
		}
		return nil, err
	}
	// bump version
	expected := existing.Version
	in.Version = expected + 1
	in.UpdatedAt = time.Now().UTC()
	rows, err := s.repo.Update(ctx, in, expected)
	if err != nil {
		return nil, err
	}
	if rows == 0 {
		// existing was found, so 0 rows indicates version mismatch
		return nil, ErrVersionConflict
	}
	return in, nil
}

// Delete removes a setting by id.
func (s *Service) Delete(ctx context.Context, id string) error {
	rows, err := s.repo.Delete(ctx, id)
	if err != nil {
		return err
	}
	if rows == 0 {
		return ErrNotFound
	}
	return nil
}

// helper json raw defaults
func jsonRawEmpty() json.RawMessage      { return json.RawMessage("{}") }
func jsonRawEmptyArray() json.RawMessage { return json.RawMessage("[]") }
