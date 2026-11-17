package positions

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

// selectColumns reads a posting joined with its organisation name.
const selectColumns = `p.id, p.organization_id, o.name, p.title, p.category,
	p.employment_type, p.work_mode, p.headcount, p.salary_negotiable, p.salary_min, p.salary_max,
	p.salary_unit, p.education, p.experience, p.province, p.city, p.district,
	p.status, p.published_at, p.expires_at, p.open_until, p.view_count, p.created_by, p.metadata,
	p.created_at, p.updated_at`

// returningColumns serves UPDATE ... RETURNING and SELECT ... FOR UPDATE, where
// the joined aliases are unavailable.
const returningColumns = `id, organization_id,
	(SELECT name FROM organizations WHERE id = job_positions.organization_id),
	title, category, employment_type, work_mode, headcount, salary_negotiable, salary_min,
	salary_max, salary_unit, education, experience, province, city, district,
	status, published_at, expires_at, open_until, view_count, created_by, metadata,
	created_at, updated_at`

// Store persists job postings in PostgreSQL.
type Store struct {
	pool *pgxpool.Pool
}

// NewStore binds the store to a connection pool.
func NewStore(pool *pgxpool.Pool) *Store {
	return &Store{pool: pool}
}

// expireDueOpenings stops postings whose 14-day recruitment window has ended.
// Expiry is lazy — there is no scheduler — so every read and state change
// applies it first. The partial index on open_until keeps the update cheap when
// nothing is due, and the update is idempotent.
func (s *Store) expireDueOpenings(ctx context.Context) error {
	if _, err := s.pool.Exec(ctx, `
		UPDATE job_positions
		SET status = '已停招', updated_at = NOW()
		WHERE status = '招聘中' AND open_until IS NOT NULL AND open_until <= NOW()`); err != nil {
		return fmt.Errorf("expire open positions: %w", err)
	}
	return nil
}

// Create inserts a draft posting in the caller's organisation.
func (s *Store) Create(ctx context.Context, input CreateInput, actorSubjectID string) (Position, error) {
	positionID, err := id.New(time.Now().UTC())
	if err != nil {
		return Position{}, err
	}
	metadata, err := json.Marshal(input.Metadata)
	if err != nil {
		return Position{}, fmt.Errorf("encode position metadata: %w", err)
	}

	position, err := scanPosition(s.pool.QueryRow(ctx, `
		INSERT INTO job_positions (
			id, organization_id, title, category, employment_type, work_mode, headcount,
			salary_negotiable, salary_min, salary_max, salary_unit, education, experience,
			province, city, district, status, expires_at, created_by, metadata
		) VALUES (
			$1, $2, $3, $4, $5, $6, $7,
			$8, $9, $10, $11, $12, $13,
			$14, $15, $16, $17, $18, $19, $20::jsonb
		)
		RETURNING `+returningColumns,
		positionID, input.OrganizationID, input.Title, input.Category, input.EmploymentType,
		input.WorkMode, input.Headcount, input.SalaryNegotiable, input.SalaryMin, input.SalaryMax,
		input.SalaryUnit, input.Education, input.Experience, input.Province, input.City,
		input.District, string(StatusDraft), input.ExpiresAt, actorSubjectID, string(metadata),
	))
	if err != nil {
		return Position{}, translate(err, "岗位已存在")
	}
	return position, nil
}

// Get reads one posting by ULID.
func (s *Store) Get(ctx context.Context, positionID string) (Position, error) {
	if err := s.expireDueOpenings(ctx); err != nil {
		return Position{}, err
	}
	position, err := scanPosition(s.pool.QueryRow(ctx, `
		SELECT `+selectColumns+`
		FROM job_positions p JOIN organizations o ON o.id = p.organization_id
		WHERE p.id = $1`, positionID))
	if err != nil {
		return Position{}, translate(err, "")
	}
	return position, nil
}

