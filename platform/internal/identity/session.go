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

	"github.com/ovaphlow/pitchfork/platform/internal/password"
)

var ErrInvalidCredentials = errors.New("invalid credentials")
var ErrInvalidSession = errors.New("invalid session")

type SessionSettings struct {
	TTL     time.Duration
	IdleTTL time.Duration
}

func (settings SessionSettings) validate() error {
	if settings.TTL <= 0 || settings.IdleTTL <= 0 || settings.IdleTTL > settings.TTL {
		return fmt.Errorf("invalid session settings")
	}
	return nil
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
	ID        string
	SubjectID string
	Access    string
	// PlatformAdmin 是代操作（把操作人指定为他人）的唯一门禁（计划 042）。
	// 与 identity_subjects.is_platform_admin 同源，由 CurrentSession 一并取出。
	PlatformAdmin bool
	// DisplayName 供下游渲染"当前登录用户"；档案缺失时为空串（不影响会话有效性）。
	DisplayName string
	// Identifier 是主体的主登录标识（identity_identifiers.identifier_usage = 主登录），
	// 供下游展示/回填"当前账号"；缺失时为空串（不影响会话有效性）。
	Identifier    string
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

// sessionSubjectFacts 是浏览器会话校验需要的主体事实：security_version 用于会话有效性判定，
// is_platform_admin 与 display_name 供下游（Aceso / Nexus）做代操作门禁与"我是谁"展示。
type sessionSubjectFacts struct {
	SecurityVersion int64  `db:"security_version"`
	PlatformAdmin   bool   `db:"is_platform_admin"`
	DisplayName     string `db:"display_name"`
	Identifier      string `db:"identifier_value"`
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

// getEnabledSubjectSessionFacts 与 getEnabledSubjectSecurityVersion 同口径（启用主体），
// 额外带出平台管理员标记、显示名与主登录标识。
//
// display_name / identifier_value 用 LEFT JOIN + COALESCE：档案或主登录标识缺失时
// **保持会话有效**（与改动前一致，不因新增字段把已有会话变成失效），只把字段置为空串。
// 主登录标识按部分唯一索引（identity_identifiers_primary_subject_idx）至多一条。
// 登录路径继续用 getEnabledSubjectSecurityVersion，不因本变更扩大查询面。
const getEnabledSubjectSessionFacts = `
SELECT s.security_version,
       s.is_platform_admin,
       COALESCE(p.display_name, '') AS display_name,
       COALESCE(i.identifier_value, '') AS identifier_value
FROM identity_subjects s
LEFT JOIN identity_profiles p ON p.subject_id = s.id
LEFT JOIN identity_identifiers i ON i.subject_id = s.id
                                AND i.identifier_usage = :primary_usage
WHERE s.id = :id AND s.status = :status`

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

const countEnabledPlatformAdministrator = `
SELECT COUNT(*)
FROM identity_subjects
WHERE id = :subject_id
  AND status = :status
  AND is_platform_admin = 1`

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
	if err := settings.validate(); err != nil {
		return LoginResult{}, err
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
	login, err := createSessionForSubject(ctx, transaction, credential.SubjectID, securityVersion, sessionAccess, settings, now)
	if err != nil {
		return LoginResult{}, err
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

	return login, nil
}

// createSessionForSubject 签发一个浏览器会话，是登录类流程（密码、邮件链接）
// 唯一的会话创建入口。调用方负责事务与审计事件；本函数不提交事务。
func createSessionForSubject(
	ctx context.Context,
	queries querier,
	subjectID string,
	subjectSecurityVersion int64,
	access string,
	settings SessionSettings,
	now time.Time,
) (LoginResult, error) {
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

	if err := namedExec(ctx, queries, createSession, map[string]any{
		"id":                       sessionID,
		"subject_id":               subjectID,
		"subject_security_version": subjectSecurityVersion,
		"token_hash":               sessionTokenHash,
		"csrf_token_hash":          csrfTokenHash,
		"session_access":           access,
		"authenticated_at":         now,
		"last_seen_at":             now,
		"expires_at":               expiresAt,
		"idle_expires_at":          idleExpiresAt,
		"metadata":                 "{}",
		"created_at":               now,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("create browser session: %w", err)
	}

	return LoginResult{SessionToken: sessionToken, CSRFToken: csrfToken, ExpiresAt: expiresAt, Access: access}, nil
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

	var subject sessionSubjectFacts
	err = namedGet(ctx, queries, &subject, getEnabledSubjectSessionFacts, map[string]any{
		"id":            sessionRecord.SubjectID,
		"status":        StatusEnabled,
		"primary_usage": IdentifierUsagePrimaryLogin,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Session{}, ErrInvalidSession
	}
	if err != nil {
		return Session{}, fmt.Errorf("load browser session subject: %w", err)
	}
	if sessionRecord.SubjectSecurityVersion != subject.SecurityVersion {
		return Session{}, ErrInvalidSession
	}

	session := Session{
		ID:            sessionRecord.ID,
		SubjectID:     sessionRecord.SubjectID,
		Access:        sessionRecord.SessionAccess,
		PlatformAdmin: subject.PlatformAdmin,
		DisplayName:   subject.DisplayName,
		Identifier:    subject.Identifier,
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

// IsPlatformAdmin 报告该主体是否是启用的平台管理员。
// 这是 IDP 管理台唯一的准入依据，刻意不依赖 Nexus，避免循环依赖锁死管理台。
func IsPlatformAdmin(ctx context.Context, database *sql.DB, subjectID string) (bool, error) {
	var administratorCount int64
	if err := namedGet(ctx, newQuerier(database), &administratorCount, countEnabledPlatformAdministrator, map[string]any{
		"subject_id": subjectID,
		"status":     StatusEnabled,
	}); err != nil {
		return false, fmt.Errorf("check platform administrator: %w", err)
	}
	return administratorCount > 0, nil
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
