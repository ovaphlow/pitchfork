package opinion

import (
	"context"
	"fmt"
)

// The media-question write gate reuses postWritableRun /
// postWriteGateError: like the monitoring feed and the release flow,
// the 「媒体沟通」 press conference is live while the drill runs, so only
// a 进行中 run accepts POST/PUT/DELETE (every other status is 400)
// while GET is never gated.

// CreateMediaQuestion adds one opinion media Q&A record (媒体问答记录)
// to the press conference of the run and returns the created row. The
// run must exist (ErrRunNotFound, 404) and be 进行中 (ValidationError,
// 400); media_name and question are required; reporter defaults to '',
// question_type to 事实类, answer to '' and status to 未回答 (a new
// question only accepts 未回答); answered_at is nil at creation;
// metadata defaults to {} and created_by to ''; the id is a
// server-generated 26-character Crockford Base32 ULID and the
// timestamps are set by the service.
func (s *Service) CreateMediaQuestion(ctx context.Context, runID string, input MediaQuestionInput) (MediaQuestion, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return MediaQuestion{}, err
	}
	if !postWritableRun(run.Status) {
		return MediaQuestion{}, postWriteGateError(run.Status)
	}
	question, err := normalizeMediaQuestion(runID, input, s.now(), s.newID())
	if err != nil {
		return MediaQuestion{}, err
	}
	if err := s.store.CreateMediaQuestion(ctx, question); err != nil {
		return MediaQuestion{}, err
	}
	return question, nil
}

// ListMediaQuestions returns the media questions of the run matching
// the filter (question_type / status exact matches), ordered by
// created_at ASC, id ASC (the press conference lists the questions in
// question order), and the total number of matches. A missing run is
// ErrRunNotFound (404). GET is not subject to the write gate: a run in
// any status with questions still answers 200.
func (s *Service) ListMediaQuestions(ctx context.Context, runID string, filter MediaQuestionFilter) ([]MediaQuestion, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListMediaQuestions(ctx, runID, filter)
}

// GetMediaQuestion returns the media question with the given id within
// the run. A missing run is ErrRunNotFound; a missing question is
// ErrMediaQuestionNotFound. GET is not subject to the write gate.
func (s *Service) GetMediaQuestion(ctx context.Context, runID, id string) (MediaQuestion, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return MediaQuestion{}, err
	}
	return s.store.GetMediaQuestion(ctx, runID, id)
}

// UpdateMediaQuestion updates the media question in place (partial
// update: omitted fields keep their current values, except media_name
// and question which are required on both entries; reporter/answer/
// question_type/status/created_by are kept when empty, metadata is
// applied only when explicitly provided) and returns the updated row.
// The run must exist and be 进行中 (404 / 400); a missing question is
// ErrMediaQuestionNotFound (404). The answer state machine governs
// status: 未回答 -> 已回答 is the only transition and sets answered_at
// (when it is not already set); 已回答 -> 未回答 is 400; a same-value
// no-op is legal and keeps answered_at unchanged; a PUT that does not
// touch status keeps answered_at as well. answer is editable at any
// time (an empty answer stays legal). updated_at is refreshed by the
// service; id, run_id and created_at are preserved.
func (s *Service) UpdateMediaQuestion(ctx context.Context, runID, id string, update MediaQuestionUpdate) (MediaQuestion, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return MediaQuestion{}, err
	}
	if !postWritableRun(run.Status) {
		return MediaQuestion{}, postWriteGateError(run.Status)
	}
	question, err := s.store.GetMediaQuestion(ctx, runID, id)
	if err != nil {
		return MediaQuestion{}, err
	}
	if update.MediaName == "" {
		return MediaQuestion{}, &ValidationError{Message: "media_name required"}
	}
	if update.Question == "" {
		return MediaQuestion{}, &ValidationError{Message: "question required"}
	}
	if update.QuestionType != "" {
		if !update.QuestionType.Valid() {
			return MediaQuestion{}, &ValidationError{Message: fmt.Sprintf("invalid question_type: %q", update.QuestionType)}
		}
		question.QuestionType = update.QuestionType
	}
	if update.Status != "" {
		if !update.Status.Valid() {
			return MediaQuestion{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", update.Status)}
		}
		if question.Status == AnswerStatusAnswered && update.Status == AnswerStatusPending {
			return MediaQuestion{}, &ValidationError{
				Message: fmt.Sprintf("illegal opinion media question status transition: %s -> %s", question.Status, update.Status),
			}
		}
		if update.Status == AnswerStatusAnswered && question.Status != AnswerStatusAnswered {
			now := s.now()
			question.AnsweredAt = &now
		}
		question.Status = update.Status
	}
	if update.Reporter != "" {
		question.Reporter = update.Reporter
	}
	if update.Answer != "" {
		question.Answer = update.Answer
	}
	if update.HasMetadata {
		question.Metadata = update.Metadata
	}
	if update.CreatedBy != "" {
		question.CreatedBy = update.CreatedBy
	}
	question.MediaName = update.MediaName
	question.Question = update.Question
	question.UpdatedAt = s.now()
	if err := s.store.UpdateMediaQuestion(ctx, question); err != nil {
		return MediaQuestion{}, err
	}
	return question, nil
}

// DeleteMediaQuestion removes the media question with the given id
// within the run. The run must exist and be 进行中 (404 / 400); a
// missing question is ErrMediaQuestionNotFound (404).
func (s *Service) DeleteMediaQuestion(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !postWritableRun(run.Status) {
		return postWriteGateError(run.Status)
	}
	return s.store.DeleteMediaQuestion(ctx, runID, id)
}
