package entity

import "encoding/json"

// Setting represents a configuration or reserved data record.
type Setting struct {
	ID        string          `json:"id"`
	DataState json.RawMessage `json:"data_state,omitempty"`
	Category  string          `json:"category,omitempty"`
	Detail    json.RawMessage `json:"detail,omitempty"`
}

// NewSetting creates a new Setting with the provided fields. DataState and Detail
// may be provided as JSON bytes (json.RawMessage) or nil.
func NewSetting(id string, category string, dataState json.RawMessage, detail json.RawMessage) *Setting {
	return &Setting{ID: id, Category: category, DataState: dataState, Detail: detail}
}

// FromMap helps create a Setting from generic maps by marshaling them to JSON.
func FromMap(id string, category string, dataState any, detail any) (*Setting, error) {
	var ds json.RawMessage
	var dt json.RawMessage
	var err error
	if dataState != nil {
		ds, err = json.Marshal(dataState)
		if err != nil {
			return nil, err
		}
	}
	if detail != nil {
		dt, err = json.Marshal(detail)
		if err != nil {
			return nil, err
		}
	}
	return &Setting{ID: id, Category: category, DataState: ds, Detail: dt}, nil
}
