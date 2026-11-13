package organizations

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgconn"
	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
	"github.com/ovaphlow/pitchfork/agora-api/internal/dbx"
	"github.com/ovaphlow/pitchfork/agora-api/internal/id"
)

const uniqueViolation = "23505"

const organizationColumns = `id, name, short_name, org_type, logo_file_id, province, city, district,
	status, reject_reason, owner_subject_id, COALESCE(reviewed_by, ''), reviewed_at,
	metadata, created_at, updated_at`

// Store persists organisations and their memberships in PostgreSQL.
type Store struct {
	pool *pgxpool.Pool
}

// NewStore binds the store to a connection pool.
func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// Membership pairs an organisation with the caller's role in it.
type Membership struct {
	Organization Organization `json:"organization"`
	MemberRole   Role         `json:"member_role"`
}

// Create inserts an organisation and its owner membership in one transaction.
func (s *Store) Create(ctx context.Context, input CreateInput, ownerSubjectID string) (Organization, error) {
	organizationID, err := id.New(time.Now().UTC())
	if err != nil {
		return Organization{}, err
	}
	memberID, err := id.New(time.Now().UTC())
	if err != nil {
		return Organization{}, err
	}

	metadata, err := json.Marshal(input.Metadata)
	if err != nil {
		return Organization{}, fmt.Errorf("encode organisation metadata: %w", err)
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Organization{}, fmt.Errorf("begin organisation insert: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	organization, err := scanOrganization(tx.QueryRow(ctx, `
		INSERT INTO organizations (
			id, name, short_name, org_type, logo_file_id, province, city, district,
			status, owner_subject_id, metadata
		) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11::jsonb)
		RETURNING `+organizationColumns,
		organizationID, input.Name, input.ShortName, input.OrgType, input.LogoFileID,
		input.Province, input.City, input.District, string(StatusPending), ownerSubjectID, string(metadata),
	))
	if err != nil {
		return Organization{}, translate(err, "该机构已存在")
	}

	if _, err := tx.Exec(ctx, `
		INSERT INTO organization_members (id, organization_id, subject_id, member_role)
		VALUES ($1, $2, $3, $4)`,
		memberID, organizationID, ownerSubjectID, string(RoleOwner),
	); err != nil {
		return Organization{}, translate(err, "机构成员已存在")
	}

	if err := tx.Commit(ctx); err != nil {
		return Organization{}, fmt.Errorf("commit organisation insert: %w", err)
	}
	return organization, nil
}

// Get reads one organisation by ULID.
func (s *Store) Get(ctx context.Context, organizationID string) (Organization, error) {
	organization, err := scanOrganization(s.pool.QueryRow(ctx,
		`SELECT `+organizationColumns+` FROM organizations WHERE id = $1`, organizationID))
	if err != nil {
		return Organization{}, translate(err, "")
	}
	return organization, nil
}

// Status reads only the review state, which the position store needs to decide
// whether publishing is allowed.
func (s *Store) Status(ctx context.Context, organizationID string) (Status, error) {
	var status Status
	if err := s.pool.QueryRow(ctx,
		`SELECT status FROM organizations WHERE id = $1`, organizationID,
	).Scan(&status); err != nil {
		return "", translate(err, "")
	}
	return status, nil
}

// Update applies a partial change and returns the stored record.
func (s *Store) Update(ctx context.Context, organizationID string, input UpdateInput) (Organization, error) {
	var metadata any
	if input.Metadata != nil {
		encoded, err := json.Marshal(*input.Metadata)
		if err != nil {
			return Organization{}, fmt.Errorf("encode organisation metadata: %w", err)
		}
		metadata = string(encoded)
	}

	organization, err := scanOrganization(s.pool.QueryRow(ctx, `
		UPDATE organizations SET
			name        = COALESCE($2, name),
			short_name  = COALESCE($3, short_name),
			org_type    = COALESCE($4, org_type),
			logo_file_id = COALESCE($5, logo_file_id),
			province    = COALESCE($6, province),
			city        = COALESCE($7, city),
			district    = COALESCE($8, district),
			metadata    = COALESCE($9::jsonb, metadata),
			updated_at  = NOW()
		WHERE id = $1
		RETURNING `+organizationColumns,
		organizationID, input.Name, input.ShortName, input.OrgType, input.LogoFileID,
		input.Province, input.City, input.District, metadata,
	))
	if err != nil {
		return Organization{}, translate(err, "该机构已存在")
	}
	return organization, nil
}

// Review records the platform decision on an organisation.
func (s *Store) Review(ctx context.Context, organizationID string, input ReviewInput, reviewer string) (Organization, error) {
	organization, err := scanOrganization(s.pool.QueryRow(ctx, `
		UPDATE organizations SET
			status        = $2,
			reject_reason = $3,
			reviewed_by   = $4,
			reviewed_at   = NOW(),
			updated_at    = NOW()
		WHERE id = $1
		RETURNING `+organizationColumns,
		organizationID, string(input.Status), input.Reason, reviewer,
	))
	if err != nil {
		return Organization{}, translate(err, "")
	}
	return organization, nil
}

// List returns verified organisations for the public directory.
func (s *Store) List(ctx context.Context, filter Filter) ([]Organization, int64, error) {
	conditions := &dbx.Conditions{}
	conditions.Raw("status = '" + string(StatusVerified) + "'")
	conditions.EQ("city", filter.City)
	conditions.EQ("org_type", filter.OrgType)
	conditions.ILIKE([]string{"name", "short_name", "metadata->>'industry'"}, filter.Query)

	where := conditions.Where()
	filterArgs := append([]any(nil), conditions.Args()...)

	limit := conditions.Add(filter.Limit)
	offset := conditions.Add(filter.Offset)

	rows, err := s.pool.Query(ctx, `
		SELECT `+organizationColumns+`, COUNT(*) OVER() AS total
		FROM organizations`+where+`
		ORDER BY created_at DESC
		LIMIT `+limit+` OFFSET `+offset, conditions.Args()...)
	if err != nil {
		return nil, 0, fmt.Errorf("list organisations: %w", err)
	}
	defer rows.Close()

	records, total, err := collectOrganizations(rows)
	if err != nil {
		return nil, 0, err
	}
	// COUNT(*) OVER() disappears when the offset overshoots the result set, so
	// the total is re-read to keep pagination honest.
	if total == 0 && filter.Offset > 0 {
		if err := s.pool.QueryRow(ctx, `SELECT COUNT(*) FROM organizations`+where, filterArgs...).Scan(&total); err != nil {
			return nil, 0, fmt.Errorf("count organisations: %w", err)
		}
	}
	return records, total, nil
}

// ListForSubject returns every organisation the subject belongs to.
func (s *Store) ListForSubject(ctx context.Context, subjectID string) ([]Membership, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT o.id, o.name, o.short_name, o.org_type, o.logo_file_id, o.province, o.city, o.district,
		       o.status, o.reject_reason, o.owner_subject_id, COALESCE(o.reviewed_by, ''), o.reviewed_at,
		       o.metadata, o.created_at, o.updated_at, m.member_role
		FROM organization_members m
		JOIN organizations o ON o.id = m.organization_id
		WHERE m.subject_id = $1
		ORDER BY o.created_at DESC`, subjectID)
	if err != nil {
		return nil, fmt.Errorf("list memberships: %w", err)
	}
	defer rows.Close()

	memberships := make([]Membership, 0, 4)
	for rows.Next() {
		var (
			organization Organization
			raw          []byte
			role         Role
		)
		if err := rows.Scan(
			&organization.ID, &organization.Name, &organization.ShortName, &organization.OrgType,
			&organization.LogoFileID, &organization.Province, &organization.City, &organization.District,
			&organization.Status, &organization.RejectReason, &organization.OwnerSubjectID,
			&organization.ReviewedBy, &organization.ReviewedAt, &raw,
			&organization.CreatedAt, &organization.UpdatedAt, &role,
		); err != nil {
			return nil, fmt.Errorf("scan membership: %w", err)
		}
		if err := json.Unmarshal(raw, &organization.Metadata); err != nil {
			return nil, fmt.Errorf("decode organisation metadata: %w", err)
		}
		memberships = append(memberships, Membership{Organization: organization, MemberRole: role})
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("list memberships: %w", err)
	}
	return memberships, nil
}

