package opinion

import (
	"errors"
	"fmt"
	"time"
)

// ErrComplaintNotFound is returned when the run exists but the
// requested opinion complaint does not (or no longer) exist. It maps to
// HTTP 404 in the routing layer.
var ErrComplaintNotFound = errors.New("opinion complaint not found")

// ComplaintChannel is the complaint channel (投诉渠道) of an opinion
// complaint: how the visitor submitted the complaint about the museum
// visit.
type ComplaintChannel string

const (
	ComplaintChannelOnSite   ComplaintChannel = "现场"
	ComplaintChannelPhone    ComplaintChannel = "电话"
	ComplaintChannelOnline   ComplaintChannel = "网络留言"
	ComplaintChannelTransfer ComplaintChannel = "12345转办"
	ComplaintChannelOther    ComplaintChannel = "其他"
)

// DefaultComplaintChannel is applied when a request omits the channel
// field.
const DefaultComplaintChannel = ComplaintChannelOnSite

var validComplaintChannels = []ComplaintChannel{
	ComplaintChannelOnSite,
	ComplaintChannelPhone,
	ComplaintChannelOnline,
	ComplaintChannelTransfer,
	ComplaintChannelOther,
}

// Valid reports whether channel is one of the allowed complaint channel
// values.
func (channel ComplaintChannel) Valid() bool {
	for _, candidate := range validComplaintChannels {
		if channel == candidate {
			return true
		}
	}
	return false
}

// ComplaintType is the complaint kind (投诉类型) of an opinion
// complaint. The card scenarios 入馆受阻 / 参观受限 carry the training
// focus; 服务态度 / 设施问题 / 其他 are the ancillary kinds.
type ComplaintType string

const (
	ComplaintTypeEntryBlocked ComplaintType = "入馆受阻"
	ComplaintTypeVisitLimited ComplaintType = "参观受限"
	ComplaintTypeService      ComplaintType = "服务态度"
	ComplaintTypeFacility     ComplaintType = "设施问题"
	ComplaintTypeOther        ComplaintType = "其他"
)

// DefaultComplaintType is applied when a request omits the
// complaint_type field.
const DefaultComplaintType = ComplaintTypeEntryBlocked

var validComplaintTypes = []ComplaintType{
	ComplaintTypeEntryBlocked,
	ComplaintTypeVisitLimited,
	ComplaintTypeService,
	ComplaintTypeFacility,
	ComplaintTypeOther,
}

// Valid reports whether complaintType is one of the allowed complaint
// type values.
func (complaintType ComplaintType) Valid() bool {
	for _, candidate := range validComplaintTypes {
		if complaintType == candidate {
			return true
		}
	}
	return false
}

// ComplaintStatus is the complaint handling state (投诉状态) of an
// opinion complaint. The state machine is enforced by the service: a
// new complaint starts in 待受理 and only moves through the adjacent
// steps 待受理 -> 处理中 -> 已办结 (same-value transitions are legal
// no-ops, skips and backward steps are rejected with 400), mirroring
// the complaint-handling flow of the 「投诉处理」 training phase.
// closed_at is set by the service at the 已办结 step and stays null on
// every other step.
type ComplaintStatus string

const (
	ComplaintStatusPending    ComplaintStatus = "待受理"
	ComplaintStatusProcessing ComplaintStatus = "处理中"
	ComplaintStatusClosed     ComplaintStatus = "已办结"
)

// DefaultComplaintStatus is applied when a request omits the status
// field.
const DefaultComplaintStatus = ComplaintStatusPending

var validComplaintStatuses = []ComplaintStatus{
	ComplaintStatusPending,
	ComplaintStatusProcessing,
	ComplaintStatusClosed,
}

