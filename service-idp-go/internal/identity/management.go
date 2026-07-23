package identity

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database/sqlc"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/password"
)

var ErrSubjectNotFound = errors.New("subject not found")
var ErrIdentifierAlreadyExists = errors.New("identifier is already in use")
var ErrLastAdministrator = errors.New("cannot disable the last enabled administrator")
var ErrInvalidSubjectInput = errors.New("invalid subject input")

type Subject struct {
	ID              string    `json:"id"`
	Status          string    `json:"status"`
	SecurityVersion int64     `json:"security_version"`
	DisplayName     string    `json:"display_name"`
	Identifier      string    `json:"identifier"`
	Roles           []string  `json:"roles"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

type ListSubjectsInput struct {
	Limit  int64
	Offset int64
}

type ListSubjectsResult struct {
	Subjects []Subject
	Total    int64
}

type CreateSubjectInput struct {
	DisplayName string
	Identifier  string
	Password    string
}

func ListSubjects(ctx context.Context, database *sql.DB, input ListSubjectsInput) (ListSubjectsResult, error) {
	if input.Limit <= 0 || input.Offset < 0 {
		return ListSubjectsResult{}, fmt.Errorf("invalid subject list pagination")
	}

	queries := sqlc.New(database)
	total, err := queries.CountSubjectsForManagement(ctx)
	if err != nil {
		return ListSubjectsResult{}, fmt.Errorf("count subjects for management: %w", err)
	}
	rows, err := queries.ListSubjectsForManagement(ctx, sqlc.ListSubjectsForManagementParams{
		IdentifierUsage: "主登录",
		Limit:           input.Limit,
		Offset:          input.Offset,
	})
	if err != nil {
		return ListSubjectsResult{}, fmt.Errorf("list subjects for management: %w", err)
	}

	subjects := make([]Subject, 0, len(rows))
	for _, row := range rows {
		subject, err := subjectFromManagementValues(
			ctx,
			queries,
			row.ID,
			row.Status,
			row.SecurityVersion,
			row.DisplayName,
			row.IdentifierValue,
			row.CreatedAt,
			row.UpdatedAt,
		)
		if err != nil {
			return ListSubjectsResult{}, err
		}
		subjects = append(subjects, subject)
	}
	return ListSubjectsResult{Subjects: subjects, Total: total}, nil
}

func GetSubject(ctx context.Context, database *sql.DB, subjectID string) (Subject, error) {
	return getSubject(ctx, sqlc.New(database), subjectID)
}

func CreateSubject(ctx context.Context, database *sql.DB, actorSubjectID string, input CreateSubjectInput) (Subject, error) {
	displayName, err := validateDisplayName(input.DisplayName)
	if err != nil {
		return Subject{}, fmt.Errorf("%w: %v", ErrInvalidSubjectInput, err)
	}
	identifier, err := normalizeAccountIdentifier(input.Identifier)
	if err != nil {
		return Subject{}, fmt.Errorf("%w: validate account identifier: %v", ErrInvalidSubjectInput, err)
	}
	passwordHash, err := password.Hash(input.Password)
	if err != nil {
		return Subject{}, fmt.Errorf("%w: %v", ErrInvalidSubjectInput, err)
	}

	queries := sqlc.New(database)
	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return Subject{}, fmt.Errorf("begin create subject transaction: %w", err)
	}
	defer transaction.Rollback()
	transactionQueries := queries.WithTx(transaction)

	_, err = transactionQueries.GetIdentifierSubjectID(ctx, sqlc.GetIdentifierSubjectIDParams{
		IdentifierType:  "账号",
		NormalizedValue: identifier,
	})
	if err == nil {
		return Subject{}, ErrIdentifierAlreadyExists
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Subject{}, fmt.Errorf("check account identifier: %w", err)
	}

	now := time.Now().UTC()
	subjectID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}
	identifierID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}
	credentialID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}
	auditEventID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}

	if err := transactionQueries.CreateSubject(ctx, sqlc.CreateSubjectParams{
		ID:              subjectID,
		Status:          "启用",
		SecurityVersion: 1,
		DisabledAt:      sql.NullTime{},
		Metadata:        "{}",
		CreatedAt:       now,
		UpdatedAt:       now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create subject: %w", err)
	}
	if err := transactionQueries.CreateProfile(ctx, sqlc.CreateProfileParams{
		SubjectID:   subjectID,
		DisplayName: displayName,
		CreatedAt:   now,
		UpdatedAt:   now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create subject profile: %w", err)
	}
	if err := transactionQueries.CreateIdentifier(ctx, sqlc.CreateIdentifierParams{
		ID:              identifierID,
		SubjectID:       subjectID,
		IdentifierType:  "账号",
		IdentifierValue: identifier,
		NormalizedValue: identifier,
		IdentifierUsage: "主登录",
		Status:          "启用",
		VerifiedAt:      sql.NullTime{},
		CreatedAt:       now,
		UpdatedAt:       now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create account identifier: %w", err)
	}
	if err := transactionQueries.CreatePasswordCredential(ctx, sqlc.CreatePasswordCredentialParams{
		ID:               credentialID,
		SubjectID:        subjectID,
		PasswordHash:     passwordHash,
		PasswordRevision: 1,
		CredentialStatus: "有效",
		ChangedAt:        now,
		CreatedAt:        now,
		UpdatedAt:        now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create password credential: %w", err)
	}
	if err := transactionQueries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditEventID,
		EventAction:     "主体创建",
		Outcome:         "成功",
		ActorSubjectID:  sql.NullString{String: actorSubjectID, Valid: true},
		TargetSubjectID: sql.NullString{String: subjectID, Valid: true},
		RequestID:       sql.NullString{},
		SourceHash:      nil,
		Metadata:        "{}",
		CreatedAt:       now,
	}); err != nil {
		return Subject{}, fmt.Errorf("write subject creation audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return Subject{}, fmt.Errorf("commit create subject transaction: %w", err)
	}

	return Subject{
		ID:              subjectID,
		Status:          "启用",
		SecurityVersion: 1,
		DisplayName:     displayName,
		Identifier:      identifier,
		Roles:           []string{},
		CreatedAt:       now,
		UpdatedAt:       now,
	}, nil
}

func DisableSubject(ctx context.Context, database *sql.DB, actorSubjectID string, subjectID string) (Subject, error) {
	queries := sqlc.New(database)
	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return Subject{}, fmt.Errorf("begin disable subject transaction: %w", err)
	}
	defer transaction.Rollback()
	transactionQueries := queries.WithTx(transaction)

	subject, err := getSubject(ctx, transactionQueries, subjectID)
	if err != nil {
		return Subject{}, err
	}
	if subject.Status == "禁用" {
		if err := transaction.Commit(); err != nil {
			return Subject{}, fmt.Errorf("commit existing disabled subject: %w", err)
		}
		return subject, nil
	}

	if hasRole(subject.Roles, "identity.admin") {
		remainingAdministrators, err := transactionQueries.CountEnabledSubjectsByRoleCodeExcludingSubjectID(ctx, sqlc.CountEnabledSubjectsByRoleCodeExcludingSubjectIDParams{
			Status:   "启用",
			RoleCode: "identity.admin",
			ID:       subjectID,
		})
		if err != nil {
			return Subject{}, fmt.Errorf("count remaining administrators: %w", err)
		}
		if remainingAdministrators == 0 {
			return Subject{}, ErrLastAdministrator
		}
	}

	now := time.Now().UTC()
	updated, err := transactionQueries.DisableSubject(ctx, sqlc.DisableSubjectParams{
		Status:     "禁用",
		DisabledAt: sql.NullTime{Time: now, Valid: true},
		UpdatedAt:  now,
		ID:         subjectID,
		Status_2:   "启用",
	})
	if err != nil {
		return Subject{}, fmt.Errorf("disable subject: %w", err)
	}
	if updated != 1 {
		return Subject{}, fmt.Errorf("disable subject: expected one enabled subject, updated %d", updated)
	}
	if _, err := transactionQueries.RevokeActiveSessionsBySubjectID(ctx, sqlc.RevokeActiveSessionsBySubjectIDParams{
		RevokedAt:     sql.NullTime{Time: now, Valid: true},
		RevokedReason: sql.NullString{String: "主体禁用", Valid: true},
		SubjectID:     subjectID,
	}); err != nil {
		return Subject{}, fmt.Errorf("revoke subject sessions: %w", err)
	}
	auditEventID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}
	if err := transactionQueries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditEventID,
		EventAction:     "主体状态变更",
		Outcome:         "成功",
		ActorSubjectID:  sql.NullString{String: actorSubjectID, Valid: true},
		TargetSubjectID: sql.NullString{String: subjectID, Valid: true},
		RequestID:       sql.NullString{},
		SourceHash:      nil,
		Metadata:        `{"status":"禁用"}`,
		CreatedAt:       now,
	}); err != nil {
		return Subject{}, fmt.Errorf("write subject disable audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return Subject{}, fmt.Errorf("commit disable subject transaction: %w", err)
	}

	subject.Status = "禁用"
	subject.SecurityVersion++
	subject.UpdatedAt = now
	return subject, nil
}

func getSubject(ctx context.Context, queries sqlc.Querier, subjectID string) (Subject, error) {
	row, err := queries.GetSubjectForManagement(ctx, sqlc.GetSubjectForManagementParams{
		ID:              subjectID,
		IdentifierUsage: "主登录",
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Subject{}, ErrSubjectNotFound
	}
	if err != nil {
		return Subject{}, fmt.Errorf("get subject for management: %w", err)
	}
	return subjectFromManagementValues(
		ctx,
		queries,
		row.ID,
		row.Status,
		row.SecurityVersion,
		row.DisplayName,
		row.IdentifierValue,
		row.CreatedAt,
		row.UpdatedAt,
	)
}

func subjectFromManagementValues(ctx context.Context, queries sqlc.Querier, subjectID string, status string, securityVersion int64, displayName string, identifier string, createdAt time.Time, updatedAt time.Time) (Subject, error) {
	roles, err := queries.ListRoleCodesBySubjectID(ctx, subjectID)
	if err != nil {
		return Subject{}, fmt.Errorf("list subject roles: %w", err)
	}
	if roles == nil {
		roles = []string{}
	}
	return Subject{
		ID:              subjectID,
		Status:          status,
		SecurityVersion: securityVersion,
		DisplayName:     displayName,
		Identifier:      identifier,
		Roles:           roles,
		CreatedAt:       createdAt,
		UpdatedAt:       updatedAt,
	}, nil
}

func hasRole(roles []string, roleCode string) bool {
	for _, role := range roles {
		if role == roleCode {
			return true
		}
	}
	return false
}

func validateDisplayName(value string) (string, error) {
	value = strings.TrimSpace(value)
	if length := utf8.RuneCountInString(value); length < 1 || length > 120 {
		return "", fmt.Errorf("display name must contain 1 to 120 characters")
	}
	return value, nil
}