// Role resolves the subject's role in the organisation.
func (s *Store) Role(ctx context.Context, organizationID, subjectID string) (Role, error) {
	var role Role
	if err := s.pool.QueryRow(ctx, `
		SELECT member_role FROM organization_members
		WHERE organization_id = $1 AND subject_id = $2`,
		organizationID, subjectID,
	).Scan(&role); err != nil {
		if err == pgx.ErrNoRows {
			return "", ErrNotMember
		}
		return "", fmt.Errorf("read member role: %w", err)
	}
	return role, nil
}

// Members lists every member of an organisation.
func (s *Store) Members(ctx context.Context, organizationID string) ([]Member, error) {
	rows, err := s.pool.Query(ctx, `
		SELECT subject_id, member_role, organization_id, created_at, updated_at
		FROM organization_members
		WHERE organization_id = $1
		ORDER BY CASE member_role WHEN '所有者' THEN 0 WHEN '管理员' THEN 1 ELSE 2 END, created_at`,
		organizationID)
	if err != nil {
		return nil, fmt.Errorf("list members: %w", err)
	}
	defer rows.Close()

	members := make([]Member, 0, 4)
	for rows.Next() {
		var member Member
		if err := rows.Scan(&member.SubjectID, &member.MemberRole, &member.OrganizationID, &member.CreatedAt, &member.UpdatedAt); err != nil {
			return nil, fmt.Errorf("scan member: %w", err)
		}
		members = append(members, member)
	}
	if err := rows.Err(); err != nil {
		return nil, fmt.Errorf("list members: %w", err)
	}
	return members, nil
}

