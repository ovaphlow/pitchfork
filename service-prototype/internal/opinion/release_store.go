package opinion

import (
	"context"
	"sort"
)

// CreateRelease appends the opinion release to the store. The id,
// run_id and timestamps come from the service; the store clones the row
// so later mutations of the caller's value cannot leak into the store.
func (s *InMemoryStore) CreateRelease(_ context.Context, release Release) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.releases = append(s.releases, cloneRelease(release))
	return nil
}

// ListReleases returns the releases of the run matching the filter
// (channel / status exact matches) ordered by created_at DESC, id DESC
// (the release flow lists the newest releases first; the id tie-break
// keeps the order deterministic when releases share a timestamp), the
// total number of matches and the paginated page.
func (s *InMemoryStore) ListReleases(_ context.Context, runID string, filter ReleaseFilter) ([]Release, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Release, 0, len(s.releases))
	for _, item := range s.releases {
		if item.RunID != runID {
			continue
		}
		if filter.Channel != "" && item.Channel != filter.Channel {
			continue
		}
		if filter.Status != "" && item.Status != filter.Status {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].CreatedAt.Equal(matched[j].CreatedAt) {
			return matched[i].ID > matched[j].ID
		}
		return matched[i].CreatedAt.After(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginateReleases(total, filter.Limit, filter.Offset)
	page := make([]Release, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneRelease(item))
	}
	return page, total, nil
}

// GetRelease returns the release with the given id within the run, or
// ErrReleaseNotFound (a release of another run is not found as well).
func (s *InMemoryStore) GetRelease(_ context.Context, runID, id string) (Release, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfRelease(runID, id)
	if index < 0 {
		return Release{}, ErrReleaseNotFound
	}
	return cloneRelease(s.releases[index]), nil
}

// UpdateRelease replaces the release with the same id within the same
// run, or ErrReleaseNotFound. The id, run_id and created_at are
// preserved by the service; the store persists the updated row as-is.
func (s *InMemoryStore) UpdateRelease(_ context.Context, release Release) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfRelease(release.RunID, release.ID)
	if index < 0 {
		return ErrReleaseNotFound
	}
	s.releases[index] = cloneRelease(release)
	return nil
}

// DeleteRelease removes the release with the given id within the run,
// or ErrReleaseNotFound.
func (s *InMemoryStore) DeleteRelease(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfRelease(runID, id)
	if index < 0 {
		return ErrReleaseNotFound
	}
	s.releases = append(s.releases[:index], s.releases[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOfRelease(runID, id string) int {
	for i, item := range s.releases {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func cloneRelease(release Release) Release {
	cloned := release
	if release.PublishedAt != nil {
		value := *release.PublishedAt
		cloned.PublishedAt = &value
	}
	cloned.Metadata = cloneMap(release.Metadata)
	return cloned
}

// paginateReleases clamps the (limit, offset) page to the total. A
// missing limit (0) means the caller default (the httpapi layer applies
// the repository default page size before reaching the store).
func paginateReleases(total, limit, offset int) (start, end int) {
	start = offset
	if start > total {
		start = total
	}
	end = start + limit
	if limit < 0 || end > total {
		end = total
	}
	return start, end
}