// IncreaseViewCount increments the public view counter.
func (s *Store) IncreaseViewCount(ctx context.Context, positionID string) (int64, error) {
	var views int64
	if err := s.pool.QueryRow(ctx,
		`UPDATE job_positions SET view_count = view_count + 1 WHERE id = $1 RETURNING view_count`,
		positionID,
	).Scan(&views); err != nil {
		return 0, translate(err, "")
	}
	return views, nil
}

// List returns public postings, optionally filtered.
func (s *Store) List(ctx context.Context, filter Filter) ([]Position, int64, error) {
	if err := s.expireDueOpenings(ctx); err != nil {
		return nil, 0, err
	}
	conditions := &dbx.Conditions{}
	conditions.Raw("p.status = '" + string(StatusOpen) + "'")
	conditions.Raw("(p.open_until IS NULL OR p.open_until > NOW())")
	conditions.Raw("(p.expires_at IS NULL OR p.expires_at > NOW())")
	conditions.EQ("p.organization_id", filter.OrganizationID)
	conditions.EQ("p.city", filter.City)
	conditions.EQ("p.category", filter.Category)
	conditions.EQ("p.employment_type", filter.EmploymentType)
	conditions.EQ("p.education", filter.Education)
	conditions.EQ("p.experience", filter.Experience)
	conditions.ILIKE([]string{"p.title", "p.metadata->>'description'", "o.name"}, filter.Query)
	if filter.MinimumSalary > 0 {
		salary := conditions.Add(filter.MinimumSalary)
		conditions.Raw(fmt.Sprintf("(p.salary_negotiable OR COALESCE(p.salary_max, p.salary_min) >= %s)", salary))
	}

	where := conditions.Where()
	filterArgs := append([]any(nil), conditions.Args()...)

	limit := conditions.Add(filter.Limit)
	offset := conditions.Add(filter.Offset)

	rows, err := s.pool.Query(ctx, `
		SELECT `+selectColumns+`, COUNT(*) OVER() AS total
		FROM job_positions p JOIN organizations o ON o.id = p.organization_id`+
		where+`
		ORDER BY p.published_at DESC NULLS LAST, p.created_at DESC
		LIMIT `+limit+` OFFSET `+offset, conditions.Args()...)
	if err != nil {
		return nil, 0, fmt.Errorf("list positions: %w", err)
	}
	defer rows.Close()

	records, total, err := collectPositions(rows)
	if err != nil {
		return nil, 0, err
	}
	// COUNT(*) OVER() disappears when the offset overshoots the result set, so
	// the total is re-read to keep pagination honest.
	if total == 0 && filter.Offset > 0 {
		if err := s.pool.QueryRow(ctx, `
			SELECT COUNT(*) FROM job_positions p JOIN organizations o ON o.id = p.organization_id`+
			where, filterArgs...).Scan(&total); err != nil {
			return nil, 0, fmt.Errorf("count positions: %w", err)
		}
	}
	return records, total, nil
}

// ListByOrganization returns every posting of an organisation, drafts included,
// for the member-facing management view.
func (s *Store) ListByOrganization(ctx context.Context, organizationID string, limit, offset int32) ([]Position, int64, error) {
	if err := s.expireDueOpenings(ctx); err != nil {
		return nil, 0, err
	}
	conditions := &dbx.Conditions{}
	conditions.EQ("p.organization_id", organizationID)
	where := conditions.Where()
	filterArgs := append([]any(nil), conditions.Args()...)

	limitPlaceholder := conditions.Add(limit)
	offsetPlaceholder := conditions.Add(offset)

	rows, err := s.pool.Query(ctx, `
		SELECT `+selectColumns+`, COUNT(*) OVER() AS total
		FROM job_positions p JOIN organizations o ON o.id = p.organization_id`+
		where+`
		ORDER BY p.created_at DESC
		LIMIT `+limitPlaceholder+` OFFSET `+offsetPlaceholder, conditions.Args()...)
	if err != nil {
		return nil, 0, fmt.Errorf("list organisation positions: %w", err)
	}
	defer rows.Close()

	records, total, err := collectPositions(rows)
	if err != nil {
		return nil, 0, err
	}
	if total == 0 && offset > 0 {
		if err := s.pool.QueryRow(ctx, `
			SELECT COUNT(*) FROM job_positions p JOIN organizations o ON o.id = p.organization_id`+
			where, filterArgs...).Scan(&total); err != nil {
			return nil, 0, fmt.Errorf("count organisation positions: %w", err)
		}
	}
	return records, total, nil
}

