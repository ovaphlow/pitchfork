package examrecords

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// PaperLookup is the subset of the papers store the exam-records service
// needs: it reads a paper by id to validate that the paper of an exam
// start exists (404) and to snapshot its questions. Injected at the
// composition root so the exam-records service never owns a paper store.
type PaperLookup interface {
	Get(ctx context.Context, id string) (papers.Paper, error)
}

// Service applies the exam-records business rules (validation,
// snapshotting at exam start, self-contained submission grading,
// server-generated ids and timestamps) on top of the store. Submission
// works against the snapshot only: the submit path never touches the
// paper lookup.
type Service struct {
	store  Store
	papers PaperLookup
	now    func() time.Time
	newID  func() string
}

// NewService builds a service over the given store and paper lookup.
// The server-generated id is a 26-character Crockford Base32 ULID.
func NewService(store Store, papers PaperLookup) *Service {
	return &Service{store: store, papers: papers, now: time.Now, newID: ulid.New}
}

// Create opens an exam for one employee on one paper and returns the
// new record. employee_id is required and must be a 26-character ULID
// (the prototype has no employee master data, so there is no existence
// check); paper_id is required and must exist, otherwise 404. The
// server generates the id and start_time and snapshots the paper (id,
// pass_score and every question with its standard answer) into
// answers_snapshot, so the record is self-contained for grading. The
// same employee may open the same paper multiple times; every open is
// an independent record.
func (s *Service) Create(ctx context.Context, input Input) (Record, error) {
	employeeID := strings.TrimSpace(input.EmployeeID)
	if employeeID == "" {
		return Record{}, &ValidationError{Message: "employee_id required"}
	}
	if !ValidULID(employeeID) {
		return Record{}, &ValidationError{Message: "invalid employee_id: must be a 26-character Crockford Base32 ULID"}
	}
	if strings.TrimSpace(input.PaperID) == "" {
		return Record{}, &ValidationError{Message: "paper_id required"}
	}
	paper, err := s.papers.Get(ctx, input.PaperID)
	if err != nil {
		return Record{}, ErrPaperNotFound
	}
	now := s.now()
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	record := Record{
		ID:              s.newID(),
		EmployeeID:      employeeID,
		PaperID:         paper.ID,
		StartTime:       now,
		AnswersSnapshot: snapshotOf(paper),
		Metadata:        metadata,
		CreatedBy:       input.CreatedBy,
		CreatedAt:       now,
		UpdatedAt:       now,
	}
	if err := s.store.Create(ctx, record); err != nil {
		return Record{}, err
	}
	return record, nil
}

// Get returns the record with the given id, or ErrNotFound. Before
// submission end_time/score/passed are null; after submission they are
// filled.
func (s *Service) Get(ctx context.Context, id string) (Record, error) {
	return s.store.Get(ctx, id)
}

// List returns the records matching the filter (employee_id/paper_id
// exact matches, paginated) ordered by created_at DESC.
func (s *Service) List(ctx context.Context, filter Filter) ([]Record, int, error) {
	return s.store.List(ctx, filter)
}

// Submit grades the answers of the record with the given id against its
// answers_snapshot, writes score/passed/end_time and returns the
// finished record. The grading is fully self-contained: it reads the
// snapshot only and never touches the paper lookup. A record that was
// already submitted (end_time set) is a 400; an unknown record a 404.
// The grading rules are pinned in grade.
func (s *Service) Submit(ctx context.Context, id string, answers map[string]any) (Record, error) {
	record, err := s.store.Get(ctx, id)
	if err != nil {
		return Record{}, err
	}
	if record.EndTime != nil {
		return Record{}, ErrAlreadySubmitted
	}
	score, err := grade(record.AnswersSnapshot, answers)
	if err != nil {
		return Record{}, err
	}
	passed := score >= record.AnswersSnapshot.PassScore
	now := s.now()
	record.Score = &score
	record.Passed = &passed
	record.EndTime = &now
	record.UpdatedAt = now
	if err := s.store.Update(ctx, record); err != nil {
		return Record{}, err
	}
	return record, nil
}

