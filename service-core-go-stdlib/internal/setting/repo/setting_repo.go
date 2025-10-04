package repo

import (
	"context"
	"database/sql"
	"encoding/json"

	"strconv"
	"strings"

	"github.com/ovaphlow/pitchfork/service-core-go-stdlib/internal/setting/entity"
)

// Repo is the repository implementation for settings backed by PostgreSQL.
type Repo struct {
	db *sql.DB
}

// NewRepo constructs a new Repo with an existing *sql.DB connection.
func NewRepo(db *sql.DB) *Repo {
	return &Repo{db: db}
}

// EnsureTable ensures the settings table and its index exist.
// Fields (match internal/setting/entity/setting.go Setting struct):
// - id varchar(32) PRIMARY KEY
// - parent_id varchar(32)
// - root_id varchar(32)
// - record_meta jsonb
// - category varchar(32) (indexed)
// - key varchar(64)
// - value jsonb
// - value_type varchar(32)
// - sort_order integer
// - version bigint
// - status varchar(32)
// - ancestors jsonb
// - created_at timestamptz
// - updated_at timestamptz
func (r *Repo) EnsureTable(ctx context.Context) error {
	// Check if table exists using to_regclass (Postgres). If it exists, skip creation.
	var tblName sql.NullString
	err := r.db.QueryRowContext(ctx, "SELECT to_regclass('public.settings')").Scan(&tblName)
	if err != nil {
		return err
	}

	if !tblName.Valid {
		// Table does not exist, create it.
		createTable := `CREATE TABLE settings (
			id varchar PRIMARY KEY,
			parent_id varchar DEFAULT '',
			root_id varchar DEFAULT '',
			record_meta jsonb DEFAULT '{}'::jsonb,
			category varchar DEFAULT '',
			key varchar DEFAULT '',
			value jsonb DEFAULT '{}'::jsonb,
			value_type varchar DEFAULT 'json',
			sort_order integer DEFAULT 0,
			version bigint DEFAULT 1,
			status varchar DEFAULT '',
			ancestors jsonb DEFAULT '[]'::jsonb,
			created_at timestamptz DEFAULT now(),
			updated_at timestamptz DEFAULT now()
		)`
		if _, err := r.db.ExecContext(ctx, createTable); err != nil {
			return err
		}
	}

	// Check if index exists; use to_regclass for index name.
	var idxName sql.NullString
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_category')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createIndex := `CREATE INDEX idx_settings_category ON settings (category)`
		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}

	// Create index for parent_id
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_parent_id')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createIndex := `CREATE INDEX idx_settings_parent_id ON settings (parent_id)`
		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}

	// Create index for root_id
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_root_id')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createIndex := `CREATE INDEX idx_settings_root_id ON settings (root_id)`
		if _, err := r.db.ExecContext(ctx, createIndex); err != nil {
			return err
		}
	}

	// Ensure an index on key for fast lookups by key
	err = r.db.QueryRowContext(ctx, "SELECT to_regclass('public.idx_settings_key')").Scan(&idxName)
	if err != nil {
		return err
	}
	if !idxName.Valid {
		createKeyIdx := `CREATE INDEX idx_settings_key ON settings (key)`
		if _, err := r.db.ExecContext(ctx, createKeyIdx); err != nil {
			return err
		}
	}

	return nil
}

// GetByID fetches a setting by id.
func (r *Repo) GetByID(ctx context.Context, id string) (*entity.Setting, error) {
	var s entity.Setting
	var recordMeta, value, ancestors []byte
	err := r.db.QueryRowContext(ctx, `SELECT id, parent_id, root_id, record_meta, category, key, value, value_type, sort_order, version, status, ancestors, created_at, updated_at FROM settings WHERE id = $1`, id).Scan(
		&s.ID,
		&s.ParentID,
		&s.RootID,
		&recordMeta,
		&s.Category,
		&s.Key,
		&value,
		&s.ValueType,
		&s.SortOrder,
		&s.Version,
		&s.Status,
		&ancestors,
		&s.CreatedAt,
		&s.UpdatedAt,
	)
	if err != nil {
		return nil, err
	}
	s.RecordMeta = json.RawMessage(recordMeta)
	s.Value = json.RawMessage(value)
	s.Ancestors = json.RawMessage(ancestors)
	return &s, nil
}

