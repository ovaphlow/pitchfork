// Package assignments implements the training-task-assignment business
// object of prototyped: the model with its enum and format validation, a
// store interface with an in-memory implementation, and the service
// layer. The package never touches a database; a PostgreSQL-backed store
// can be swapped in later behind the same interface. An assignment
// belongs to a course (validated through an injected course store) and
// targets 用户/岗位/部门 through target_ids; with no master data for
// posts or departments, employee expansion matches 用户 assignments only.
package assignments

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrNotFound is returned by the store and service when an assignment id
// does not exist. It maps to HTTP 404 in the routing layer.
var ErrNotFound = errors.New("assignment not found")

// ErrCourseNotFound is returned by the service when the course of an
// assignment does not exist. It maps to HTTP 404 in the routing layer
// (course existence is checked before the assignment is stored).
var ErrCourseNotFound = errors.New("course not found")

// ValidationError describes a request that violates the assignments
// business rules (missing required fields, invalid enum values or
// invalid formats). It maps to HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// AssignType is the assignment mode (指派方式) of a training task.
type AssignType string

const (
	AssignTypeManual AssignType = "手动指派"
	AssignTypeAuto   AssignType = "自动触发"
)

var validAssignTypes = []AssignType{AssignTypeManual, AssignTypeAuto}

// Valid reports whether assignType is one of the allowed values.
func (assignType AssignType) Valid() bool {
	for _, candidate := range validAssignTypes {
		if assignType == candidate {
			return true
		}
	}
	return false
}

// TargetType is the target dimension (指派对象类型) of an assignment.
type TargetType string

const (
	TargetTypeUser TargetType = "用户"
	TargetTypePost TargetType = "岗位"
	TargetTypeDept TargetType = "部门"
)

var validTargetTypes = []TargetType{TargetTypeUser, TargetTypePost, TargetTypeDept}

// Valid reports whether targetType is one of the allowed values.
func (targetType TargetType) Valid() bool {
	for _, candidate := range validTargetTypes {
		if targetType == candidate {
			return true
		}
	}
	return false
}

// Assignment is a training task assignment as exposed by the API.
// trigger_rule is an optional JSONB extension (an empty object when
// omitted) that carries the trigger rule of 自动触发 assignments;
// deadline is an optional RFC3339 timestamp (empty means unset);
// target_ids are the ids of the assigned targets and always contain
// non-empty strings.
type Assignment struct {
	ID          string         `json:"id"`
	CourseID    string         `json:"course_id"`
	AssignType  AssignType     `json:"assign_type"`
	TriggerRule map[string]any `json:"trigger_rule"`
	Deadline    string         `json:"deadline"`
	TargetType  TargetType     `json:"target_type"`
	TargetIDs   []string       `json:"target_ids"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

// Input carries the client-supplied fields of a create request. The
// prototype has no auth context, so CreatedBy is optional and taken from
// the request body (empty when omitted). TriggerRule is a JSON object
// validated by the routing layer on the raw body; nil means omitted and
// defaults to an empty object.
type Input struct {
	CourseID    string
	AssignType  AssignType
	TriggerRule map[string]any
	Deadline    string
	TargetType  TargetType
	TargetIDs   []string
	CreatedBy   string
}

// Filter selects assignments for listing. Empty values match everything;
// EmployeeID expands to 用户 assignments whose target_ids contain the id
// (岗位/部门 assignments never match an employee id — there is no master
// data to expand them); Limit and Offset paginate the matching set.
type Filter struct {
	CourseID   string
	EmployeeID string
	TargetType TargetType
	Limit      int
	Offset     int
}

// normalize validates client input and produces a complete assignment.
// course_id, assign_type, target_type and target_ids are required;
// trigger_rule defaults to an empty object; deadline is optional and must
// be an RFC3339 timestamp when non-empty. The timestamps and the
// server-generated id come from the caller.
func normalize(input Input, now time.Time, id string) (Assignment, error) {
	courseID := strings.TrimSpace(input.CourseID)
	if courseID == "" {
		return Assignment{}, &ValidationError{Message: "course_id required"}
	}
	if !input.AssignType.Valid() {
		if input.AssignType == "" {
			return Assignment{}, &ValidationError{Message: "assign_type required"}
		}
		return Assignment{}, &ValidationError{Message: fmt.Sprintf("invalid assign_type: %q", input.AssignType)}
	}
	if !input.TargetType.Valid() {
		if input.TargetType == "" {
			return Assignment{}, &ValidationError{Message: "target_type required"}
		}
		return Assignment{}, &ValidationError{Message: fmt.Sprintf("invalid target_type: %q", input.TargetType)}
	}
	if len(input.TargetIDs) == 0 {
		return Assignment{}, &ValidationError{Message: "target_ids required"}
	}
	for index, targetID := range input.TargetIDs {
		if strings.TrimSpace(targetID) == "" {
			return Assignment{}, &ValidationError{
				Message: fmt.Sprintf("target_ids must not contain empty elements (index %d)", index),
			}
		}
	}
	triggerRule := input.TriggerRule
	if triggerRule == nil {
		triggerRule = map[string]any{}
	}
	deadline := strings.TrimSpace(input.Deadline)
	if deadline != "" {
		if _, err := time.Parse(time.RFC3339, deadline); err != nil {
			return Assignment{}, &ValidationError{Message: "invalid deadline: must be an RFC3339 timestamp"}
		}
	}
	return Assignment{
		ID:          id,
		CourseID:    courseID,
		AssignType:  input.AssignType,
		TriggerRule: triggerRule,
		Deadline:    deadline,
		TargetType:  input.TargetType,
		TargetIDs:   input.TargetIDs,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}
