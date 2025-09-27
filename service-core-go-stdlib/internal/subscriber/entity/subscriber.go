package entity

type Subscriber struct {
	ID         int64  `json:"id"`
	RecordMeta string `json:"record_meta,omitempty"`
	Email      string `json:"email"`
	Phone      string `json:"phone,omitempty"`
	Metadata   string `json:"metadata,omitempty"`
}
