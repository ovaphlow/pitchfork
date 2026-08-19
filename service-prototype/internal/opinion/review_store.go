package opinion

import (
	"context"
)

// UpsertReview inserts the review or replaces the review with the same
// run_id (its id and created_at are preserved by the service).
func (s *InMemoryStore) UpsertReview(_ context.Context, review Review) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.reviews {
		if item.RunID == review.RunID {
			s.reviews[i] = cloneReview(review)
			return nil
		}
	}
	s.reviews = append(s.reviews, cloneReview(review))
	return nil
}

// GetReview returns the review of the run, or ErrReviewNotFound.
func (s *InMemoryStore) GetReview(_ context.Context, runID string) (Review, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfReview(runID)
	if index < 0 {
		return Review{}, ErrReviewNotFound
	}
	return cloneReview(s.reviews[index]), nil
}

// DeleteReview removes the review of the run, or ErrReviewNotFound.
func (s *InMemoryStore) DeleteReview(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfReview(runID)
	if index < 0 {
		return ErrReviewNotFound
	}
	s.reviews = append(s.reviews[:index], s.reviews[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOfReview(runID string) int {
	for i, item := range s.reviews {
		if item.RunID == runID {
			return i
		}
	}
	return -1
}

func cloneReview(review Review) Review {
	cloned := review
	cloned.Metadata = cloneMap(review.Metadata)
	return cloned
}