// AddMember adds one subject to an organisation.
func (s *Store) AddMember(ctx context.Context, organizationID, subjectID string, role Role) (Member, error) {
	memberID, err := id.New(time.Now().UTC())
	if err != nil {
		return Member{}, err
	}

	var member Member
	err = s.pool.QueryRow(ctx, `
		INSERT INTO organization_members (id, organization_id, subject_id, member_role)
		VALUES ($1, $2, $3, $4)
		RETURNING subject_id, member_role, organization_id, created_at, updated_at`,
		memberID, organizationID, subjectID, string(role),
	).Scan(&member.SubjectID, &member.MemberRole, &member.OrganizationID, &member.CreatedAt, &member.UpdatedAt)
	if err != nil {
		return Member{}, translate(err, "该成员已在机构中")
	}
	return member, nil
}

// RemoveMember deletes a non-owner membership.
func (s *Store) RemoveMember(ctx context.Context, organizationID, subjectID string) error {
	role, err := s.Role(ctx, organizationID, subjectID)
	if err != nil {
		if err == ErrNotMember {
			return &apierr.NotFound{Resource: "成员"}
		}
		return err
	}
	if role == RoleOwner {
		return &apierr.Conflict{Message: "所有者不能被移除"}
	}

	if _, err := s.pool.Exec(ctx, `
		DELETE FROM organization_members WHERE organization_id = $1 AND subject_id = $2`,
		organizationID, subjectID,
	); err != nil {
		return fmt.Errorf("delete member: %w", err)
	}
	return nil
}

func scanOrganization(row pgx.Row) (Organization, error) {
	var (
		organization Organization
		raw          []byte
	)
	if err := row.Scan(
		&organization.ID, &organization.Name, &organization.ShortName, &organization.OrgType,
		&organization.LogoFileID, &organization.Province, &organization.City, &organization.District,
		&organization.Status, &organization.RejectReason, &organization.OwnerSubjectID,
		&organization.ReviewedBy, &organization.ReviewedAt, &raw,
		&organization.CreatedAt, &organization.UpdatedAt,
	); err != nil {
		return Organization{}, err
	}
	if err := json.Unmarshal(raw, &organization.Metadata); err != nil {
		return Organization{}, fmt.Errorf("decode organisation metadata: %w", err)
	}
	return organization, nil
}

func collectOrganizations(rows pgx.Rows) ([]Organization, int64, error) {
	organizations := make([]Organization, 0, 16)
	var total int64
	for rows.Next() {
		var (
			organization Organization
			raw          []byte
		)
		if err := rows.Scan(
			&organization.ID, &organization.Name, &organization.ShortName, &organization.OrgType,
			&organization.LogoFileID, &organization.Province, &organization.City, &organization.District,
			&organization.Status, &organization.RejectReason, &organization.OwnerSubjectID,
			&organization.ReviewedBy, &organization.ReviewedAt, &raw,
			&organization.CreatedAt, &organization.UpdatedAt, &total,
		); err != nil {
			return nil, 0, fmt.Errorf("scan organisation: %w", err)
		}
		if err := json.Unmarshal(raw, &organization.Metadata); err != nil {
			return nil, 0, fmt.Errorf("decode organisation metadata: %w", err)
		}
		organizations = append(organizations, organization)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("list organisations: %w", err)
	}
	return organizations, total, nil
}

// translate maps driver errors onto API error kinds.
func translate(err error, conflictMessage string) error {
	if err == pgx.ErrNoRows {
		return &apierr.NotFound{Resource: "机构"}
	}
	var pgError *pgconn.PgError
	if errors.As(err, &pgError) && pgError.Code == uniqueViolation {
		return &apierr.Conflict{Message: conflictMessage}
	}
	if pgError != nil && pgError.Code == "23514" {
		return apierr.Invalid("机构数据不符合约束")
	}
	return err
}
