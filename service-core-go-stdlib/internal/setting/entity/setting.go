package entity

import (
	"encoding/json"
	"time"
)

// Setting represents a configuration or reserved data record.
type Setting struct {
	ID         string          `json:"id" db:"id"`
	ParentID   string          `json:"parent_id,omitempty" db:"parent_id"`
	RootID     string          `json:"root_id,omitempty" db:"root_id"`
	RecordMeta json.RawMessage `json:"record_meta,omitempty" db:"record_meta"`
	Category   string          `json:"category,omitempty" db:"category"`     // logical grouping
	Key        string          `json:"key,omitempty" db:"key"`               // machine key / identifier
	Value      json.RawMessage `json:"value,omitempty" db:"value"`           // flexible typed value
	ValueType  string          `json:"value_type,omitempty" db:"value_type"` // hint: json/string/number/bool
	SortOrder  int             `json:"sort_order,omitempty" db:"sort_order"`
	Version    int64           `json:"version,omitempty" db:"version"`
	Status     string          `json:"status,omitempty" db:"status"`       // e.g. active / disabled / archived
	Ancestors  json.RawMessage `json:"ancestors,omitempty" db:"ancestors"` // JSON array of ancestor IDs, e.g. ["id1","id2"]
	CreatedAt  time.Time       `json:"created_at" db:"created_at"`
	UpdatedAt  time.Time       `json:"updated_at" db:"updated_at"`
}

// NewSetting creates a new Setting for representing multi-level hierarchies.
// This constructor keeps the previous signature for compatibility and fills
// reasonable defaults for the newly added fields.
func NewSetting(id string, parentID string, rootID string, category string, recordMeta json.RawMessage, metadata json.RawMessage) *Setting {
	now := time.Now().UTC()

	return &Setting{
		ID:         id,
		ParentID:   parentID,
		RootID:     rootID,
		Category:   category,
		RecordMeta: recordMeta,
		Key:        id,
		Value:      nil,
		ValueType:  "json",
		Version:    1,
		Status:     "active",
		Ancestors:  json.RawMessage("[]"),
		CreatedAt:  now,
		UpdatedAt:  now,
	}
}

// TableName returns the database table name for the Setting entity.
// This is useful for ORMs or helper libraries that look for a TableName method.
func (Setting) TableName() string {
	return "settings"
}