// List returns settings filtered by category/parent/root (optional) with pagination.
func (r *Repo) List(ctx context.Context, category string, parentID string, rootID string, limit, offset int) ([]*entity.Setting, error) {
	if limit <= 0 {
		limit = 50
	}
	if offset < 0 {
		offset = 0
	}
	// Build dynamic WHERE clause depending on provided filters
	base := `SELECT id, parent_id, root_id, record_meta, category, key, value, value_type, sort_order, version, status, ancestors, created_at, updated_at FROM settings`
	var where []string
	var args []any
	argIdx := 1
	if category != "" {
		where = append(where, "category = $"+strconv.Itoa(argIdx))
		args = append(args, category)
		argIdx++
	}
	if parentID != "" {
		where = append(where, "parent_id = $"+strconv.Itoa(argIdx))
		args = append(args, parentID)
		argIdx++
	}
	if rootID != "" {
		where = append(where, "root_id = $"+strconv.Itoa(argIdx))
		args = append(args, rootID)
		argIdx++
	}
	var q string
	if len(where) > 0 {
		q = base + " WHERE " + strings.Join(where, " AND ") + " ORDER BY sort_order, created_at DESC LIMIT $" + strconv.Itoa(argIdx) + " OFFSET $" + strconv.Itoa(argIdx+1)
		args = append(args, limit, offset)
	} else {
		q = base + " ORDER BY sort_order, created_at DESC LIMIT $" + strconv.Itoa(argIdx) + " OFFSET $" + strconv.Itoa(argIdx+1)
		args = append(args, limit, offset)
	}
	rows, err := r.db.QueryContext(ctx, q, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	var res []*entity.Setting
	for rows.Next() {
		var s entity.Setting
		var recordMeta, value, ancestors []byte
		if err := rows.Scan(&s.ID, &s.ParentID, &s.RootID, &recordMeta, &s.Category, &s.Key, &value, &s.ValueType, &s.SortOrder, &s.Version, &s.Status, &ancestors, &s.CreatedAt, &s.UpdatedAt); err != nil {
			return nil, err
		}
		s.RecordMeta = json.RawMessage(recordMeta)
		s.Value = json.RawMessage(value)
		s.Ancestors = json.RawMessage(ancestors)
		res = append(res, &s)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return res, nil
}

// Create inserts a new setting record.
func (r *Repo) Create(ctx context.Context, s *entity.Setting) error {
	_, err := r.db.ExecContext(ctx, `INSERT INTO settings (id, parent_id, root_id, record_meta, category, key, value, value_type, sort_order, version, status, ancestors, created_at, updated_at) VALUES ($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12,$13,$14)`, s.ID, s.ParentID, s.RootID, s.RecordMeta, s.Category, s.Key, s.Value, s.ValueType, s.SortOrder, s.Version, s.Status, s.Ancestors, s.CreatedAt, s.UpdatedAt)
	return err
}

// Update updates an existing setting using optimistic locking on version.
// It returns sql.ErrNoRows if the update did not affect any row (not found or version mismatch).
func (r *Repo) Update(ctx context.Context, s *entity.Setting, expectedVersion int64) (int64, error) {
	newVersion := s.Version
	res, err := r.db.ExecContext(ctx, `UPDATE settings SET parent_id=$1, root_id=$2, record_meta=$3, category=$4, key=$5, value=$6, value_type=$7, sort_order=$8, version=$9, status=$10, ancestors=$11, updated_at=$12 WHERE id=$13 AND version=$14`, s.ParentID, s.RootID, s.RecordMeta, s.Category, s.Key, s.Value, s.ValueType, s.SortOrder, newVersion, s.Status, s.Ancestors, s.UpdatedAt, s.ID, expectedVersion)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// Delete removes a setting by id.
func (r *Repo) Delete(ctx context.Context, id string) (int64, error) {
	res, err := r.db.ExecContext(ctx, `DELETE FROM settings WHERE id = $1`, id)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}
