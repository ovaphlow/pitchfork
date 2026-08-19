package opinion

import (
	"errors"
	"fmt"
	"time"
)

// ErrReleaseNotFound is returned when the run exists but the requested
// opinion release does not (or no longer) exist. It maps to HTTP 404 in
// the routing layer.
var ErrReleaseNotFound = errors.New("opinion release not found")

// Channel is the release channel (发布渠道) of an opinion release: the
// official channels (官方渠道: 官网公告 / 微信公众号 / 微博官方号) or the
// mainstream media press release (主流媒体: 新闻媒体通稿, in which case
// media_name carries the media name).
type Channel string

const (
	ChannelOfficialWebsite Channel = "官网公告"
	ChannelWechat          Channel = "微信公众号"
	ChannelWeiboOfficial   Channel = "微博官方号"
	ChannelNewsRelease     Channel = "新闻媒体通稿"
)

// DefaultChannel is applied when a request omits the channel field.
const DefaultChannel = ChannelOfficialWebsite

var validChannels = []Channel{ChannelOfficialWebsite, ChannelWechat, ChannelWeiboOfficial, ChannelNewsRelease}

// Valid reports whether channel is one of the allowed channel values.
func (channel Channel) Valid() bool {
	for _, candidate := range validChannels {
		if channel == candidate {
			return true
		}
	}
	return false
}

// ReleaseStatus is the publication state (发布状态) of an opinion
// release. The state machine is enforced by the service: a new release
// starts in 草稿 and only moves through the adjacent steps 草稿 -> 待审核
// -> 已发布 -> 已撤回 (same-value transitions are legal no-ops, skips
// and backward steps are rejected with 400), mirroring the trainee
// publishing a situation statement through the official channels and
// the mainstream media in the 「信息发布」 training phase. published_at
// is set by the service at the 已发布 step and reset to null on every
// other step (已撤回 clears it again).
type ReleaseStatus string

const (
	ReleaseStatusDraft     ReleaseStatus = "草稿"
	ReleaseStatusPending   ReleaseStatus = "待审核"
	ReleaseStatusPublished ReleaseStatus = "已发布"
	ReleaseStatusWithdrawn ReleaseStatus = "已撤回"
)

// DefaultReleaseStatus is applied when a request omits the status field.
const DefaultReleaseStatus = ReleaseStatusDraft

var validReleaseStatuses = []ReleaseStatus{ReleaseStatusDraft, ReleaseStatusPending, ReleaseStatusPublished, ReleaseStatusWithdrawn}

// Valid reports whether status is one of the allowed release status
// values.
func (status ReleaseStatus) Valid() bool {
	for _, candidate := range validReleaseStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// legalReleaseStatusTransition reports whether moving from one status to
// another follows the state machine: staying on the same status is a
// legal no-op, advancing one adjacent step (草稿 -> 待审核 -> 已发布 ->
// 已撤回) is legal, every skip or backward step is illegal.
func legalReleaseStatusTransition(from, to ReleaseStatus) bool {
	if from == to {
		return true
	}
	return from == ReleaseStatusDraft && to == ReleaseStatusPending ||
		from == ReleaseStatusPending && to == ReleaseStatusPublished ||
		from == ReleaseStatusPublished && to == ReleaseStatusWithdrawn
}

// Release is one opinion release (信息发布记录) of a drill run: the
// situation-statement publication record (情况说明发布记录) of the
// 「信息发布」 training phase. channel / media_name describe the release
// channel (media_name is the media name of a 新闻媒体通稿, '' otherwise);
// title / content are the headline and body (both required); the
// status state machine (草稿 -> 待审核 -> 已发布 -> 已撤回, forward-only)
// records the publication progress, with published_at set by the
// service at the 已发布 step. metadata follows the repository
// extension-field convention; created_by passes through (the prototype
// has no auth context); created_at / updated_at are maintained by the
// service.
type Release struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	Channel     Channel        `json:"channel"`
	Title       string         `json:"title"`
	Content     string         `json:"content"`
	MediaName   string         `json:"media_name"`
	Status      ReleaseStatus  `json:"status"`
	PublishedAt *time.Time     `json:"published_at"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

// ReleaseInput carries the client-supplied fields of an opinion release
// creation. run_id and id are never part of the input: they are decided
// by the route path and the service. title and content are required;
// channel defaults to 官网公告 when empty and must be one of the allowed
// values; media_name passes through (defaults to '' and is not coupled
// to the channel value); status defaults to 草稿 and a new release only
// accepts 草稿 (the state machine governs later PUTs); metadata defaults
// to an empty object; created_by passes through (the prototype has no
// auth context).
type ReleaseInput struct {
	Channel   Channel
	Title     string
	Content   string
	MediaName string
	Status    ReleaseStatus
	Metadata  map[string]any
	CreatedBy string
}

// ReleaseUpdate carries the client-supplied fields of an opinion release
// update (partial update semantics: an omitted field keeps its current
// value, except title and content which are required on both entries and
// must stay non-empty). Empty enum/string fields mean "keep the current
// value"; HasMetadata tells an explicitly provided field from an omitted
// one ({} is a legal metadata value, so it cannot be told apart from
// omission otherwise). The service validates the enum values and enforces
// the publication state machine; published_at is managed by the service
// (set at the transition into 已发布, reset to null on the other steps,
// preserved when the status is untouched).
type ReleaseUpdate struct {
	Channel     Channel
	Title       string
	Content     string
	MediaName   string
	Status      ReleaseStatus
	Metadata    map[string]any
	HasMetadata bool
	CreatedBy   string
}

// ReleaseFilter selects opinion releases for listing. Empty enum values
// match everything; Limit and Offset paginate the matching set.
type ReleaseFilter struct {
	Channel Channel
	Status  ReleaseStatus
	Limit   int
	Offset  int
}

// normalizeRelease validates client input and produces a complete
// release. title and content are required; channel defaults to 官网公告
// and must be one of the allowed values; media_name passes through
// (empty stays ''); status defaults to 草稿 and must be one of the
// allowed values; a create additionally only accepts 草稿 (an explicit
// 待审核/已发布/已撤回 is a ValidationError — the state machine governs
// the later PUTs in the service). metadata nil becomes an empty object;
// published_at is always nil at creation. The run and the timestamps
// come from the caller.
func normalizeRelease(runID string, input ReleaseInput, now time.Time, id string) (Release, error) {
	if input.Title == "" {
		return Release{}, &ValidationError{Message: "title required"}
	}
	if input.Content == "" {
		return Release{}, &ValidationError{Message: "content required"}
	}
	channel := input.Channel
	if channel == "" {
		channel = DefaultChannel
	}
	if !channel.Valid() {
		return Release{}, &ValidationError{Message: fmt.Sprintf("invalid channel: %q", input.Channel)}
	}
	status := input.Status
	if status == "" {
		status = DefaultReleaseStatus
	}
	if !status.Valid() {
		return Release{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	if status != DefaultReleaseStatus {
		return Release{}, &ValidationError{
			Message: fmt.Sprintf("invalid status for a new opinion release: %q (only %s is accepted)", input.Status, DefaultReleaseStatus),
		}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Release{
		ID:          id,
		RunID:       runID,
		Channel:     channel,
		Title:       input.Title,
		Content:     input.Content,
		MediaName:   input.MediaName,
		Status:      status,
		PublishedAt: nil,
		Metadata:    metadata,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}