// snapshotOf projects a paper onto the read-only exam snapshot: the
// paper id, its pass score and the full question list with the standard
// answers (id/type/difficulty/content/options/answer). The papers
// dependency delivers no per-question score, so the snapshot carries
// exactly the delivered fields and every correct answer is worth one
// point (see grade).
func snapshotOf(paper papers.Paper) Snapshot {
	snapshot := Snapshot{
		PaperID:   paper.ID,
		PassScore: paper.PassScore,
		Questions: make([]QuestionSnapshot, 0, len(paper.Questions)),
	}
	for _, question := range paper.Questions {
		snapshot.Questions = append(snapshot.Questions, QuestionSnapshot{
			ID:         question.ID,
			Type:       question.Type,
			Difficulty: question.Difficulty,
			Content:    question.Content,
			Options:    append([]string(nil), question.Options...),
			Answer:     cloneAnswer(question.Answer),
		})
	}
	return snapshot
}

// grade scores a submission against the snapshot. The papers dependency
// delivers no per-question score, so every snapshot question is worth
// one point: score is the number of correctly answered questions and a
// record passes when score >= snapshot.pass_score. The pinned rules:
//
//   - a snapshot question missing from answers (漏答) earns 0 points and
//     is not an error — the submission completes normally;
//   - a shape mismatch is a ValidationError (400): 单选/判断/填空 answers
//     must be non-empty strings, 多选 answers non-empty arrays of
//     strings;
//   - a well-shaped but wrong value earns 0 points: 单选/判断/填空 must
//     equal the snapshot answer exactly, 多选 must equal the snapshot
//     answer set exactly (少选/多选/错选 all fail; order does not
//     matter);
//   - an answers key that is not a snapshot question id is a
//     ValidationError.
func grade(snapshot Snapshot, answers map[string]any) (int, error) {
	known := make(map[string]bool, len(snapshot.Questions))
	for _, question := range snapshot.Questions {
		known[question.ID] = true
	}
	for id := range answers {
		if !known[id] {
			return 0, &ValidationError{Message: fmt.Sprintf("unknown question id: %s", id)}
		}
	}
	score := 0
	for _, question := range snapshot.Questions {
		value, answered := answers[question.ID]
		if !answered {
			continue
		}
		correct, err := matches(question, value)
		if err != nil {
			return 0, err
		}
		if correct {
			score++
		}
	}
	return score, nil
}

// matches reports whether the submitted value of one question is
// correct. A well-shaped but wrong value returns false (0 points); a
// value whose shape does not fit the question type is a
// ValidationError.
func matches(question QuestionSnapshot, value any) (bool, error) {
	switch question.Type {
	case questions.QuestionTypeMultiple:
		submitted, ok := multiAnswerStrings(value)
		if !ok || len(submitted) == 0 {
			return false, &ValidationError{Message: fmt.Sprintf("question %s: 多选 answer must be a non-empty array of strings", question.ID)}
		}
		return sameAnswerSet(submitted, question.Answer), nil
	default: // 单选/判断/填空
		submitted, ok := value.(string)
		if !ok || submitted == "" {
			return false, &ValidationError{Message: fmt.Sprintf("question %s: answer must be a non-empty string", question.ID)}
		}
		standard, _ := question.Answer.(string)
		return submitted == standard, nil
	}
}

// multiAnswerStrings normalizes a submitted 多选 answer to a string
// slice. JSON bodies decode arrays as []any, in-process callers may
// hand over []string directly; every element must be a string.
func multiAnswerStrings(value any) ([]string, bool) {
	switch values := value.(type) {
	case []any:
		submitted := make([]string, 0, len(values))
		for _, item := range values {
			option, ok := item.(string)
			if !ok {
				return nil, false
			}
			submitted = append(submitted, option)
		}
		return submitted, true
	case []string:
		return append([]string(nil), values...), true
	default:
		return nil, false
	}
}

// sameAnswerSet reports whether the submitted options equal the
// snapshot answer as a set: every submitted option must be in the
// standard answer and vice versa, without duplicates. 少选 (subset),
// 多选 (superset) and 错选 (a wrong option) all fail; the order does not
// matter.
func sameAnswerSet(submitted []string, standard any) bool {
	want := make(map[string]bool)
	switch answer := standard.(type) {
	case []any:
		for _, item := range answer {
			option, ok := item.(string)
			if !ok {
				return false
			}
			want[option] = true
		}
	case []string:
		for _, option := range answer {
			want[option] = true
		}
	default:
		return false
	}
	if len(submitted) != len(want) {
		return false
	}
	seen := make(map[string]bool, len(submitted))
	for _, option := range submitted {
		if !want[option] || seen[option] {
			return false
		}
		seen[option] = true
	}
	return true
}
