package entity

import "encoding/json"

// Setting represents a configuration or reserved data record.
type Setting struct {
	ID         string          `json:"id"`
	ParentID   string          `json:"parent_id,omitempty"`
	RootID     string          `json:"root_id,omitempty"`
	RecordMeta json.RawMessage `json:"record_meta,omitempty"`
	Category   string          `json:"category,omitempty"`
	Metadata   json.RawMessage `json:"metadata,omitempty"`
}

// NewSettingWithAncestry creates a new Setting
// for representing multi-level hierarchies.
func NewSetting(id string, parentID string, rootID string, category string, recordMeta json.RawMessage, metadata json.RawMessage) *Setting {
	return &Setting{ID: id, ParentID: parentID, RootID: rootID, Category: category, RecordMeta: recordMeta, Metadata: metadata}
}
