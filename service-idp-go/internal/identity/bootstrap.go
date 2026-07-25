package identity

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database/sqlc"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/password"
)

type BootstrapInput struct {
	Identifier string
	Password   string
}

func EnsureBootstrap(ctx context.Context, database *sql.DB, input BootstrapInput) (bool, error) {
	queries := sqlc.New(database)
	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return false, fmt.Errorf("begin bootstrap transaction: %w", err)
	}
	defer transaction.Rollback()
	transactionQueries := queries.WithTx(transaction)

	now := time.Now().UTC()
	if err := seedRoles(ctx, transactionQueries, now); err != nil {
		return false, err
	}

	subjectCount, err := transactionQueries.CountSubjects(ctx)
	if err != nil {
		return false, fmt.Errorf("count identity subjects: %w", err)
	}
	if subjectCount > 0 {
		if err := transaction.Commit(); err != nil {
			return false, fmt.Errorf("commit existing bootstrap state: %w", err)
		}
		return false, nil
	}

	identifier, err := normalizeAccountIdentifier(input.Identifier)
	if err != nil {
		return false, fmt.Errorf("validate bootstrap identifier: %w", err)
	}
	passwordHash, err := password.Hash(input.Password)
	if err != nil {
		return false, fmt.Errorf("hash bootstrap password: %w", err)
	}

	subjectID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	identifierID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	credentialID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	grantID, err := NewULID(now)
	if err != nil {
		return false, err
	}
	auditEventID, err := NewULID(now)
	if err != nil {
		return false, err
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
		return false, fmt.Errorf("create bootstrap subject: %w", err)
	}
	if err := transactionQueries.CreateProfile(ctx, sqlc.CreateProfileParams{
		SubjectID:   subjectID,
		DisplayName: "系统管理员",
		CreatedAt:   now,
		UpdatedAt:   now,
	}); err != nil {
		return false, fmt.Errorf("create bootstrap profile: %w", err)
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
		return false, fmt.Errorf("create bootstrap identifier: %w", err)
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
		return false, fmt.Errorf("create bootstrap credential: %w", err)
	}

	adminRoleID, err := transactionQueries.GetRoleIDByCode(ctx, "identity.admin")
	if err != nil {
		return false, fmt.Errorf("find identity admin role: %w", err)
	}
	if err := transactionQueries.AssignSubjectRole(ctx, sqlc.AssignSubjectRoleParams{
		ID:                 grantID,
		SubjectID:          subjectID,
		RoleID:             adminRoleID,
		GrantedBySubjectID: sql.NullString{},
		CreatedAt:          now,
	}); err != nil {
		return false, fmt.Errorf("grant bootstrap administrator role: %w", err)
	}
	if err := transactionQueries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditEventID,
		EventAction:     "主体创建",
		Outcome:         "成功",
		ActorSubjectID:  sql.NullString{},
		TargetSubjectID: sql.NullString{String: subjectID, Valid: true},
		RequestID:       sql.NullString{},
		SourceHash:      nil,
		Metadata:        `{"actor_source":"bootstrap"}`,
		CreatedAt:       now,
	}); err != nil {
		return false, fmt.Errorf("write bootstrap audit event: %w", err)
	}

	if err := transaction.Commit(); err != nil {
		return false, fmt.Errorf("commit bootstrap transaction: %w", err)
	}
	return true, nil
}

func seedRoles(ctx context.Context, queries sqlc.Querier, now time.Time) error {
	roles := []struct {
		Code        string
		DisplayName string
		Description string
	}{
		{Code: "identity.admin", DisplayName: "身份管理员", Description: "管理身份、凭据、角色、会话和恢复操作。"},
		{Code: "identity.audit.read", DisplayName: "审计查看者", Description: "查看运行概览和不可变审计事件。"},
	}
	for _, role := range roles {
		roleID, err := NewULID(now)
		if err != nil {
			return err
		}
		if err := queries.CreateRoleIfAbsent(ctx, sqlc.CreateRoleIfAbsentParams{
			ID:          roleID,
			RoleCode:    role.Code,
			DisplayName: role.DisplayName,
			Description: role.Description,
			CreatedAt:   now,
			UpdatedAt:   now,
		}); err != nil {
			return fmt.Errorf("seed role %s: %w", role.Code, err)
		}
	}
	return nil
}

func normalizeAccountIdentifier(value string) (string, error) {
	value = strings.TrimSpace(value)
	if len(value) < 3 || len(value) > 64 {
		return "", fmt.Errorf("account identifier must contain 3 to 64 characters")
	}

	normalized := strings.ToLower(value)
	if strings.Contains(normalized, "@") {
		return normalizeEmailIdentifier(normalized)
	}

	for _, character := range normalized {
		if (character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '_' || character == '-' || character == '.' {
			continue
		}
		return "", fmt.Errorf("account identifier contains an unsupported character")
	}
	return normalized, nil
}

func normalizeEmailIdentifier(value string) (string, error) {
	if strings.Count(value, "@") != 1 {
		return "", fmt.Errorf("email account identifier must contain one @")
	}

	localPart, domain, _ := strings.Cut(value, "@")
	if !validEmailLocalPart(localPart) || !validEmailDomain(domain) {
		return "", fmt.Errorf("email account identifier is invalid")
	}
	return value, nil
}

func validEmailLocalPart(value string) bool {
	if value == "" || strings.HasPrefix(value, ".") || strings.HasSuffix(value, ".") || strings.Contains(value, "..") {
		return false
	}
	for _, character := range value {
		if (character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '_' || character == '-' || character == '.' {
			continue
		}
		return false
	}
	return true
}

func validEmailDomain(value string) bool {
	labels := strings.Split(value, ".")
	if len(labels) < 2 {
		return false
	}
	for _, label := range labels {
		if label == "" || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return false
		}
		for _, character := range label {
			if (character >= 'a' && character <= 'z') ||
				(character >= '0' && character <= '9') || character == '-' {
				continue
			}
			return false
		}
	}
	return true
}
