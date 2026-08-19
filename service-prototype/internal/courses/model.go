// Package courses implements the training-course business object of
// prototyped: the model with its enum validation, a store interface with
// an in-memory implementation, and the service layer. The package never
// touches a database; a PostgreSQL-backed store can be swapped in later
// behind the same interface.
package courses

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrNotFound is returned by the store and service when a course id does
// not exist. It maps to HTTP 404 in the routing layer.
var ErrNotFound = errors.New("course not found")

// ValidationError describes a request that violates the courses business
// rules (missing required fields or invalid enum values). It maps to
// HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// Topic is the training topic (专题) of a course.
type Topic string

const (
	TopicPassengerFlow   Topic = "客流评估与引导"
	TopicInfrastructure  Topic = "基础设施保障"
	TopicNaturalDisaster Topic = "自然灾害防范"
	TopicSafetyResponse  Topic = "安全应急处置"
	TopicPublicOpinion   Topic = "舆情应对"
)

var validTopics = []Topic{
	TopicPassengerFlow,
	TopicInfrastructure,
	TopicNaturalDisaster,
	TopicSafetyResponse,
	TopicPublicOpinion,
}

// Valid reports whether topic is one of the allowed topic values.
func (topic Topic) Valid() bool {
	for _, candidate := range validTopics {
		if topic == candidate {
			return true
		}
	}
	return false
}

// DeliveryType is the delivery mode (type) of a course.
type DeliveryType string

const (
	DeliveryOnline  DeliveryType = "线上授课"
	DeliveryOffline DeliveryType = "线下授课"
)

var validDeliveryTypes = []DeliveryType{DeliveryOnline, DeliveryOffline}

// Valid reports whether deliveryType is one of the allowed type values.
func (deliveryType DeliveryType) Valid() bool {
	for _, candidate := range validDeliveryTypes {
		if deliveryType == candidate {
			return true
		}
	}
	return false
}

// Status is the lifecycle state of a course.
type Status string

const (
	StatusEnabled  Status = "启用"
	StatusDisabled Status = "停用"
)

// DefaultStatus is applied when a request omits the status field.
const DefaultStatus = StatusEnabled

var validStatuses = []Status{StatusEnabled, StatusDisabled}

// Valid reports whether status is one of the allowed status values.
func (status Status) Valid() bool {
	for _, candidate := range validStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// Course is a training course as exposed by the API. Metadata follows the
// repository JSONB extension-field convention and is always present (an
// omitted request field is stored and echoed as an empty object).
type Course struct {
	ID        string         `json:"id"`
	Title     string         `json:"title"`
	Topic     Topic          `json:"topic"`
	Type      DeliveryType   `json:"type"`
	Status    Status         `json:"status"`
	Metadata  map[string]any `json:"metadata"`
	CreatedBy string         `json:"created_by"`
	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
}

// Input carries the client-supplied fields shared by create and update.
// The prototype has no auth context, so CreatedBy is optional and taken
// from the request body (empty when omitted).
type Input struct {
	Title     string
	Topic     Topic
	Type      DeliveryType
	Status    Status
	Metadata  map[string]any
	CreatedBy string
}

// Filter selects courses for listing. Empty enum values match everything;
// Limit and Offset paginate the matching set.
type Filter struct {
	Topic  Topic
	Type   DeliveryType
	Status Status
	Limit  int
	Offset int
}

// normalize validates client input and produces a complete course. Title,
// topic and type are required; every enum must be one of the allowed
// values; status defaults to 启用 and metadata to an empty object. The
// timestamps and the server-generated id come from the caller.
func normalize(input Input, now time.Time, id string) (Course, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return Course{}, &ValidationError{Message: "title required"}
	}
	if !input.Topic.Valid() {
		return Course{}, &ValidationError{Message: fmt.Sprintf("invalid topic: %q", input.Topic)}
	}
	if !input.Type.Valid() {
		return Course{}, &ValidationError{Message: fmt.Sprintf("invalid type: %q", input.Type)}
	}
	status := input.Status
	if status == "" {
		status = DefaultStatus
	}
	if !status.Valid() {
		return Course{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Course{
		ID:        id,
		Title:     title,
		Topic:     input.Topic,
		Type:      input.Type,
		Status:    status,
		Metadata:  metadata,
		CreatedBy: input.CreatedBy,
		CreatedAt: now,
		UpdatedAt: now,
	}, nil
}
