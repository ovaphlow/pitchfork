package opinion

import (
	"context"
	"sort"
)

// CreateMediaQuestion appends the opinion media question to the store.
// The id, run_id and timestamps come from the service; the store clones
// the row so later mutations of the caller's value cannot leak into the
// store.
func (s *InMemoryStore) CreateMediaQuestion(_ context.Context, question MediaQuestion) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.mediaQuestions = append(s.mediaQuestions, cloneMediaQuestion(question))
	return nil
}

// ListMediaQuestions returns the media questions of the run matching
// the filter (question_type / status exact matches) ordered by
// created_at ASC, id ASC (the press conference lists the questions in
// question order; the id tie-break keeps the order deterministic when
// questions share a timestamp), the total number of matches and the
// paginated page.
func (s *InMemoryStore) ListMediaQuestions(_ context.Context, runID string, filter MediaQuestionFilter) ([]MediaQuestion, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]MediaQuestion, 0, len(s.mediaQuestions))
	for _, item := range s.mediaQuestions {
		if item.RunID != runID {
			continue
		}
		if filter.QuestionType != "" && item.QuestionType != filter.QuestionType {
			continue
		}
		if filter.Status != "" && item.Status != filter.Status {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].CreatedAt.Equal(matched[j].CreatedAt) {
			return matched[i].ID < matched[j].ID
		}
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginatePosts(total, filter.Limit, filter.Offset)
	page := make([]MediaQuestion, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneMediaQuestion(item))
	}
	return page, total, nil
}

// GetMediaQuestion returns the media question with the given id within
// the run, or ErrMediaQuestionNotFound (a question of another run is
// not found as well).
func (s *InMemoryStore) GetMediaQuestion(_ context.Context, runID, id string) (MediaQuestion, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfMediaQuestion(runID, id)
	if index < 0 {
		return MediaQuestion{}, ErrMediaQuestionNotFound
	}
	return cloneMediaQuestion(s.mediaQuestions[index]), nil
}

// UpdateMediaQuestion replaces the media question with the same id
// within the same run, or ErrMediaQuestionNotFound. The id, run_id and
// created_at are preserved by the service; the store persists the
// updated row as-is.
func (s *InMemoryStore) UpdateMediaQuestion(_ context.Context, question MediaQuestion) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfMediaQuestion(question.RunID, question.ID)
	if index < 0 {
		return ErrMediaQuestionNotFound
	}
	s.mediaQuestions[index] = cloneMediaQuestion(question)
	return nil
}

// DeleteMediaQuestion removes the media question with the given id
// within the run, or ErrMediaQuestionNotFound.
func (s *InMemoryStore) DeleteMediaQuestion(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfMediaQuestion(runID, id)
	if index < 0 {
		return ErrMediaQuestionNotFound
	}
	s.mediaQuestions = append(s.mediaQuestions[:index], s.mediaQuestions[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOfMediaQuestion(runID, id string) int {
	for i, item := range s.mediaQuestions {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func cloneMediaQuestion(question MediaQuestion) MediaQuestion {
	cloned := question
	if question.AnsweredAt != nil {
		value := *question.AnsweredAt
		cloned.AnsweredAt = &value
	}
	cloned.Metadata = cloneMap(question.Metadata)
	return cloned
}
