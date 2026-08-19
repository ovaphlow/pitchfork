// Package examrecords implements the online-exam record (在线考核记录)
// business object of prototyped: the model with its validation, a store
// interface with an in-memory implementation and the service layer that
// snapshots a paper at exam start and grades the submission against the
// snapshot. The package never touches a database; a PostgreSQL-backed
// store can be swapped in later behind the same interface.
package examrecords

import (
	"errors"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// ErrNotFound is returned by the store and service when an exam record
// id does not exist. It maps to HTTP 404 in the routing layer.
var ErrNotFound = errors.New("exam record not found")

// ErrPaperNotFound is returned by the service when the paper of an exam
// start does not exist. It maps to HTTP 404 in the routing layer.
var ErrPaperNotFound = errors.New("paper not found")

// ErrAlreadySubmitted is returned by the service when a submission is
// attempted on a record that already has an end_time. It maps to HTTP
// 400 in the routing layer.
var ErrAlreadySubmitted = errors.New("exam record already submitted")

// ValidationError describes a request that violates the exam-records
// business rules (missing or malformed fields, answer shapes that do not
// match the question type, answers referencing questions outside the
// snapshot). It maps to HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// ulidAlphabet is the Crockford Base32 alphabet of internal/ulid (digits
// 0-9 and A-Z minus I, L, O, U).
const ulidAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// ValidULID reports whether id is a 26-character Crockford Base32 ULID,
// the exact shape internal/ulid.New produces. The prototype has no
// employee master data, so employee_id (and the list filters) are
// validated against this format instead of an existence check.
func ValidULID(id string) bool {
	if len(id) != 26 {
		return false
	}
	for i := 0; i < len(id); i++ {
		if !strings.ContainsRune(ulidAlphabet, rune(id[i])) {
			return false
		}
	}
	return true
}

// Record is one exam record as exposed by the API. The id and start_time
// are server-generated at exam start; end_time/score/passed stay null
// until submission; answers_snapshot is the read-only exam snapshot and
// is never written by the client; metadata follows the repository JSONB
// extension-field convention (an empty object when omitted) and
// created_by is optional (empty when omitted) because the prototype has
// no auth context.
type Record struct {
	ID              string         `json:"id"`
	EmployeeID      string         `json:"employee_id"`
	PaperID         string         `json:"paper_id"`
	StartTime       time.Time      `json:"start_time"`
	EndTime         *time.Time     `json:"end_time"`
	Score           *int           `json:"score"`
	Passed          *bool          `json:"passed"`
	AnswersSnapshot Snapshot       `json:"answers_snapshot"`
	Metadata        map[string]any `json:"metadata"`
	CreatedBy       string         `json:"created_by"`
	CreatedAt       time.Time      `json:"created_at"`
	UpdatedAt       time.Time      `json:"updated_at"`
}

// Snapshot is the read-only, self-contained exam snapshot taken at exam
// start: the paper id, its pass score and every question with its
// standard answer. Submission grading works against the snapshot alone,
// so the submit handler never reads the paper or the question bank
// again. The papers dependency delivers question snapshots without a
// per-question score, so the snapshot carries exactly the delivered
// fields (id/type/difficulty/content/options/answer) and every correct
// answer is worth one point (see grade).
type Snapshot struct {
	PaperID   string             `json:"paper_id"`
	PassScore int                `json:"pass_score"`
	Questions []QuestionSnapshot `json:"questions"`
}

// QuestionSnapshot is one question of the exam snapshot. It mirrors the
// papers.QuestionSnapshot projection (id/type/difficulty/content/
// options/answer): the answer is a string for 单选/判断/填空 and a string
// array for 多选.
type QuestionSnapshot struct {
	ID         string                 `json:"id"`
	Type       questions.QuestionType `json:"type"`
	Difficulty int                    `json:"difficulty"`
	Content    string                 `json:"content"`
	Options    []string               `json:"options"`
	Answer     any                    `json:"answer"`
}

// Input carries the client-supplied fields of an exam start.
// EmployeeID and PaperID are required; Metadata is an optional JSON
// object validated by the routing layer on the raw body (nil means
// omitted and defaults to an empty object); CreatedBy is optional
// (empty when omitted).
type Input struct {
	EmployeeID string
	PaperID    string
	Metadata   map[string]any
	CreatedBy  string
}

// Filter selects exam records for listing. Empty EmployeeID/PaperID
// match everything (an empty query parameter means unset); Limit and
// Offset paginate the matching set (ordered by created_at DESC, id
// DESC).
type Filter struct {
	EmployeeID string
	PaperID    string
	Limit      int
	Offset     int
}
