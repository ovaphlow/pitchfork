package opinion

import (
	"errors"
	"fmt"
	"time"
)

// ErrMediaQuestionNotFound is returned when the run exists but the
// requested opinion media question does not (or no longer) exist. It
// maps to HTTP 404 in the routing layer.
var ErrMediaQuestionNotFound = errors.New("opinion media question not found")

// QuestionType is the question kind (问题类型) of an opinion media
// question: how pointed the journalist's question is at the simulated
// press conference (模拟新闻发布会).
type QuestionType string

const (
	QuestionTypeFactual     QuestionType = "事实类"
	QuestionTypeChallenging QuestionType = "质疑类"
	QuestionTypeSharp       QuestionType = "尖锐类"
)

// DefaultQuestionType is applied when a request omits the question_type
// field.
const DefaultQuestionType = QuestionTypeFactual

var validQuestionTypes = []QuestionType{QuestionTypeFactual, QuestionTypeChallenging, QuestionTypeSharp}

// Valid reports whether questionType is one of the allowed question
// type values.
func (questionType QuestionType) Valid() bool {
	for _, candidate := range validQuestionTypes {
		if questionType == candidate {
			return true
		}
	}
	return false
}

// AnswerStatus is the answering state (回答状态) of an opinion media
// question. The state machine is enforced by the service: a question
// starts 未回答 and only moves forward to 已回答 (same-value transitions
// are legal no-ops, the backward step 已回答 -> 未回答 is rejected with
// 400), mirroring the trainee answering the journalist's question at
// the press conference. answered_at is set by the service when the
// question is answered.
type AnswerStatus string

const (
	AnswerStatusPending  AnswerStatus = "未回答"
	AnswerStatusAnswered AnswerStatus = "已回答"
)

// DefaultAnswerStatus is applied when a request omits the status field.
const DefaultAnswerStatus = AnswerStatusPending

var validAnswerStatuses = []AnswerStatus{AnswerStatusPending, AnswerStatusAnswered}

// Valid reports whether status is one of the allowed answer status
// values.
func (status AnswerStatus) Valid() bool {
	for _, candidate := range validAnswerStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// MediaQuestion is one media Q&A record (媒体问答记录) of a drill run:
// the simulated press-conference question and the trainee's answer
// during the 「媒体沟通」 training phase. media_name / question describe
// the asked question (both required); reporter is the journalist name;
// question_type is the question kind; answer is the trainee's reply
// ('' until answered); the status state machine (未回答 -> 已回答,
// one-way) records whether the trainee has answered, with answered_at
// set by the service at the transition. metadata follows the repository
// extension-field convention; created_by passes through (the prototype
// has no auth context); created_at / updated_at are maintained by the
// service.
type MediaQuestion struct {
	ID           string         `json:"id"`
	RunID        string         `json:"run_id"`
	MediaName    string         `json:"media_name"`
	Reporter     string         `json:"reporter"`
	Question     string         `json:"question"`
	QuestionType QuestionType   `json:"question_type"`
	Answer       string         `json:"answer"`
	Status       AnswerStatus   `json:"status"`
	AnsweredAt   *time.Time     `json:"answered_at"`
	Metadata     map[string]any `json:"metadata"`
	CreatedBy    string         `json:"created_by"`
	CreatedAt    time.Time      `json:"created_at"`
	UpdatedAt    time.Time      `json:"updated_at"`
}

// MediaQuestionInput carries the client-supplied fields of an opinion
// media question creation. run_id and id are never part of the input:
// they are decided by the route path and the service. media_name and
// question are required; reporter defaults to '' when empty;
// question_type defaults to 事实类 when empty and must be one of the
// allowed values; answer defaults to '' when empty; status defaults to
// 未回答 and a new question only accepts 未回答 (the state machine
// governs later PUTs); metadata defaults to an empty object; created_by
// passes through (the prototype has no auth context).
type MediaQuestionInput struct {
	MediaName    string
	Reporter     string
	Question     string
	QuestionType QuestionType
	Answer       string
	Status       AnswerStatus
	Metadata     map[string]any
	CreatedBy    string
}

// MediaQuestionUpdate carries the client-supplied fields of an opinion
// media question update (partial update semantics: an omitted field
// keeps its current value). media_name and question are required on
// both entries (an empty value is a ValidationError); reporter, answer
// and created_by pass through when non-empty (answer is editable at any
// time); empty enum fields mean "keep the current value"; HasMetadata
// tells an explicitly provided field from an omitted one ({} is a legal
// metadata value, so it cannot be told apart from omission otherwise).
// The service validates the enum values and enforces the answer state
// machine; answered_at is managed by the service (set at the
// 未回答 -> 已回答 transition, preserved otherwise).
type MediaQuestionUpdate struct {
	MediaName    string
	Reporter     string
	Question     string
	QuestionType QuestionType
	Answer       string
	Status       AnswerStatus
	Metadata     map[string]any
	HasMetadata  bool
	CreatedBy    string
}

// MediaQuestionFilter selects opinion media questions for listing. Empty
// enum values match everything; Limit and Offset paginate the matching
// set.
type MediaQuestionFilter struct {
	QuestionType QuestionType
	Status       AnswerStatus
	Limit        int
	Offset       int
}

// normalizeMediaQuestion validates client input and produces a complete
// media question. media_name and question are required; reporter
// defaults to '' when empty; question_type defaults to 事实类 and must
// be one of the allowed values; answer defaults to '' when empty;
// status defaults to 未回答 and must be one of the allowed values; a
// create additionally only accepts 未回答 (an explicit 已回答 is a
// ValidationError — the state machine governs the later PUTs in the
// service). metadata nil becomes an empty object; answered_at is always
// nil at creation. The run and the timestamps come from the caller.
func normalizeMediaQuestion(runID string, input MediaQuestionInput, now time.Time, id string) (MediaQuestion, error) {
	if input.MediaName == "" {
		return MediaQuestion{}, &ValidationError{Message: "media_name required"}
	}
	if input.Question == "" {
		return MediaQuestion{}, &ValidationError{Message: "question required"}
	}
	questionType := input.QuestionType
	if questionType == "" {
		questionType = DefaultQuestionType
	}
	if !questionType.Valid() {
		return MediaQuestion{}, &ValidationError{Message: fmt.Sprintf("invalid question_type: %q", input.QuestionType)}
	}
	status := input.Status
	if status == "" {
		status = DefaultAnswerStatus
	}
	if !status.Valid() {
		return MediaQuestion{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	if status != DefaultAnswerStatus {
		return MediaQuestion{}, &ValidationError{
			Message: fmt.Sprintf("invalid status for a new opinion media question: %q (only %s is accepted)", input.Status, DefaultAnswerStatus),
		}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return MediaQuestion{
		ID:           id,
		RunID:        runID,
		MediaName:    input.MediaName,
		Reporter:     input.Reporter,
		Question:     input.Question,
		QuestionType: questionType,
		Answer:       input.Answer,
		Status:       status,
		AnsweredAt:   nil,
		Metadata:     metadata,
		CreatedBy:    input.CreatedBy,
		CreatedAt:    now,
		UpdatedAt:    now,
	}, nil
}
