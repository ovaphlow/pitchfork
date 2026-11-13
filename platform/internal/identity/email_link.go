package identity

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"strings"
	"time"
)

var ErrInvalidEmailLoginLink = errors.New("invalid email login link")
var ErrInvalidEmailLoginLinkRequest = errors.New("invalid email login link request")
var ErrEmailLoginLinkUnavailable = errors.New("email login link unavailable")
var ErrEmailLoginLinkRedirectURI = errors.New("email login link redirect URI not allowed")
var ErrEmailLoginLinkDelivery = errors.New("email login link delivery failed")

// EmailLoginLinkMailer 是邮件链接的唯一投递出口。身份服务不感知 SMTP 细节，
// 测试与备用投递通道（例如 HTTP 邮件 API）只需实现该接口。
type EmailLoginLinkMailer interface {
	SendEmailLoginLink(ctx context.Context, to string, link string, ttl time.Duration) error
}

type EmailLoginLinkSettings struct {
	TTL          time.Duration
	RedirectURIs []string
	RateWindow   time.Duration
	EmailLimit   int
	SourceLimit  int
}

type RequestEmailLoginLinkInput struct {
	Email         string
	DisplayName   string
	RedirectURI   string
	SourceAddress string
}

// emailLoginLinkSubjectRow 是可登录邮箱指向的主体与标识。
type emailLoginLinkSubjectRow struct {
	IdentifierID string `db:"id"`
	SubjectID    string `db:"subject_id"`
}

// emailLoginLinkConsumeRow 是兑换时需要的最小链接视图。
type emailLoginLinkConsumeRow struct {
	ID              string `db:"id"`
	SubjectID       string `db:"subject_id"`
	IdentifierID    string `db:"identifier_id"`
	SecurityVersion int64  `db:"security_version"`
}

// emailLoginLinkRetention 是申请记录的最短保留期：过期的链接既不能兑换，
// 也不需要继续参与限流计数。
const emailLoginLinkRetention = 24 * time.Hour

const deleteExpiredEmailLoginLinks = `
DELETE FROM identity_email_login_links
WHERE created_at < :cutoff`

const countEmailLoginLinksByEmail = `
SELECT COUNT(*)
FROM identity_email_login_links
WHERE email_hash = :email_hash AND created_at >= :since`

const countEmailLoginLinksBySource = `
SELECT COUNT(*)
FROM identity_email_login_links
WHERE source_hash = :source_hash AND created_at >= :since`

// findEmailLoginIdentifier 按邮箱找到可登录标识本身（不是主体）：
// 邮件链接必须绑定到具体标识，兑换后据此标记邮箱已验证。
const findEmailLoginIdentifier = `
SELECT i.id, i.subject_id
FROM identity_identifiers i
JOIN identity_subjects s ON s.id = i.subject_id
WHERE i.normalized_value = :normalized_value
  AND i.status = :status
  AND i.identifier_usage IN (:primary_usage, :secondary_usage)
  AND s.status = :subject_status
ORDER BY CASE WHEN i.identifier_usage = :primary_usage THEN 0 ELSE 1 END,
         i.created_at ASC
LIMIT 1`

const findLoginIdentifierIDForSubject = `
SELECT id
FROM identity_identifiers
WHERE subject_id = :subject_id AND identifier_usage = :identifier_usage
LIMIT 1`

const consumeEmailLoginLinksForSubject = `
UPDATE identity_email_login_links
SET consumed_at = :now
WHERE subject_id = :subject_id AND consumed_at IS NULL`

const insertEmailLoginLink = `
INSERT INTO identity_email_login_links(
    id, subject_id, identifier_id, token_hash, redirect_uri, email_hash,
    source_hash, expires_at, consumed_at, created_at
) VALUES (
    :id, :subject_id, :identifier_id, :token_hash, :redirect_uri, :email_hash,
    :source_hash, :expires_at, NULL, :created_at
)`

const deleteEmailLoginLink = `
DELETE FROM identity_email_login_links
WHERE id = :id`

