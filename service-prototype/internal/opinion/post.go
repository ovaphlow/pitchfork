package opinion

import (
	"errors"
	"fmt"
	"time"
)

// ErrPostNotFound is returned when the run exists but the requested
// opinion post does not (or no longer) exist. It maps to HTTP 404 in
// the routing layer.
var ErrPostNotFound = errors.New("opinion post not found")

// Source is the origin platform (来源平台) of an opinion post: the
// simulated public-opinion feed mixes posts from the main Chinese
// social/news platforms.
type Source string

const (
	SourceWeibo Source = "微博"
	SourceDouyin Source = "抖音"
	SourceNews  Source = "新闻媒体"
	SourceForum Source = "论坛"
	SourceOther Source = "其他"
)

// DefaultSource is applied when a request omits the source field.
const DefaultSource = SourceWeibo

var validSources = []Source{SourceWeibo, SourceDouyin, SourceNews, SourceForum, SourceOther}

// Valid reports whether source is one of the allowed source values.
func (source Source) Valid() bool {
	for _, candidate := range validSources {
		if source == candidate {
			return true
		}
	}
	return false
}

// Sentiment is the emotional tendency (情感倾向) of an opinion post.
type Sentiment string

const (
	SentimentNegative Sentiment = "负面"
	SentimentNeutral  Sentiment = "中性"
	SentimentPositive Sentiment = "正面"
)

// DefaultSentiment is applied when a request omits the sentiment field.
const DefaultSentiment = SentimentNegative

var validSentiments = []Sentiment{SentimentNegative, SentimentNeutral, SentimentPositive}

// Valid reports whether sentiment is one of the allowed sentiment
// values.
func (sentiment Sentiment) Valid() bool {
	for _, candidate := range validSentiments {
		if sentiment == candidate {
			return true
		}
	}
	return false
}

// WarnStatus is the warning state (预警状态) of an opinion post. The
// state machine is enforced by the service: a post starts 未预警 and
// only moves forward to 已预警 (same-value transitions are legal
// no-ops, the backward step 已预警 -> 未预警 is rejected with 400),
// mirroring the trainee executing a warning (预警) on a negative or
// hot post in the monitoring flow. warned_at is set by the service
// when the post is warned.
type WarnStatus string

const (
	WarnStatusPending WarnStatus = "未预警"
	WarnStatusWarned  WarnStatus = "已预警"
)

// DefaultWarnStatus is applied when a request omits the warn_status
// field.
const DefaultWarnStatus = WarnStatusPending

var validWarnStatuses = []WarnStatus{WarnStatusPending, WarnStatusWarned}

// Valid reports whether status is one of the allowed warn status
// values.
func (status WarnStatus) Valid() bool {
	for _, candidate := range validWarnStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// Post is one opinion post (舆情信息) of a drill run: the simulated
// public-opinion feed the trainee monitors during the 「舆情监测与预警」
// training phase. source / sentiment / heat describe the post; the
// warn_status state machine (未预警 -> 已预警, one-way) records whether
// the trainee has executed a warning, with warned_at set by the service
// at the transition. metadata follows the repository extension-field
// convention; created_by passes through (the prototype has no auth
// context); created_at / updated_at are maintained by the service.
type Post struct {
	ID         string         `json:"id"`
	RunID      string         `json:"run_id"`
	Source     Source         `json:"source"`
	Content    string         `json:"content"`
	Sentiment  Sentiment      `json:"sentiment"`
	Heat       int            `json:"heat"`
	WarnStatus WarnStatus     `json:"warn_status"`
	WarnedAt   *time.Time     `json:"warned_at"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
	CreatedAt  time.Time      `json:"created_at"`
	UpdatedAt  time.Time      `json:"updated_at"`
}

// PostInput carries the client-supplied fields of an opinion post
// creation. run_id and id are never part of the input: they are decided
// by the route path and the service. content is required; source
// defaults to 微博 when empty and must be one of the allowed values;
// sentiment defaults to 负面 when empty and must be one of the allowed
// values; heat defaults to 0 and must be between 0 and 100; warn_status
// defaults to 未预警 and a new post only accepts 未预警 (the state
// machine governs later PUTs); metadata defaults to an empty object;
// created_by passes through (the prototype has no auth context).
type PostInput struct {
	Content    string
	Source     Source
	Sentiment  Sentiment
	Heat       int
	WarnStatus WarnStatus
	Metadata   map[string]any
	CreatedBy  string
}

// PostUpdate carries the client-supplied fields of an opinion post
// update (partial update semantics: an omitted field keeps its current
// value). Empty enum/string fields mean "keep the current value";
// HasHeat and HasMetadata tell an explicitly provided field from an
// omitted one (0 is a legal heat value and {} a legal metadata value,
// so they cannot be told apart from omission otherwise). The service
// validates the enum values and the heat range and enforces the warn
// state machine; warned_at is managed by the service (set at the
// 未预警 -> 已预警 transition, preserved otherwise).
type PostUpdate struct {
	Content     string
	Source      Source
	Sentiment   Sentiment
	Heat        int
	HasHeat     bool
	WarnStatus  WarnStatus
	Metadata    map[string]any
	HasMetadata bool
	CreatedBy   string
}

// PostFilter selects opinion posts for listing. Empty enum values match
// everything; Limit and Offset paginate the matching set.
type PostFilter struct {
	Source     Source
	Sentiment  Sentiment
	WarnStatus WarnStatus
	Limit      int
	Offset     int
}

// normalizePost validates client input and produces a complete post.
// content is required; source defaults to 微博 and must be one of the
// allowed values; sentiment defaults to 负面 and must be one of the
// allowed values; heat must be between 0 and 100; warn_status defaults
// to 未预警 and must be one of the allowed values; a create additionally
// only accepts 未预警 (an explicit 已预警 is a ValidationError — the
// state machine governs the later PUTs in the service). metadata nil
// becomes an empty object; warned_at is always nil at creation. The run
// and the timestamps come from the caller.
func normalizePost(runID string, input PostInput, now time.Time, id string) (Post, error) {
	if input.Content == "" {
		return Post{}, &ValidationError{Message: "content required"}
	}
	source := input.Source
	if source == "" {
		source = DefaultSource
	}
	if !source.Valid() {
		return Post{}, &ValidationError{Message: fmt.Sprintf("invalid source: %q", input.Source)}
	}
	sentiment := input.Sentiment
	if sentiment == "" {
		sentiment = DefaultSentiment
	}
	if !sentiment.Valid() {
		return Post{}, &ValidationError{Message: fmt.Sprintf("invalid sentiment: %q", input.Sentiment)}
	}
	if input.Heat < 0 || input.Heat > 100 {
		return Post{}, &ValidationError{Message: fmt.Sprintf("invalid heat: %d (must be between 0 and 100)", input.Heat)}
	}
	status := input.WarnStatus
	if status == "" {
		status = DefaultWarnStatus
	}
	if !status.Valid() {
		return Post{}, &ValidationError{Message: fmt.Sprintf("invalid warn_status: %q", input.WarnStatus)}
	}
	if status != DefaultWarnStatus {
		return Post{}, &ValidationError{
			Message: fmt.Sprintf("invalid warn_status for a new opinion post: %q (only %s is accepted)", input.WarnStatus, DefaultWarnStatus),
		}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Post{
		ID:         id,
		RunID:      runID,
		Source:     source,
		Content:    input.Content,
		Sentiment:  sentiment,
		Heat:       input.Heat,
		WarnStatus: status,
		WarnedAt:   nil,
		Metadata:   metadata,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}
