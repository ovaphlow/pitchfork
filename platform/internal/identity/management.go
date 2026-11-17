package identity

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
	"time"
	"unicode/utf8"

	"github.com/ovaphlow/pitchfork/platform/internal/password"
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
	PlatformAdmin   bool      `json:"platform_admin"`
	CreatedAt       time.Time `json:"created_at"`
	UpdatedAt       time.Time `json:"updated_at"`
}

// Enabled 报告主体当前是否处于启用状态，供 HTTP 层与模板判断使用，
// 避免在调用点重复比较 status 字面量。
func (s Subject) Enabled() bool {
	return s.Status == StatusEnabled
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

// managementSubjectRow 是管理台列表与详情共用的主体视图。
type managementSubjectRow struct {
	ID              string    `db:"id"`
	Status          string    `db:"status"`
	SecurityVersion int64     `db:"security_version"`
	PlatformAdmin   bool      `db:"is_platform_admin"`
	DisplayName     string    `db:"display_name"`
	IdentifierValue string    `db:"identifier_value"`
	CreatedAt       time.Time `db:"created_at"`
	UpdatedAt       time.Time `db:"updated_at"`
}

// countSubjects 被管理台列表和引导流程共用。
const countSubjects = `
SELECT COUNT(*)
FROM identity_subjects`

const listSubjectsForManagement = `
SELECT s.id, s.status, s.security_version, s.is_platform_admin, p.display_name, i.identifier_value, s.created_at, s.updated_at
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id = s.id
JOIN identity_identifiers i ON i.subject_id = s.id
WHERE i.identifier_usage = :identifier_usage
ORDER BY s.created_at DESC
LIMIT :page_limit OFFSET :page_offset`

const getSubjectForManagement = `
SELECT s.id, s.status, s.security_version, s.is_platform_admin, p.display_name, i.identifier_value, s.created_at, s.updated_at
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id = s.id
JOIN identity_identifiers i ON i.subject_id = s.id
WHERE s.id = :id AND i.identifier_usage = :identifier_usage`

// getIdentifierSubjectID 只按 normalized_value 查重（不带 identifier_type）：
// 同一邮箱不允许以不同类型（账号 / 邮箱）重复建号，否则登录入口会出现歧义。
const getIdentifierSubjectID = `
SELECT subject_id
FROM identity_identifiers
WHERE normalized_value = :normalized_value`

const createSubject = `
INSERT INTO identity_subjects(
    id, status, security_version, metadata, created_at, updated_at
) VALUES (
    :id, :status, :security_version, :metadata, :created_at, :updated_at
)`

const createProfile = `
INSERT INTO identity_profiles(subject_id, display_name, created_at, updated_at)
VALUES (
    :subject_id, :display_name, :created_at, :updated_at
)`

const createIdentifier = `
INSERT INTO identity_identifiers(
    id, subject_id, identifier_type, identifier_value, normalized_value,
    identifier_usage, status, created_at, updated_at
) VALUES (
    :id, :subject_id, :identifier_type, :identifier_value, :normalized_value,
    :identifier_usage, :status, :created_at, :updated_at
)`

const createPasswordCredential = `
INSERT INTO identity_password_credentials(
    id, subject_id, password_hash, password_revision, credential_status,
    changed_at, created_at, updated_at
) VALUES (
    :id, :subject_id, :password_hash, :password_revision, :credential_status,
    :changed_at, :created_at, :updated_at
)`

const countEnabledPlatformAdminsExcludingSubjectID = `
SELECT COUNT(*)
FROM identity_subjects AS subject
WHERE subject.status = :status
  AND subject.is_platform_admin = 1
  AND subject.id <> :subject_id`

const disableSubject = `
UPDATE identity_subjects
SET status = :disabled_status,
    security_version = security_version + 1,
    disabled_at = :disabled_at,
    updated_at = :updated_at
WHERE id = :id AND status = :enabled_status`

const enableSubject = `
UPDATE identity_subjects
SET status = :enabled_status,
    security_version = security_version + 1,
    disabled_at = NULL,
    updated_at = :updated_at
WHERE id = :id AND status = :disabled_status`

const setSubjectPlatformAdmin = `
UPDATE identity_subjects
SET is_platform_admin = :is_platform_admin,
    updated_at = :updated_at
WHERE id = :id`

func ListSubjects(ctx context.Context, database *sql.DB, input ListSubjectsInput) (ListSubjectsResult, error) {
	if input.Limit <= 0 || input.Offset < 0 {
		return ListSubjectsResult{}, fmt.Errorf("invalid subject list pagination")
	}

	queries := newQuerier(database)
	var total int64
	if err := namedGet(ctx, queries, &total, countSubjects, nil); err != nil {
		return ListSubjectsResult{}, fmt.Errorf("count subjects for management: %w", err)
	}
	var rows []managementSubjectRow
	if err := namedSelect(ctx, queries, &rows, listSubjectsForManagement, map[string]any{
		"identifier_usage": IdentifierUsagePrimaryLogin,
		"page_limit":       input.Limit,
		"page_offset":      input.Offset,
	}); err != nil {
		return ListSubjectsResult{}, fmt.Errorf("list subjects for management: %w", err)
	}

	subjects := make([]Subject, 0, len(rows))
	for _, row := range rows {
		subjects = append(subjects, subjectFromManagementValues(
			row.ID,
			row.Status,
			row.SecurityVersion,
			row.PlatformAdmin,
			row.DisplayName,
			row.IdentifierValue,
			row.CreatedAt,
			row.UpdatedAt,
		))
	}
	return ListSubjectsResult{Subjects: subjects, Total: total}, nil
}

// ListSubjectsByIDs returns the management view for the requested subjects.
// Product backends use it to render their own user registry without reading
// every platform account.
func ListSubjectsByIDs(ctx context.Context, database *sql.DB, subjectIDs []string) ([]Subject, error) {
	if len(subjectIDs) == 0 {
		return []Subject{}, nil
	}
	if len(subjectIDs) > 100 {
		return nil, fmt.Errorf("at most 100 subject ids are allowed")
	}
	queries := newQuerier(database)
	placeholders := make([]string, len(subjectIDs))
	arguments := make([]any, 0, len(subjectIDs)+1)
	arguments = append(arguments, IdentifierUsagePrimaryLogin)
	for index, subjectID := range subjectIDs {
		placeholders[index] = "?"
		arguments = append(arguments, subjectID)
	}
	query := `
SELECT s.id, s.status, s.security_version, s.is_platform_admin, p.display_name, i.identifier_value, s.created_at, s.updated_at
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id = s.id
JOIN identity_identifiers i ON i.subject_id = s.id
WHERE i.identifier_usage = ? AND s.id IN (` + strings.Join(placeholders, ", ") + `)
ORDER BY s.created_at DESC`

	var rows []managementSubjectRow
	if err := queries.SelectContext(ctx, &rows, queries.Rebind(query), arguments...); err != nil {
		return nil, fmt.Errorf("list subjects by ids: %w", err)
	}
	subjects := make([]Subject, 0, len(rows))
	for _, row := range rows {
		subjects = append(subjects, subjectFromManagementValues(
			row.ID,
			row.Status,
			row.SecurityVersion,
			row.PlatformAdmin,
			row.DisplayName,
			row.IdentifierValue,
			row.CreatedAt,
			row.UpdatedAt,
		))
	}
	return subjects, nil
}

// DirectoryEntry 是**业务人员目录**条目：只暴露「主体 ID + 姓名」。
//
// 刻意不含 identifier（登录账号）、status、security_version、platform_admin、created_at 等
// 管理字段：本端点对**所有登录用户**开放（业务账号要在表单里挑同事、在列表里看责任人姓名），
// 暴露面必须控制在「组织内通讯录」级别（计划 044 D1/D2）。
type DirectoryEntry struct {
	ID          string `json:"id"`
	DisplayName string `json:"display_name"`
}

type ListDirectoryInput struct {
	Limit  int64
	Offset int64
	// Search 按 display_name 模糊匹配（空串表示不过滤）；SQLite 的 LIKE 对 ASCII 不区分大小写
	Search string
}

type ListDirectoryResult struct {
	Entries []DirectoryEntry
	Total   int64
}

// directoryRow 是目录查询的最小视图（字段与响应一一对应）。
type directoryRow struct {
	ID          string `db:"id"`
	DisplayName string `db:"display_name"`
}

// countDirectorySubjects / listDirectorySubjects：只取**启用**主体，避免把已禁用账号
// 当作可选操作人。INNER JOIN identity_profiles 是刻意的——没有档案的主体无法展示姓名，
// 而本端点的唯一用途就是展示姓名。
//
// `ESCAPE '\'` 与 escapeLikePattern 成对：用户输入的 % / _ 不得变成通配符。
const countDirectorySubjects = `
SELECT COUNT(*)
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id = s.id
WHERE s.status = :status
  AND (:search = '' OR p.display_name LIKE :pattern ESCAPE '\')`

const listDirectorySubjects = `
SELECT s.id, p.display_name
FROM identity_subjects s
JOIN identity_profiles p ON p.subject_id = s.id
WHERE s.status = :status
  AND (:search = '' OR p.display_name LIKE :pattern ESCAPE '\')
ORDER BY p.display_name, s.id
LIMIT :page_limit OFFSET :page_offset`

// ListDirectory 是业务侧唯一可读的主体目录（计划 044）。
// 与 ListSubjects（管理台，需平台管理员）的区别只有两点：字段最小化 + 只含启用主体。
func ListDirectory(ctx context.Context, database *sql.DB, input ListDirectoryInput) (ListDirectoryResult, error) {
	if input.Limit <= 0 || input.Offset < 0 {
		return ListDirectoryResult{}, fmt.Errorf("invalid directory pagination")
	}
	search := strings.TrimSpace(input.Search)
	pattern := "%" + escapeLikePattern(search) + "%"

	queries := newQuerier(database)
	var total int64
	// 参数一律内联成 map 字面量：sqlx_test.go 的 TestSQLNamedParametersAreProvided
	// 会静态比对 SQL 常量里的命名参数与调用点实参，变量形式会被判为不可校验。
	if err := namedGet(ctx, queries, &total, countDirectorySubjects, map[string]any{
		"status":  StatusEnabled,
		"search":  search,
		"pattern": pattern,
	}); err != nil {
		return ListDirectoryResult{}, fmt.Errorf("count directory subjects: %w", err)
	}
	var rows []directoryRow
	if err := namedSelect(ctx, queries, &rows, listDirectorySubjects, map[string]any{
		"status":      StatusEnabled,
		"search":      search,
		"pattern":     pattern,
		"page_limit":  input.Limit,
		"page_offset": input.Offset,
	}); err != nil {
		return ListDirectoryResult{}, fmt.Errorf("list directory subjects: %w", err)
	}

	entries := make([]DirectoryEntry, 0, len(rows))
	for _, row := range rows {
		entries = append(entries, DirectoryEntry{ID: row.ID, DisplayName: row.DisplayName})
	}
	return ListDirectoryResult{Entries: entries, Total: total}, nil
}

// escapeLikePattern 把 LIKE 的通配符转义掉，避免用户输入的 % / _ 把检索变成全表匹配。
// 反斜杠同时需要转义；SQL 里配合 ESCAPE '\\' 使用。
func escapeLikePattern(value string) string {
	replacer := strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`)
	return replacer.Replace(value)
}

func GetSubject(ctx context.Context, database *sql.DB, subjectID string) (Subject, error) {
	return getSubject(ctx, newQuerier(database), subjectID)
}

// SetPlatformAdmin 授予或撤销平台管理员。撤销时保证库里仍至少有一个启用的平台管理员，
// 否则返回 ErrLastAdministrator（与禁用最后一个管理员一致，避免把管理台锁死）。
func SetPlatformAdmin(ctx context.Context, database *sql.DB, actorSubjectID string, subjectID string, value bool) (Subject, error) {
	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return Subject{}, fmt.Errorf("begin set platform admin transaction: %w", err)
	}
	defer transaction.Rollback()

	subject, err := getSubject(ctx, transaction, subjectID)
	if err != nil {
		return Subject{}, err
	}
	now := time.Now().UTC()
	if subject.PlatformAdmin != value {
		if !value {
			var remaining int64
			if err := namedGet(ctx, transaction, &remaining, countEnabledPlatformAdminsExcludingSubjectID, map[string]any{
				"status":     StatusEnabled,
				"subject_id": subjectID,
			}); err != nil {
				return Subject{}, fmt.Errorf("count remaining platform administrators: %w", err)
			}
			if remaining == 0 {
				return Subject{}, ErrLastAdministrator
			}
		}
		if err := namedExec(ctx, transaction, setSubjectPlatformAdmin, map[string]any{
			"id":                subjectID,
			"is_platform_admin": value,
			"updated_at":        now,
		}); err != nil {
			return Subject{}, fmt.Errorf("update platform administrator: %w", err)
		}
		action := AuditActionRoleGranted
		if !value {
			action = AuditActionRoleRevoked
		}
		if err := insertAuditEvent(ctx, transaction, auditEvent{
			Action:          action,
			Outcome:         OutcomeSucceeded,
			ActorSubjectID:  actorSubjectID,
			TargetSubjectID: subjectID,
			Metadata:        fmt.Sprintf(`{"platform_admin":%t}`, value),
		}, now); err != nil {
			return Subject{}, fmt.Errorf("write platform administrator audit event: %w", err)
		}
		subject.PlatformAdmin = value
		subject.UpdatedAt = now
	}

	if err := transaction.Commit(); err != nil {
		return Subject{}, fmt.Errorf("commit set platform admin transaction: %w", err)
	}
	return subject, nil
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

	identifierType := IdentifierTypeAccount
	if strings.Contains(identifier, "@") {
		identifierType = IdentifierTypeEmail
	}

	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return Subject{}, fmt.Errorf("begin create subject transaction: %w", err)
	}
	defer transaction.Rollback()

	subject, err := createSubjectRecord(ctx, transaction, actorSubjectID, displayName, identifier, identifierType, &passwordHash, time.Now().UTC())
	if err != nil {
		return Subject{}, err
	}
	if err := transaction.Commit(); err != nil {
		return Subject{}, fmt.Errorf("commit create subject transaction: %w", err)
	}
	return subject, nil
}

// createSubjectRecord 是创建主体的唯一实现：管理台建号与邮件链接自助建号共用。
// 调用方负责事务；passwordHash 为 nil 表示无密码主体（只能通过邮件链接登录）。
func createSubjectRecord(ctx context.Context, queries querier, actorSubjectID string, displayName string, identifier string, identifierType string, passwordHash *string, now time.Time) (Subject, error) {
	var existingSubjectID string
	err := namedGet(ctx, queries, &existingSubjectID, getIdentifierSubjectID, map[string]any{
		"normalized_value": identifier,
	})
	if err == nil {
		return Subject{}, ErrIdentifierAlreadyExists
	}
	if !errors.Is(err, sql.ErrNoRows) {
		return Subject{}, fmt.Errorf("check account identifier: %w", err)
	}

	subjectID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}
	identifierID, err := NewULID(now)
	if err != nil {
		return Subject{}, err
	}
	if err := namedExec(ctx, queries, createSubject, map[string]any{
		"id":               subjectID,
		"status":           StatusEnabled,
		"security_version": 1,
		"metadata":         "{}",
		"created_at":       now,
		"updated_at":       now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create subject: %w", err)
	}
	if err := namedExec(ctx, queries, createProfile, map[string]any{
		"subject_id":   subjectID,
		"display_name": displayName,
		"created_at":   now,
		"updated_at":   now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create subject profile: %w", err)
	}
	if err := namedExec(ctx, queries, createIdentifier, map[string]any{
		"id":               identifierID,
		"subject_id":       subjectID,
		"identifier_type":  identifierType,
		"identifier_value": identifier,
		"normalized_value": identifier,
		"identifier_usage": IdentifierUsagePrimaryLogin,
		"status":           StatusEnabled,
		"created_at":       now,
		"updated_at":       now,
	}); err != nil {
		return Subject{}, fmt.Errorf("create account identifier: %w", err)
	}
	if passwordHash != nil {
		credentialID, err := NewULID(now)
		if err != nil {
			return Subject{}, err
		}
		if err := namedExec(ctx, queries, createPasswordCredential, map[string]any{
			"id":                credentialID,
			"subject_id":        subjectID,
			"password_hash":     *passwordHash,
			"password_revision": 1,
			"credential_status": CredentialStatusValid,
			"changed_at":        now,
			"created_at":        now,
			"updated_at":        now,
		}); err != nil {
			return Subject{}, fmt.Errorf("create password credential: %w", err)
		}
	}
	if err := insertAuditEvent(ctx, queries, auditEvent{
		Action:          AuditActionSubjectCreated,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  actorSubjectID,
		TargetSubjectID: subjectID,
	}, now); err != nil {
		return Subject{}, fmt.Errorf("write subject creation audit event: %w", err)
	}

	return Subject{
		ID:              subjectID,
		Status:          StatusEnabled,
		SecurityVersion: 1,
		DisplayName:     displayName,
		Identifier:      identifier,
		PlatformAdmin:   false,
		CreatedAt:       now,
		UpdatedAt:       now,
	}, nil
}

func DisableSubject(ctx context.Context, database *sql.DB, actorSubjectID string, subjectID string) (Subject, error) {
	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return Subject{}, fmt.Errorf("begin disable subject transaction: %w", err)
	}
	defer transaction.Rollback()

	subject, err := getSubject(ctx, transaction, subjectID)
	if err != nil {
		return Subject{}, err
	}
	if subject.Status == StatusDisabled {
		if err := transaction.Commit(); err != nil {
			return Subject{}, fmt.Errorf("commit existing disabled subject: %w", err)
		}
		return subject, nil
	}

	if subject.PlatformAdmin {
		var remainingAdministrators int64
		err := namedGet(ctx, transaction, &remainingAdministrators, countEnabledPlatformAdminsExcludingSubjectID, map[string]any{
			"status":     StatusEnabled,
			"subject_id": subjectID,
		})
		if err != nil {
			return Subject{}, fmt.Errorf("count remaining administrators: %w", err)
		}
		if remainingAdministrators == 0 {
			return Subject{}, ErrLastAdministrator
		}
	}

	now := time.Now().UTC()
	updated, err := namedExecRows(ctx, transaction, disableSubject, map[string]any{
		"disabled_status": StatusDisabled,
		"disabled_at":     now,
		"updated_at":      now,
		"id":              subjectID,
		"enabled_status":  StatusEnabled,
	})
	if err != nil {
		return Subject{}, fmt.Errorf("disable subject: %w", err)
	}
	if updated != 1 {
		return Subject{}, fmt.Errorf("disable subject: expected one enabled subject, updated %d", updated)
	}
	if err := namedExec(ctx, transaction, revokeActiveSessionsBySubjectID, map[string]any{
		"revoked_at":     now,
		"revoked_reason": RevokedReasonSubjectDisabled,
		"subject_id":     subjectID,
	}); err != nil {
		return Subject{}, fmt.Errorf("revoke subject sessions: %w", err)
	}
	if err := insertAuditEvent(ctx, transaction, auditEvent{
		Action:          AuditActionSubjectStatusChanged,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  actorSubjectID,
		TargetSubjectID: subjectID,
		Metadata:        fmt.Sprintf(`{"status":%q}`, StatusDisabled),
	}, now); err != nil {
		return Subject{}, fmt.Errorf("write subject disable audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return Subject{}, fmt.Errorf("commit disable subject transaction: %w", err)
	}

	subject.Status = StatusDisabled
	subject.SecurityVersion++
	subject.UpdatedAt = now
	return subject, nil
}

// EnableSubject re-enables a disabled subject. Existing sessions were revoked
// at disable time, so the next sign-in starts a fresh session.
func EnableSubject(ctx context.Context, database *sql.DB, actorSubjectID string, subjectID string) (Subject, error) {
	transaction, err := newQuerier(database).BeginTxx(ctx, nil)
	if err != nil {
		return Subject{}, fmt.Errorf("begin enable subject transaction: %w", err)
	}
	defer transaction.Rollback()

	subject, err := getSubject(ctx, transaction, subjectID)
	if err != nil {
		return Subject{}, err
	}
	if subject.Status == StatusEnabled {
		if err := transaction.Commit(); err != nil {
			return Subject{}, fmt.Errorf("commit existing enabled subject: %w", err)
		}
		return subject, nil
	}

	now := time.Now().UTC()
	updated, err := namedExecRows(ctx, transaction, enableSubject, map[string]any{
		"enabled_status":  StatusEnabled,
		"disabled_status": StatusDisabled,
		"updated_at":      now,
		"id":              subjectID,
	})
	if err != nil {
		return Subject{}, fmt.Errorf("enable subject: %w", err)
	}
	if updated != 1 {
		return Subject{}, fmt.Errorf("enable subject: expected one disabled subject, updated %d", updated)
	}
	if err := insertAuditEvent(ctx, transaction, auditEvent{
		Action:          AuditActionSubjectStatusChanged,
		Outcome:         OutcomeSucceeded,
		ActorSubjectID:  actorSubjectID,
		TargetSubjectID: subjectID,
		Metadata:        fmt.Sprintf(`{"status":%q}`, StatusEnabled),
	}, now); err != nil {
		return Subject{}, fmt.Errorf("write subject enable audit event: %w", err)
	}
	if err := transaction.Commit(); err != nil {
		return Subject{}, fmt.Errorf("commit enable subject transaction: %w", err)
	}

	subject.Status = StatusEnabled
	subject.SecurityVersion++
	subject.UpdatedAt = now
	return subject, nil
}

func getSubject(ctx context.Context, queries querier, subjectID string) (Subject, error) {
	var row managementSubjectRow
	err := namedGet(ctx, queries, &row, getSubjectForManagement, map[string]any{
		"id":               subjectID,
		"identifier_usage": IdentifierUsagePrimaryLogin,
	})
	if errors.Is(err, sql.ErrNoRows) {
		return Subject{}, ErrSubjectNotFound
	}
	if err != nil {
		return Subject{}, fmt.Errorf("get subject for management: %w", err)
	}
	return subjectFromManagementValues(
		row.ID,
		row.Status,
		row.SecurityVersion,
		row.PlatformAdmin,
		row.DisplayName,
		row.IdentifierValue,
		row.CreatedAt,
		row.UpdatedAt,
	), nil
}

func subjectFromManagementValues(subjectID string, status string, securityVersion int64, platformAdmin bool, displayName string, identifier string, createdAt time.Time, updatedAt time.Time) Subject {
	return Subject{
		ID:              subjectID,
		Status:          status,
		SecurityVersion: securityVersion,
		DisplayName:     displayName,
		Identifier:      identifier,
		PlatformAdmin:   platformAdmin,
		CreatedAt:       createdAt,
		UpdatedAt:       updatedAt,
	}
}

func validateDisplayName(value string) (string, error) {
	value = strings.TrimSpace(value)
	if length := utf8.RuneCountInString(value); length < 1 || length > 120 {
		return "", fmt.Errorf("display name must contain 1 to 120 characters")
	}
	return value, nil
}
