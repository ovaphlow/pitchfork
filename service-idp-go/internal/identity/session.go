package identity

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database/sqlc"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/password"
)

var ErrInvalidCredentials = errors.New("invalid credentials")
var ErrInvalidSession = errors.New("invalid session")

type SessionSettings struct {
	TTL     time.Duration
	IdleTTL time.Duration
}

type LoginResult struct {
	SessionToken string
	CSRFToken    string
	ExpiresAt    time.Time
	Access       string
}

type LoginInput struct {
	Identifier    string
	Password      string
	SourceAddress string
}

type Session struct {
	ID            string
	SubjectID     string
	Access        string
	csrfTokenHash []byte
}

func Login(ctx context.Context, database *sql.DB, input LoginInput, settings SessionSettings, throttleSettings LoginThrottleSettings) (LoginResult, error) {
	if settings.TTL <= 0 || settings.IdleTTL <= 0 || settings.IdleTTL > settings.TTL {
		return LoginResult{}, fmt.Errorf("invalid session settings")
	}
	if err := throttleSettings.validate(); err != nil {
		return LoginResult{}, fmt.Errorf("invalid login throttle settings: %w", err)
	}

	queries := sqlc.New(database)
	now := time.Now().UTC()
	throttleKey := newLoginThrottleKey(throttleSettings, input.Identifier, input.SourceAddress)
	locked, err := loginThrottleLocked(ctx, queries, throttleKey, now)
	if err != nil {
		return LoginResult{}, err
	}
	if locked {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}

	normalizedIdentifier, err := normalizeAccountIdentifier(input.Identifier)
	if err != nil {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}

	credential, err := queries.GetLoginCredentialByNormalizedIdentifier(ctx, sqlc.GetLoginCredentialByNormalizedIdentifierParams{
		NormalizedValue:   normalizedIdentifier,
		Status:            "启用",
		IdentifierUsage:   "主登录",
		IdentifierUsage_2: "辅助登录",
	})
	if errors.Is(err, sql.ErrNoRows) {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}
	if err != nil {
		return LoginResult{}, fmt.Errorf("load login credential: %w", err)
	}
	if credential.CredentialStatus == "已作废" {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}
	securityVersion, err := queries.GetEnabledSubjectSecurityVersion(ctx, sqlc.GetEnabledSubjectSecurityVersionParams{
		ID:     credential.SubjectID,
		Status: "启用",
	})
	if errors.Is(err, sql.ErrNoRows) {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}
	if err != nil {
		return LoginResult{}, fmt.Errorf("load login subject: %w", err)
	}
	matched, err := password.Verify(input.Password, credential.PasswordHash)
	if err != nil || !matched {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}

	now = time.Now().UTC()
	sessionID, err := NewULID(now)
	if err != nil {
		return LoginResult{}, err
	}
	sessionToken, sessionTokenHash, err := newSecret()
	if err != nil {
		return LoginResult{}, err
	}
	csrfToken, csrfTokenHash, err := newSecret()
	if err != nil {
		return LoginResult{}, err
	}
	expiresAt := now.Add(settings.TTL)
	idleExpiresAt := now.Add(settings.IdleTTL)
	if idleExpiresAt.After(expiresAt) {
		idleExpiresAt = expiresAt
	}
	sessionAccess := "完整"
	if credential.CredentialStatus == "需更新" {
		sessionAccess = "仅改密"
	}

	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return LoginResult{}, fmt.Errorf("begin login transaction: %w", err)
	}
	defer transaction.Rollback()
	transactionQueries := queries.WithTx(transaction)
	locked, err = loginThrottleLocked(ctx, transactionQueries, throttleKey, now)
	if err != nil {
		return LoginResult{}, err
	}
	if locked {
		if err := recordLoginFailureInTransaction(ctx, transactionQueries, throttleSettings, throttleKey, now); err != nil {
			return LoginResult{}, err
		}
		if err := transaction.Commit(); err != nil {
			return LoginResult{}, fmt.Errorf("commit throttled login transaction: %w", err)
		}
		return LoginResult{}, ErrInvalidCredentials
	}
	if err := transactionQueries.DeleteLoginThrottle(ctx, sqlc.DeleteLoginThrottleParams{
		IdentifierHash: throttleKey.identifierHash,
		SourceHash:     throttleKey.sourceHash,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("clear login throttle: %w", err)
	}
	if err := transactionQueries.CreateSession(ctx, sqlc.CreateSessionParams{
		ID:                     sessionID,
		SubjectID:              credential.SubjectID,
		SubjectSecurityVersion: securityVersion,
		TokenHash:              sessionTokenHash,
		CsrfTokenHash:          csrfTokenHash,
		SessionAccess:          sessionAccess,
		AuthenticatedAt:        now,
		LastSeenAt:             now,
		ExpiresAt:              expiresAt,
		IdleExpiresAt:          idleExpiresAt,
		RevokedAt:              sql.NullTime{},
		RevokedReason:          sql.NullString{},
		Metadata:               "{}",
		CreatedAt:              now,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("create browser session: %w", err)
	}
	auditID, err := NewULID(now)
	if err != nil {
		return LoginResult{}, err
	}
	if err := transactionQueries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditID,
		EventAction:     "登录",
		Outcome:         "成功",
		ActorSubjectID:  sql.NullString{String: credential.SubjectID, Valid: true},
		TargetSubjectID: sql.NullString{String: credential.SubjectID, Valid: true},
		RequestID:       sql.NullString{},
		SourceHash:      throttleKey.sourceHash,
		Metadata:        "{}",
		CreatedAt:       now,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("write login audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return LoginResult{}, fmt.Errorf("commit login transaction: %w", err)
	}

	return LoginResult{SessionToken: sessionToken, CSRFToken: csrfToken, ExpiresAt: expiresAt, Access: sessionAccess}, nil
}

func rejectLogin(ctx context.Context, database *sql.DB, settings LoginThrottleSettings, key loginThrottleKey, now time.Time) error {
	if err := recordLoginFailure(ctx, database, settings, key, now); err != nil {
		return fmt.Errorf("record failed login: %w", err)
	}
	return ErrInvalidCredentials
}

func CurrentSession(ctx context.Context, database *sql.DB, rawToken string, settings SessionSettings) (Session, error) {
	tokenHash, err := hashSecret(rawToken)
	if err != nil {
		return Session{}, ErrInvalidSession
	}
	queries := sqlc.New(database)
	now := time.Now().UTC()
	sessionRecord, err := queries.GetActiveSessionByTokenHash(ctx, sqlc.GetActiveSessionByTokenHashParams{
		TokenHash:     tokenHash,
		ExpiresAt:     now,
		IdleExpiresAt: now,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrInvalidSession
	}
	if err != nil {
		return Session{}, fmt.Errorf("load browser session: %w", err)
	}
	subjectSecurityVersion, err := queries.GetEnabledSubjectSecurityVersion(ctx, sqlc.GetEnabledSubjectSecurityVersionParams{
		ID:     sessionRecord.SubjectID,
		Status: "启用",
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrInvalidSession
	}
	if err != nil {
		return Session{}, fmt.Errorf("load browser session subject: %w", err)
	}
	if sessionRecord.SubjectSecurityVersion != subjectSecurityVersion {
		return Session{}, ErrInvalidSession
	}
	session := Session{
		ID:            sessionRecord.ID,
		SubjectID:     sessionRecord.SubjectID,
		Access:        sessionRecord.SessionAccess,
		csrfTokenHash: sessionRecord.CsrfTokenHash,
	}
	expiresAt := sessionRecord.ExpiresAt
	idleExpiresAt := now.Add(settings.IdleTTL)
	if idleExpiresAt.After(expiresAt) {
		idleExpiresAt = expiresAt
	}
	updated, err := queries.TouchActiveSession(ctx, sqlc.TouchActiveSessionParams{
		LastSeenAt:    now,
		IdleExpiresAt: idleExpiresAt,
		ID:            session.ID,
	})
	if err != nil {
		return Session{}, fmt.Errorf("refresh browser session: %w", err)
	}
	if updated != 1 {
		return Session{}, ErrInvalidSession
	}
	return session, nil
}

func VerifyCSRF(session Session, rawToken string) bool {
	tokenHash, err := hashSecret(rawToken)
	if err != nil {
		return false
	}
	return subtle.ConstantTimeCompare(tokenHash, session.csrfTokenHash) == 1
}

func HasRole(ctx context.Context, database *sql.DB, subjectID string, roleCode string) (bool, error) {
	assignmentCount, err := sqlc.New(database).CountSubjectRoleAssignments(ctx, sqlc.CountSubjectRoleAssignmentsParams{
		SubjectID: subjectID,
		RoleCode:  roleCode,
	})
	if err != nil {
		return false, fmt.Errorf("check control-plane role: %w", err)
	}
	return assignmentCount > 0, nil
}

func Logout(ctx context.Context, database *sql.DB, rawToken string) error {
	tokenHash, err := hashSecret(rawToken)
	if err != nil {
		return ErrInvalidSession
	}
	queries := sqlc.New(database)
	now := time.Now().UTC()

	transaction, err := database.BeginTx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin logout transaction: %w", err)
	}
	defer transaction.Rollback()
	transactionQueries := queries.WithTx(transaction)

	subjectID, err := transactionQueries.GetActiveSessionSubjectByTokenHash(ctx, tokenHash)
	if errors.Is(err, sql.ErrNoRows) {
		return ErrInvalidSession
	}
	if err != nil {
		return fmt.Errorf("load session for logout: %w", err)
	}
	revoked, err := transactionQueries.RevokeActiveSessionByTokenHash(ctx, sqlc.RevokeActiveSessionByTokenHashParams{
		RevokedAt:     sql.NullTime{Time: now, Valid: true},
		RevokedReason: sql.NullString{String: "用户退出", Valid: true},
		TokenHash:     tokenHash,
	})
	if err != nil {
		return fmt.Errorf("revoke browser session: %w", err)
	}
	if revoked != 1 {
		return ErrInvalidSession
	}
	auditID, err := NewULID(now)
	if err != nil {
		return err
	}
	if err := transactionQueries.InsertAuditEvent(ctx, sqlc.InsertAuditEventParams{
		ID:              auditID,
		EventAction:     "退出登录",
		Outcome:         "成功",
		ActorSubjectID:  sql.NullString{String: subjectID, Valid: true},
		TargetSubjectID: sql.NullString{String: subjectID, Valid: true},
		RequestID:       sql.NullString{},
		SourceHash:      nil,
		Metadata:        "{}",
		CreatedAt:       now,
	}); err != nil {
		return fmt.Errorf("write logout audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit logout transaction: %w", err)
	}
	return nil
}

func newSecret() (string, []byte, error) {
	value := make([]byte, 32)
	if _, err := rand.Read(value); err != nil {
		return "", nil, fmt.Errorf("read session secret: %w", err)
	}
	return base64.RawURLEncoding.EncodeToString(value), hashBytes(value), nil
}

func hashSecret(value string) ([]byte, error) {
	decoded, err := base64.RawURLEncoding.DecodeString(value)
	if err != nil || len(decoded) != 32 {
		return nil, fmt.Errorf("invalid session secret")
	}
	return hashBytes(decoded), nil
}

func hashBytes(value []byte) []byte {
	hash := sha256.Sum256(value)
	return hash[:]
}
