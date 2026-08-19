// Package progress implements the learning-progress (学习进度) business
// object of prototyped: the model with its status derivation, a store
// interface with an in-memory implementation, and the service layer. The
// package never touches a database; a PostgreSQL-backed store can be
// swapped in later behind the same interface. A progress row records the
// completion state of one course chapter for one employee within one
// training task assignment; the unique key (assignment_id, employee_id,
// chapter_id) gives the upsert semantics: the first report of a chapter
// creates the row, later reports update it in place.
package progress

import (
	"errors"
	"time"
)

// ErrNotFound is returned by the store when a progress row does not
// exist.
var ErrNotFound = errors.New("learning progress not found")

// ErrAssignmentNotFound is returned by the service when the assignment
// of a progress operation does not exist. It maps to HTTP 404 in the
// routing layer.
var ErrAssignmentNotFound = errors.New("assignment not found")

// ErrChapterNotFound is returned by the service when the chapter of a
// progress operation does not exist, or does not belong to the course of
// the assignment. It maps to HTTP 404 in the routing layer.
var ErrChapterNotFound = errors.New("chapter not found")

// ErrCourseNotFound is returned by the service when the course of the
// assignment does not exist. It maps to HTTP 404 in the routing layer.
var ErrCourseNotFound = errors.New("course not found")

// ValidationError describes a request that violates the progress
// business rules (progress_percent outside 0-100). It maps to HTTP 400
// in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// Status is the completion state of a progress row (or of a course in
// the summary). It is derived server-side and never accepted as input.
type Status string

const (
	StatusLearning  Status = "学习中"
	StatusCompleted Status = "已完成"
)

// Progress is one learning-progress row as exposed by the API. The id is
// a server-generated ULID; started_at is set on the first report only
// and preserved by later updates; completed_at is set when the chapter
// is completed and never reverted; detail is an optional JSONB extension
// echoed verbatim (an empty object when omitted).
type Progress struct {
	ID              string         `json:"id"`
	AssignmentID    string         `json:"assignment_id"`
	EmployeeID      string         `json:"employee_id"`
	ChapterID       string         `json:"chapter_id"`
	ProgressPercent int            `json:"progress_percent"`
	Status          Status         `json:"status"`
	Detail          map[string]any `json:"detail"`
	StartedAt       *time.Time     `json:"started_at"`
	CompletedAt     *time.Time     `json:"completed_at"`
	CreatedAt       time.Time      `json:"created_at"`
	UpdatedAt       time.Time      `json:"updated_at"`
}

// Input carries the client-supplied fields of a progress report.
// ProgressPercent is required and must be an integer 0-100 (the routing
// layer enforces the JSON shape, the service the range); Detail is an
// optional JSON object validated by the routing layer on the raw body
// (nil means omitted and defaults to an empty object).
type Input struct {
	ProgressPercent int
	Detail          map[string]any
}

// ChapterProgress is one chapter row of the progress summary. Chapters
// of the assignment course that were never reported carry the zero
// values: progress_percent 0, status 学习中, started_at/completed_at nil
// and an empty detail object.
type ChapterProgress struct {
	ChapterID       string         `json:"chapter_id"`
	ChapterTitle    string         `json:"chapter_title"`
	ProgressPercent int            `json:"progress_percent"`
	Status          Status         `json:"status"`
	StartedAt       *time.Time     `json:"started_at"`
	CompletedAt     *time.Time     `json:"completed_at"`
	Detail          map[string]any `json:"detail"`
}

// Summary aggregates the learning progress of one employee within one
// assignment. It is a single object, not a list endpoint, so the
// {records, meta} pagination convention does not apply. Chapters covers
// every chapter of the assignment course in sort_order ascending; the
// summary status is derived from the chapter rows: a course without
// chapters (an empty set) is never 已完成.
type Summary struct {
	AssignmentID      string            `json:"assignment_id"`
	EmployeeID        string            `json:"employee_id"`
	CourseID          string            `json:"course_id"`
	CourseTitle       string            `json:"course_title"`
	TotalChapters     int               `json:"total_chapters"`
	CompletedChapters int               `json:"completed_chapters"`
	Status            Status            `json:"status"`
	Chapters          []ChapterProgress `json:"chapters"`
}
