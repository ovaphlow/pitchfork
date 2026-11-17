// Package intents is Agora's user registry: a row exists once a subject has used
// Agora. user_type records the job-market identity last chosen, but it is a
// preference, not an authorization source: recruiter capabilities still come
// from organisation membership roles.
package intents

import (
	"context"
	"fmt"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
)

// UserType is the job-market identity picked on the Agora login screen.
type UserType string

const (
	JobSeeker UserType = "job_seeker"
	Recruiter UserType = "recruiter"
)

// Types lists every accepted intent.
var Types = []UserType{JobSeeker, Recruiter}

// Parse validates and normalises a requested intent.
func Parse(value string) (UserType, error) {
	next := UserType(value)
	for _, candidate := range Types {
		if candidate == next {
			return next, nil
		}
	}
	return "", apierr.Invalid("用户类型无效")
}

// Store persists intents in PostgreSQL.
type Store struct {
	pool *pgxpool.Pool
}

// NewStore binds the store to a connection pool.
func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// Ensure registers the subject as an Agora user without touching a chosen type.
// It is called whenever a valid Agora session is resolved, so Aceso-only
// accounts never enter the Agora administration list.
func (s *Store) Ensure(ctx context.Context, subjectID string) error {
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO subject_intents (subject_id)
		VALUES ($1)
		ON CONFLICT (subject_id) DO NOTHING`,
		subjectID,
	); err != nil {
		return fmt.Errorf("register subject intent: %w", err)
	}
	return nil
}

// List returns one page of Agora user IDs, newest first, plus the total count.
func (s *Store) List(ctx context.Context, limit, offset int32) ([]string, int64, error) {
	var total int64
	if err := s.pool.QueryRow(ctx, `SELECT COUNT(*) FROM subject_intents`).Scan(&total); err != nil {
		return nil, 0, fmt.Errorf("count subject intents: %w", err)
	}
	rows, err := s.pool.Query(ctx, `
		SELECT subject_id FROM subject_intents
		ORDER BY created_at DESC, subject_id
		LIMIT $1 OFFSET $2`, limit, offset)
	if err != nil {
		return nil, 0, fmt.Errorf("list subject intents: %w", err)
	}
	defer rows.Close()

	ids := make([]string, 0, limit)
	for rows.Next() {
		var subjectID string
		if err := rows.Scan(&subjectID); err != nil {
			return nil, 0, fmt.Errorf("scan subject intent: %w", err)
		}
		ids = append(ids, subjectID)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("iterate subject intents: %w", err)
	}
	return ids, total, nil
}

// Set upserts the subject's last chosen intent.
func (s *Store) Set(ctx context.Context, subjectID string, userType UserType) error {
	if _, err := s.pool.Exec(ctx, `
		INSERT INTO subject_intents (subject_id, user_type)
		VALUES ($1, $2)
		ON CONFLICT (subject_id) DO UPDATE
		SET user_type = EXCLUDED.user_type, updated_at = NOW()`,
		subjectID, string(userType),
	); err != nil {
		return fmt.Errorf("save subject intent: %w", err)
	}
	return nil
}

// BySubjects returns the known intents for the given subject IDs.
func (s *Store) BySubjects(ctx context.Context, subjectIDs []string) (map[string]UserType, error) {
	result := make(map[string]UserType, len(subjectIDs))
	if len(subjectIDs) == 0 {
		return result, nil
	}
	rows, err := s.pool.Query(ctx, `
		SELECT subject_id, user_type FROM subject_intents WHERE subject_id = ANY($1)`,
		subjectIDs,
	)
	if err != nil {
		return nil, fmt.Errorf("list subject intents: %w", err)
	}
	defer rows.Close()

	for rows.Next() {
		var subjectID string
		var userType *string
		if err := rows.Scan(&subjectID, &userType); err != nil {
			return nil, fmt.Errorf("scan subject intent: %w", err)
		}
		if userType == nil {
			continue
		}
		result[subjectID] = UserType(*userType)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("iterate subject intents: %w", err)
	}
	return result, nil
}