// Valid reports whether status is one of the allowed complaint status
// values.
func (status ComplaintStatus) Valid() bool {
	for _, candidate := range validComplaintStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// legalComplaintStatusTransition reports whether moving from one status
// to another follows the state machine: staying on the same status is a
// legal no-op, advancing one adjacent step (待受理 -> 处理中 -> 已办结) is
// legal, every skip or backward step is illegal.
func legalComplaintStatusTransition(from, to ComplaintStatus) bool {
	if from == to {
		return true
	}
	return from == ComplaintStatusPending && to == ComplaintStatusProcessing ||
		from == ComplaintStatusProcessing && to == ComplaintStatusClosed
}

// Complaint is one complaint ticket (投诉处理记录) of a drill run: the
// visitor complaint about the museum visit during the 「投诉处理」
// training phase. complainant is the complaining visitor (required);
// channel is the complaint channel; complaint_type focuses on the card
// scenarios (入馆受阻 / 参观受限); content is the complaint body
// (required); the status state machine (待受理 -> 处理中 -> 已办结,
// forward-only) records the handling progress, with closed_at set by
// the service at the 已办结 step; handling (安抚疏导措施) and handler
// (处理人) keep the soothing-guidance trace. metadata follows the
// repository extension-field convention; created_by passes through (the
// prototype has no auth context); created_at / updated_at are
// maintained by the service.
type Complaint struct {
	ID            string           `json:"id"`
	RunID         string           `json:"run_id"`
	Complainant   string           `json:"complainant"`
	Channel       ComplaintChannel `json:"channel"`
	ComplaintType ComplaintType    `json:"complaint_type"`
	Content       string           `json:"content"`
	Status        ComplaintStatus  `json:"status"`
	Handling      string           `json:"handling"`
	Handler       string           `json:"handler"`
	ClosedAt      *time.Time       `json:"closed_at"`
	Metadata      map[string]any   `json:"metadata"`
	CreatedBy     string           `json:"created_by"`
	CreatedAt     time.Time        `json:"created_at"`
	UpdatedAt     time.Time        `json:"updated_at"`
}

// ComplaintInput carries the client-supplied fields of an opinion
// complaint creation. run_id and id are never part of the input: they
// are decided by the route path and the service. complainant and
// content are required; channel defaults to 现场 when empty and must be
// one of the allowed values; complaint_type defaults to 入馆受阻 when
// empty and must be one of the allowed values; status defaults to
// 待受理 and a new complaint only accepts 待受理 (the state machine
// governs later PUTs); handling / handler default to ” when empty;
// metadata defaults to an empty object; created_by passes through (the
// prototype has no auth context).
type ComplaintInput struct {
	Complainant   string
	Channel       ComplaintChannel
	ComplaintType ComplaintType
	Content       string
	Status        ComplaintStatus
	Handling      string
	Handler       string
	Metadata      map[string]any
	CreatedBy     string
}

// ComplaintUpdate carries the client-supplied fields of an opinion
// complaint update (partial update semantics: an omitted field keeps
// its current value, except complainant and content which are required
// on both entries and must stay non-empty). Empty enum/string fields
// mean "keep the current value"; HasMetadata tells an explicitly
// provided field from an omitted one ({} is a legal metadata value, so
// it cannot be told apart from omission otherwise). The service
// validates the enum values and enforces the handling state machine;
// closed_at is managed by the service (set at the transition into
// 已办结, reset to null on the other steps, preserved when the status is
// untouched — including a 已办结 no-op and PUTs that only touch
// business fields like handling / handler / content).
type ComplaintUpdate struct {
	Complainant   string
	Channel       ComplaintChannel
	ComplaintType ComplaintType
	Content       string
	Status        ComplaintStatus
	Handling      string
	Handler       string
	Metadata      map[string]any
	HasMetadata   bool
	CreatedBy     string
}

// ComplaintFilter selects opinion complaints for listing. Empty enum
// values match everything; Limit and Offset paginate the matching set.
type ComplaintFilter struct {
	Channel       ComplaintChannel
	ComplaintType ComplaintType
	Status        ComplaintStatus
	Limit         int
	Offset        int
}

// normalizeComplaint validates client input and produces a complete
// complaint. complainant and content are required; channel defaults to
// 现场 and must be one of the allowed values; complaint_type defaults
// to 入馆受阻 and must be one of the allowed values; status defaults to
// 待受理 and must be one of the allowed values; a create additionally
// only accepts 待受理 (an explicit 处理中/已办结 is a ValidationError —
// the state machine governs the later PUTs in the service). handling /
// handler pass through (empty stays ”); metadata nil becomes an empty
// object; closed_at is always nil at creation. The run and the
// timestamps come from the caller.
func normalizeComplaint(runID string, input ComplaintInput, now time.Time, id string) (Complaint, error) {
	if input.Complainant == "" {
		return Complaint{}, &ValidationError{Message: "complainant required"}
	}
	if input.Content == "" {
		return Complaint{}, &ValidationError{Message: "content required"}
	}
	channel := input.Channel
	if channel == "" {
		channel = DefaultComplaintChannel
	}
	if !channel.Valid() {
		return Complaint{}, &ValidationError{Message: fmt.Sprintf("invalid channel: %q", input.Channel)}
	}
	complaintType := input.ComplaintType
	if complaintType == "" {
		complaintType = DefaultComplaintType
	}
	if !complaintType.Valid() {
		return Complaint{}, &ValidationError{Message: fmt.Sprintf("invalid complaint_type: %q", input.ComplaintType)}
	}
	status := input.Status
	if status == "" {
		status = DefaultComplaintStatus
	}
	if !status.Valid() {
		return Complaint{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	if status != DefaultComplaintStatus {
		return Complaint{}, &ValidationError{
			Message: fmt.Sprintf("invalid status for a new opinion complaint: %q (only %s is accepted)", input.Status, DefaultComplaintStatus),
		}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Complaint{
		ID:            id,
		RunID:         runID,
		Complainant:   input.Complainant,
		Channel:       channel,
		ComplaintType: complaintType,
		Content:       input.Content,
		Status:        status,
		Handling:      input.Handling,
		Handler:       input.Handler,
		ClosedAt:      nil,
		Metadata:      metadata,
		CreatedBy:     input.CreatedBy,
		CreatedAt:     now,
		UpdatedAt:     now,
	}, nil
}
