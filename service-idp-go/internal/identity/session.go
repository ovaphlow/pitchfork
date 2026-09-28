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

// loginCredentialRow 是登录校验需要的最小凭据视图。
type loginCredentialRow struct {
	SubjectID        string `db:"subject_id"`
	PasswordHash     string `db:"password_hash"`
	CredentialStatus string `db:"credential_status"`
}

// activeSessionRow 是浏览器会话校验需要的视图。
type activeSessionRow struct {
	ID                     string    `db:"id"`
	SubjectID              string    `db:"subject_id"`
	SubjectSecurityVersion int64     `db:"subject_security_version"`
	SessionAccess          string    `db:"session_access"`
	CsrfTokenHash          []byte    `db:"csrf_token_hash"`
	ExpiresAt              time.Time `db:"expires_at"`
}

// 枚举值一律经 enums.go 常量以命名参数传入，不写死在 SQL 里，
// 这样 internal/identity/enums.go 始终是枚举的唯一来源。
const getLoginCredentialByNormalizedIdentifier = `
SELECT i.subject_id, c.password_hash, c.credential_status
FROM identity_identifiers i
JOIN identity_password_credentials c ON c.subject_id = i.subject_id
WHERE i.normalized_value = :normalized_value
  AND i.status = :status
  AND i.identifier_usage IN (:primary_usage, :secondary_usage)
LIMIT 1`

const getEnabledSubjectSecurityVersion = `
SELECT security_version
FROM identity_subjects
WHERE id = :id AND status = :status`

const createSession = `
INSERT INTO identity_sessions(
    id, subject_id, subject_security_version, token_hash, csrf_token_hash,
    session_access, authenticated_at, last_seen_at, expires_at, idle_expires_at,
    metadata, created_at
) VALUES (
    :id, :subject_id, :subject_security_version, :token_hash, :csrf_token_hash,
    :session_access, :authenticated_at, :last_seen_at, :expires_at, :idle_expires_at,
    :metadata, :created_at
)`

const getActiveSessionByTokenHash = `
SELECT id, subject_id, subject_security_version, session_access, csrf_token_hash, expires_at
FROM identity_sessions
WHERE token_hash = :token_hash
  AND revoked_at IS NULL
  AND expires_at > :now
  AND idle_expires_at > :now`

const touchActiveSession = `
UPDATE identity_sessions
SET last_seen_at = :last_seen_at,
    idle_expires_at = :idle_expires_at
WHERE id = :id
  AND revoked_at IS NULL`

const countSubjectRoleAssignments = `
SELECT COUNT(*)
FROM identity_subject_roles AS subject_role
JOIN identity_roles AS role ON role.id = subject_role.role_id
WHERE subject_role.subject_id = :subject_id
  AND role.role_code = :role_code`

const getActiveSessionSubjectByTokenHash = `
SELECT subject_id
FROM identity_sessions
WHERE token_hash = :token_hash
  AND revoked_at IS NULL`

const revokeActiveSessionByTokenHash = `
UPDATE identity_sessions
SET revoked_at = :revoked_at,
    revoked_reason = :revoked_reason
WHERE token_hash = :token_hash
  AND revoked_at IS NULL`

const deleteLoginThrottle = `
DELETE FROM identity_login_throttles
WHERE identifier_hash = :identifier_hash AND source_hash = :source_hash`