const findEmailLoginLinkForConsume = `
SELECT l.id, l.subject_id, l.identifier_id, s.security_version
FROM identity_email_login_links l
JOIN identity_subjects s ON s.id = l.subject_id
JOIN identity_identifiers i ON i.id = l.identifier_id
WHERE l.token_hash = :token_hash
  AND l.consumed_at IS NULL
  AND l.expires_at > :now
  AND s.status = :subject_status
  AND i.status = :identifier_status`

const consumeEmailLoginLink = `
UPDATE identity_email_login_links
SET consumed_at = :now
WHERE id = :id AND consumed_at IS NULL`

const verifyEmailLoginIdentifier = `
UPDATE identity_identifiers
SET verified_at = COALESCE(verified_at, :now), updated_at = :now
WHERE id = :id`

func (settings EmailLoginLinkSettings) validate() error {
	if settings.TTL <= 0 || settings.TTL > time.Hour {
		return fmt.Errorf("email login link TTL must be positive and at most one hour")
	}
	if settings.RateWindow <= 0 {
		return fmt.Errorf("email login link rate window must be positive")
	}
	if settings.EmailLimit <= 0 {
		return fmt.Errorf("email login link email limit must be positive")
	}
	if settings.SourceLimit <= 0 {
		return fmt.Errorf("email login link source limit must be positive")
	}
	for _, redirectURI := range settings.RedirectURIs {
		if _, err := normalizeRedirectURI(redirectURI); err != nil {
			return fmt.Errorf("invalid email login redirect URI: %w", err)
		}
	}
	return nil
}

// normalizeRedirectURI 只接受 http(s)、带主机、无用户信息、无片段的地址。
// 查询串与路径保留：部署时链接可能落在子路径上。
func normalizeRedirectURI(value string) (string, error) {
	parsed, err := url.Parse(strings.TrimSpace(value))
	if err != nil {
		return "", fmt.Errorf("redirect URI is not a URL")
	}
	if parsed.Scheme != "http" && parsed.Scheme != "https" {
		return "", fmt.Errorf("redirect URI scheme must be http or https")
	}
	if parsed.Host == "" || parsed.User != nil || parsed.Fragment != "" {
		return "", fmt.Errorf("redirect URI must have a host and no user info or fragment")
	}
	return parsed.String(), nil
}

// buildEmailLoginLink 把一次性 token 挂到落地页 URL 上，由 Agora UI 读取。
func buildEmailLoginLink(redirectURI string, token string) string {
	parsed, err := url.Parse(redirectURI)
	if err != nil {
		return redirectURI
	}
	query := parsed.Query()
	query.Set("email_token", token)
	parsed.RawQuery = query.Encode()
	return parsed.String()
}