// Update applies a partial change. Salary consistency is re-checked against the
// stored row because a patch may send only one end of the range.
func (s *Store) Update(ctx context.Context, positionID string, input UpdateInput) (Position, error) {
	var metadata any
	if input.Metadata != nil {
		encoded, err := json.Marshal(*input.Metadata)
		if err != nil {
			return Position{}, fmt.Errorf("encode position metadata: %w", err)
		}
		metadata = string(encoded)
	}

	tx, err := s.pool.Begin(ctx)
	if err != nil {
		return Position{}, fmt.Errorf("begin position update: %w", err)
	}
	defer func() { _ = tx.Rollback(ctx) }()

	current, err := scanPosition(tx.QueryRow(ctx, `
		SELECT `+returningColumns+` FROM job_positions WHERE id = $1 FOR UPDATE`, positionID))
	if err != nil {
		return Position{}, translate(err, "")
	}

	negotiable := current.SalaryNegotiable
	if input.SalaryNegotiable != nil {
		negotiable = *input.SalaryNegotiable
	}
	minimum := current.SalaryMin
	if input.SalaryMin != nil {
		minimum = input.SalaryMin
	}
	maximum := current.SalaryMax
	if input.SalaryMax != nil {
		maximum = input.SalaryMax
	}
	// 面议 clears the range; a priced posting must stay consistent with the
	// stored value for whichever end the patch did not send.
	if negotiable {
		minimum, maximum = nil, nil
	} else if minimum, maximum, err = salary(false, minimum, maximum); err != nil {
		return Position{}, err
	}

	position, err := scanPosition(tx.QueryRow(ctx, `
		UPDATE job_positions SET
			title             = COALESCE($2, title),
			category          = COALESCE($3, category),
			employment_type   = COALESCE($4, employment_type),
			work_mode         = COALESCE($5, work_mode),
			headcount         = COALESCE($6, headcount),
			salary_negotiable = $7,
			salary_min        = $8,
			salary_max        = $9,
			salary_unit       = COALESCE($10, salary_unit),
			education         = COALESCE($11, education),
			experience        = COALESCE($12, experience),
			province          = COALESCE($13, province),
			city              = COALESCE($14, city),
			district          = COALESCE($15, district),
			expires_at        = COALESCE($16, expires_at),
			metadata          = COALESCE($17::jsonb, metadata),
			updated_at        = NOW()
		WHERE id = $1
		RETURNING `+returningColumns,
		positionID, input.Title, input.Category, input.EmploymentType, input.WorkMode,
		input.Headcount, negotiable, minimum, maximum, input.SalaryUnit, input.Education,
		input.Experience, input.Province, input.City, input.District, input.ExpiresAt, metadata,
	))
	if err != nil {
		return Position{}, translate(err, "")
	}
	if err := tx.Commit(ctx); err != nil {
		return Position{}, fmt.Errorf("commit position update: %w", err)
	}
	return position, nil
}

