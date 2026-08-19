package opinion

import (
	"context"
	"sort"
)

// CreateComplaint appends the opinion complaint to the store. The id,
// run_id and timestamps come from the service; the store clones the row
// so later mutations of the caller's value cannot leak into the store.
func (s *InMemoryStore) CreateComplaint(_ context.Context, complaint Complaint) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.complaints = append(s.complaints, cloneComplaint(complaint))
	return nil
}

// ListComplaints returns the complaints of the run matching the filter
// (channel / complaint_type / status exact matches) ordered by
// created_at ASC, id ASC (the complaint flow lists the tickets in
// intake order, 受理顺序; the id tie-break keeps the order deterministic
// when complaints share a timestamp), the total number of matches and
// the paginated page.
func (s *InMemoryStore) ListComplaints(_ context.Context, runID string, filter ComplaintFilter) ([]Complaint, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Complaint, 0, len(s.complaints))
	for _, item := range s.complaints {
		if item.RunID != runID {
			continue
		}
		if filter.Channel != "" && item.Channel != filter.Channel {
			continue
		}
		if filter.ComplaintType != "" && item.ComplaintType != filter.ComplaintType {
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
	page := make([]Complaint, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneComplaint(item))
	}
	return page, total, nil
}

// GetComplaint returns the complaint with the given id within the run,
// or ErrComplaintNotFound (a complaint of another run is not found as
// well).
func (s *InMemoryStore) GetComplaint(_ context.Context, runID, id string) (Complaint, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfComplaint(runID, id)
	if index < 0 {
		return Complaint{}, ErrComplaintNotFound
	}
	return cloneComplaint(s.complaints[index]), nil
}

// UpdateComplaint replaces the complaint with the same id within the
// same run, or ErrComplaintNotFound. The id, run_id and created_at are
// preserved by the service; the store persists the updated row as-is.
func (s *InMemoryStore) UpdateComplaint(_ context.Context, complaint Complaint) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfComplaint(complaint.RunID, complaint.ID)
	if index < 0 {
		return ErrComplaintNotFound
	}
	s.complaints[index] = cloneComplaint(complaint)
	return nil
}

// DeleteComplaint removes the complaint with the given id within the
// run, or ErrComplaintNotFound.
func (s *InMemoryStore) DeleteComplaint(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfComplaint(runID, id)
	if index < 0 {
		return ErrComplaintNotFound
	}
	s.complaints = append(s.complaints[:index], s.complaints[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOfComplaint(runID, id string) int {
	for i, item := range s.complaints {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func cloneComplaint(complaint Complaint) Complaint {
	cloned := complaint
	if complaint.ClosedAt != nil {
		value := *complaint.ClosedAt
		cloned.ClosedAt = &value
	}
	cloned.Metadata = cloneMap(complaint.Metadata)
	return cloned
}