// RequestEmailLoginLink 申请一封一次性登录邮件。
//
// 永远不向调用方暴露「邮箱是否已注册」：未知邮箱、限流、投递失败都返回 nil
// （HTTP 层统一 202）。只有配置缺失、邮箱格式非法或 redirect URI 未登记
// 才是可区分的错误——它们与具体邮箱无关。
func RequestEmailLoginLink(
	ctx context.Context,
	database *sql.DB,
	input RequestEmailLoginLinkInput,
	settings EmailLoginLinkSettings,
	throttleSecret []byte,
	mailer EmailLoginLinkMailer,
) error {
	normalizedEmail, err := normalizeAccountIdentifier(input.Email)
	if err != nil || !strings.Contains(normalizedEmail, "@") {
		return ErrInvalidEmailLoginLinkRequest
	}

	displayName := ""
	if strings.TrimSpace(input.DisplayName) != "" {
		displayName, err = validateDisplayName(input.DisplayName)
		if err != nil {
			return ErrInvalidEmailLoginLinkRequest
		}
	}

	if err := settings.validate(); err != nil {
		return ErrEmailLoginLinkUnavailable
	}
	if len(settings.RedirectURIs) == 0 || mailer == nil {
		return ErrEmailLoginLinkUnavailable
	}

	redirectURI, err := normalizeRedirectURI(input.RedirectURI)
	if err != nil {
		return ErrInvalidEmailLoginLinkRequest
	}
	if !emailLoginRedirectURIAllowed(settings.RedirectURIs, redirectURI) {
		return ErrEmailLoginLinkRedirectURI
	}

	emailHash := hmacSHA256(throttleSecret, normalizedEmail)
	sourceHash := hmacSHA256(throttleSecret, normalizedSourceAddress(input.SourceAddress))
	queries := newQuerier(database)
	now := time.Now().UTC()

	transaction, err := queries.BeginTxx(ctx, nil)
	if err != nil {
		return fmt.Errorf("begin email login link transaction: %w", err)
	}
	defer transaction.Rollback()

	if err := namedExec(ctx, transaction, deleteExpiredEmailLoginLinks, map[string]any{
		"cutoff": now.Add(-emailLoginLinkRetention),
	}); err != nil {
		return fmt.Errorf("delete expired email login links: %w", err)
	}

	windowStart := now.Add(-settings.RateWindow)
	var emailCount int64
	if err := namedGet(ctx, transaction, &emailCount, countEmailLoginLinksByEmail, map[string]any{
		"email_hash": emailHash,
		"since":      windowStart,
	}); err != nil {
		return fmt.Errorf("count email login links by email: %w", err)
	}
	if emailCount >= int64(settings.EmailLimit) {
		if err := transaction.Commit(); err != nil {
			return fmt.Errorf("commit rate limited email login link: %w", err)
		}
		return nil
	}
	var sourceCount int64
	if err := namedGet(ctx, transaction, &sourceCount, countEmailLoginLinksBySource, map[string]any{
		"source_hash": sourceHash,
		"since":       windowStart,
	}); err != nil {
		return fmt.Errorf("count email login links by source: %w", err)
	}
	if sourceCount >= int64(settings.SourceLimit) {
		if err := transaction.Commit(); err != nil {
			return fmt.Errorf("commit rate limited email login link: %w", err)
		}
		return nil
	}

	var subject emailLoginLinkSubjectRow
	err = namedGet(ctx, transaction, &subject, findEmailLoginIdentifier, map[string]any{
		"normalized_value": normalizedEmail,
		"status":           StatusEnabled,
		"primary_usage":    IdentifierUsagePrimaryLogin,
		"secondary_usage":  IdentifierUsageSecondaryLogin,
		"subject_status":   StatusEnabled,
	})
	switch {
	case errors.Is(err, sql.ErrNoRows):
		if displayName == "" {
			if err := transaction.Commit(); err != nil {
				return fmt.Errorf("commit unknown email login request: %w", err)
			}
			return nil
		}
		created, err := createSubjectRecord(ctx, transaction, "", displayName, normalizedEmail, IdentifierTypeEmail, nil, now)
		if errors.Is(err, ErrIdentifierAlreadyExists) {
			// 该邮箱已作为「联系」标识存在：不能据此建号，也不发信。
			if err := transaction.Commit(); err != nil {
				return fmt.Errorf("commit duplicate email login request: %w", err)
			}
			return nil
		}
		if err != nil {
			return err
		}
		identifierID, err := namedGetIdentifierID(ctx, transaction, created.ID)
		if err != nil {
			return err
		}
		subject = emailLoginLinkSubjectRow{IdentifierID: identifierID, SubjectID: created.ID}
	case err != nil:
		return fmt.Errorf("load email login subject: %w", err)
	}

	if err := namedExec(ctx, transaction, consumeEmailLoginLinksForSubject, map[string]any{
		"now":        now,
		"subject_id": subject.SubjectID,
	}); err != nil {
		return fmt.Errorf("invalidate previous email login links: %w", err)
	}

	linkID, err := NewULID(now)
	if err != nil {
		return err
	}
	token, tokenHash, err := newSecret()
	if err != nil {
		return err
	}
	if err := namedExec(ctx, transaction, insertEmailLoginLink, map[string]any{
		"id":            linkID,
		"subject_id":    subject.SubjectID,
		"identifier_id": subject.IdentifierID,
		"token_hash":    tokenHash,
		"redirect_uri":  redirectURI,
		"email_hash":    emailHash,
		"source_hash":   sourceHash,
		"expires_at":    now.Add(settings.TTL),
		"created_at":    now,
	}); err != nil {
		return fmt.Errorf("create email login link: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return fmt.Errorf("commit email login link transaction: %w", err)
	}

	link := buildEmailLoginLink(redirectURI, token)
	if err := mailer.SendEmailLoginLink(ctx, normalizedEmail, link, settings.TTL); err != nil {
		// 投递失败：立刻作废这条链接，避免用户拿到一封「有效但没送达」的记录；
		// 删除失败也无妨，过期清理会兜底。
		_ = namedExec(ctx, newQuerier(database), deleteEmailLoginLink, map[string]any{
			"id": linkID,
		})
		return fmt.Errorf("%w: %v", ErrEmailLoginLinkDelivery, err)
	}
	return nil
}

// ConsumeEmailLoginLink 兑换一次性登录 token。邮件链接已证明邮箱所有权，
// 因此直接签发完整会话；credential_status=需更新 不阻止邮件登录
// （Agora 的无密码主体没有可改的密码，需要限制时禁用主体）。
func ConsumeEmailLoginLink(
	ctx context.Context,
	database *sql.DB,
	token string,
	sessionSettings SessionSettings,
	throttleSecret []byte,
	sourceAddress string,
) (LoginResult, error) {
	tokenHash, err := hashSecret(token)
	if err != nil {
		return LoginResult{}, ErrInvalidEmailLoginLink
	}
	if err := sessionSettings.validate(); err != nil {
		return LoginResult{}, err
	}

	queries := newQuerier(database)
	now := time.Now().UTC()

	transaction, err := queries.BeginTxx(ctx, nil)
	if err != nil {
		return LoginResult{}, fmt.Errorf("begin email login link exchange transaction: %w", err)
	}
	defer transaction.Rollback()

	var link emailLoginLinkConsumeRow
	err = namedGet(ctx, transaction, &link, findEmailLoginLinkForConsume, map[string]any{
		"token_hash":        tokenHash,
		"now":               now,
		"subject_status":    StatusEnabled,
		"identifier_status": StatusEnabled,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return LoginResult{}, ErrInvalidEmailLoginLink
	}
	if err != nil {
		return LoginResult{}, fmt.Errorf("load email login link: %w", err)
	}

	consumed, err := namedExecRows(ctx, transaction, consumeEmailLoginLink, map[string]any{
		"now": now,
		"id":  link.ID,
	})
	if err != nil {
		return LoginResult{}, fmt.Errorf("consume email login link: %w", err)
	}
	if consumed != 1 {
		return LoginResult{}, ErrInvalidEmailLoginLink
	}

	if err := namedExec(ctx, transaction, verifyEmailLoginIdentifier, map[string]any{
		"now": now,
		"id":  link.IdentifierID,
	}); err != nil {
		return LoginResult{}, fmt.Errorf("verify email identifier: %w", err)
	}

	login, err := createSessionForSubject(ctx, transaction, link.SubjectID, link.SecurityVersion, SessionAccessFull, sessionSettings, now)
	if err != nil {
		return LoginResult{}, err
	}
	if err := insertAuditEvent(ctx, transaction, auditEvent{
		Action:          AuditActionLogin,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  link.SubjectID,
		TargetSubjectID: link.SubjectID,
		SourceHash:      hmacSHA256(throttleSecret, normalizedSourceAddress(sourceAddress)),
		Metadata:        `{"method":"email_link"}`,
	}, now); err != nil {
		return LoginResult{}, fmt.Errorf("write email login audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return LoginResult{}, fmt.Errorf("commit email login link exchange transaction: %w", err)
	}
	return login, nil
}

func emailLoginRedirectURIAllowed(allowed []string, candidate string) bool {
	for _, redirectURI := range allowed {
		if redirectURI == candidate {
			return true
		}
	}
	return false
}

// namedGetIdentifierID 取主体的主登录标识 ID（建号后立刻使用）。
func namedGetIdentifierID(ctx context.Context, queries querier, subjectID string) (string, error) {
	var identifierID string
	if err := namedGet(ctx, queries, &identifierID, findLoginIdentifierIDForSubject, map[string]any{
		"subject_id":       subjectID,
		"identifier_usage": IdentifierUsagePrimaryLogin,
	}); err != nil {
		return "", fmt.Errorf("load primary login identifier: %w", err)
	}
	return identifierID, nil
}