// SetStatus moves a posting through its publication state machine. The first
// transition into 招聘中 stamps published_at and opens a 14-day window; a
// stopped or closed posting reopened through 招聘中 gets 7 more days.
func (s *Store) SetStatus(ctx context.Context, positionID string, next Status) (Position, error) {
	if err := s.expireDueOpenings(ctx); err != nil {
		return Position{}, err
	}
	position, err := scanPosition(s.pool.QueryRow(ctx, `
		UPDATE job_positions SET
			status = $2::text,
			published_at = CASE WHEN $3 THEN COALESCE(published_at, NOW()) ELSE published_at END,
			open_until = CASE
				WHEN $2::text <> '招聘中' THEN CASE WHEN $2::text = '已关闭' THEN NULL ELSE open_until END
				WHEN published_at IS NULL THEN NOW() + INTERVAL '14 days'
				WHEN open_until IS NULL OR open_until <= NOW() THEN NOW() + INTERVAL '7 days'
				ELSE open_until
			END,
			updated_at = NOW()
		WHERE id = $1
		RETURNING `+returningColumns,
		positionID, string(next), next == StatusOpen,
	))
	if err != nil {
		return Position{}, translate(err, "")
	}
	return position, nil
}

// Delete removes a posting permanently. Applications are not modelled yet, so
// there is no dependent data to cascade.
func (s *Store) Delete(ctx context.Context, positionID string) error {
	tag, err := s.pool.Exec(ctx, `DELETE FROM job_positions WHERE id = $1`, positionID)
	if err != nil {
		return fmt.Errorf("delete position: %w", err)
	}
	if tag.RowsAffected() == 0 {
		return &apierr.NotFound{Resource: "岗位"}
	}
	return nil
}

func scanPosition(row pgx.Row) (Position, error) {
	var (
		position Position
		raw      []byte
	)
	if err := row.Scan(
		&position.ID, &position.OrganizationID, &position.OrganizationName, &position.Title,
		&position.Category, &position.EmploymentType, &position.WorkMode, &position.Headcount,
		&position.SalaryNegotiable, &position.SalaryMin, &position.SalaryMax, &position.SalaryUnit,
		&position.Education, &position.Experience, &position.Province, &position.City,
		&position.District, &position.Status, &position.PublishedAt, &position.ExpiresAt,
		&position.OpenUntil, &position.ViewCount, &position.CreatedBy, &raw,
		&position.CreatedAt, &position.UpdatedAt,
	); err != nil {
		return Position{}, err
	}
	if err := json.Unmarshal(raw, &position.Metadata); err != nil {
		return Position{}, fmt.Errorf("decode position metadata: %w", err)
	}
	return position, nil
}

func collectPositions(rows pgx.Rows) ([]Position, int64, error) {
	out := make([]Position, 0, 16)
	var total int64
	for rows.Next() {
		var (
			position Position
			raw      []byte
		)
		if err := rows.Scan(
			&position.ID, &position.OrganizationID, &position.OrganizationName, &position.Title,
			&position.Category, &position.EmploymentType, &position.WorkMode, &position.Headcount,
			&position.SalaryNegotiable, &position.SalaryMin, &position.SalaryMax, &position.SalaryUnit,
			&position.Education, &position.Experience, &position.Province, &position.City,
			&position.District, &position.Status, &position.PublishedAt, &position.ExpiresAt,
			&position.OpenUntil, &position.ViewCount, &position.CreatedBy, &raw,
			&position.CreatedAt, &position.UpdatedAt, &total,
		); err != nil {
			return nil, 0, fmt.Errorf("scan position: %w", err)
		}
		if err := json.Unmarshal(raw, &position.Metadata); err != nil {
			return nil, 0, fmt.Errorf("decode position metadata: %w", err)
		}
		out = append(out, position)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, fmt.Errorf("list positions: %w", err)
	}
	return out, total, nil
}

func translate(err error, conflictMessage string) error {
	if errors.Is(err, pgx.ErrNoRows) {
		return &apierr.NotFound{Resource: "岗位"}
	}
	var pgError *pgconn.PgError
	if errors.As(err, &pgError) {
		switch pgError.Code {
		case uniqueViolation:
			return &apierr.Conflict{Message: conflictMessage}
		case "23514", "23503":
			return apierr.Invalid("岗位数据不符合约束")
		}
	}
	return err
}