func Login(ctx context.Context, database *sql.DB, input LoginInput, settings SessionSettings, throttleSettings LoginThrottleSettings) (LoginResult, error) {
	if settings.TTL <= 0 || settings.IdleTTL <= 0 || settings.IdleTTL > settings.TTL {
		return LoginResult{}, fmt.Errorf("invalid session settings")
	}
	if err := throttleSettings.validate(); err != nil {
		return LoginResult{}, fmt.Errorf("invalid login throttle settings: %w", err)
	}

	queries := newQuerier(database)
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

	var credential loginCredentialRow
	err = namedGet(ctx, queries, &credential, getLoginCredentialByNormalizedIdentifier, map[string]any{
		"normalized_value": normalizedIdentifier,
		"status":           StatusEnabled,
		"primary_usage":    IdentifierUsagePrimaryLogin,
		"secondary_usage":  IdentifierUsageSecondaryLogin,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}
	if err != nil {
		return LoginResult{}, fmt.Errorf("load login credential: %w", err)
	}
	if credential.CredentialStatus == CredentialStatusVoided {
		return LoginResult{}, rejectLogin(ctx, database, throttleSettings, throttleKey, now)
	}

	var securityVersion int64
	err = namedGet(ctx, queries, &securityVersion, getEnabledSubjectSecurityVersion, map[string]any{
		"id":     credential.SubjectID,
		"status": StatusEnabled,
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
	sessionAccess := SessionAccessFull
	if credential.CredentialStatus == CredentialStatusMustUpdate {
		sessionAccess = SessionAccessPasswordOnly
	}

	transaction, err := queries.BeginTxx(ctx, nil)
	if err != nil {
		return LoginResult{}, fmt.Errorf("begin login transaction: %w", err)
	}
	defer transaction.Rollback()

	locked, err = loginThrottleLocked(ctx, transaction, throttleKey, now)
	if err != nil {
		return LoginResult{}, err
	}
	if locked {
		if err := recordLoginFailureInTransaction(ctx, transaction, throttleSettings, throttleKey, now); err != nil {
			return LoginResult{}, err
		}
		if err := transaction.Commit(); err != nil {
			return LoginResult{}, fmt.Errorf("commit throttled login transaction: %w", err)
		}
		return LoginResult{}, ErrInvalidCredentials
	}
	if err := namedExec(ctx, transaction, deleteLoginThrottle, map[string]any{
		"identifier_hash": throttleKey.identifierHash,
		"source_hash":     throttleKey.sourceHash,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("clear login throttle: %w", err)
	}
	if err := namedExec(ctx, transaction, createSession, map[string]any{
		"id":                       sessionID,
		"subject_id":               credential.SubjectID,
		"subject_security_version": securityVersion,
		"token_hash":               sessionTokenHash,
		"csrf_token_hash":          csrfTokenHash,
		"session_access":           sessionAccess,
		"authenticated_at":         now,
		"last_seen_at":             now,
		"expires_at":               expiresAt,
		"idle_expires_at":          idleExpiresAt,
		"metadata":                 "{}",
		"created_at":               now,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("create browser session: %w", err)
	}
	if err := insertAuditEvent(ctx, transaction, auditEvent{
		Action:          AuditActionLogin,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  credential.SubjectID,
		TargetSubjectID: credential.SubjectID,
		SourceHash:      throttleKey.sourceHash,
	}, now); err != nil {
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
	queries := newQuerier(database)
	now := time.Now().UTC()

	var sessionRecord activeSessionRow
	err = namedGet(ctx, queries, &sessionRecord, getActiveSessionByTokenHash, map[string]any{
		"token_hash": tokenHash,
		"now":        now,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrInvalidSession
	}
	if err != nil {
		return Session{}, fmt.Errorf("load browser session: %w", err)
	}

	var subjectSecurityVersion int64
	err = namedGet(ctx, queries, &subjectSecurityVersion, getEnabledSubjectSecurityVersion, map[string]any{
		"id":     sessionRecord.SubjectID,
		"status": StatusEnabled,
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
	updated, err := namedExecRows(ctx, queries, touchActiveSession, map[string]any{
		"last_seen_at":    now,
		"idle_expires_at": idleExpiresAt,
		"id":              session.ID,
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
	var assignmentCount int64
	if err := namedGet(ctx, newQuerier(database), &assignmentCount, countSubjectRoleAssignments, map[string]any{
		"subject_id": subjectID,
		"role_code":  roleCode,
	}); err != nil {
		return false, fmt.Errorf("check control-plane role: %w", err)
	}
	return assignmentCount > 0, nil
}

func Logout(ctx context.Context, database *sql.DB, rawToken string) error {
	tokenHash, err := hashSecret(rawToken)
	if err != nil {
		return ErrInvalidSession
	}
	queries := newQuerier(database)
	now := time.Now().UTC()

	transaction, err := queries.BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin logout transaction: %w", err)
	}
	defer transaction.Rollback()

	var subjectID string
	err = namedGet(ctx, transaction, &subjectID, getActiveSessionSubjectByTokenHash, map[string]any{
		"token_hash": tokenHash,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return ErrInvalidSession
	}
	if err != nil {
		return fmt.Errorf("load session for logout: %w", err)
	}

	revoked, err := namedExecRows(ctx, transaction, revokeActiveSessionByTokenHash, map[string]any{
		"revoked_at":     now,
		"revoked_reason": RevokedReasonUserLogout,
		"token_hash":     tokenHash,
	})
	if err != nil {
		return fmt.Errorf("revoke browser session: %w", err)
	}
	if revoked != 1 {
		return ErrInvalidSession
	}
	if err := insertAuditEvent(ctx, transaction, auditEvent{
		Action:          AuditActionLogout,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  subjectID,
		TargetSubjectID: subjectID,
	}, now); err != nil {
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
