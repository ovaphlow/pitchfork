package opinion

import (
	"context"
	"sort"
)

// CreatePost appends the opinion post to the store. The id, run_id and
// timestamps come from the service; the store clones the row so later
// mutations of the caller's value cannot leak into the store.
func (s *InMemoryStore) CreatePost(_ context.Context, post Post) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.posts = append(s.posts, clonePost(post))
	return nil
}

// ListPosts returns the posts of the run matching the filter (source /
// sentiment / warn_status exact matches) ordered by created_at DESC,
// id DESC (the monitoring flow lists the newest posts first; the id
// tie-break keeps the order deterministic when posts share a
// timestamp), the total number of matches and the paginated page.
func (s *InMemoryStore) ListPosts(_ context.Context, runID string, filter PostFilter) ([]Post, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Post, 0, len(s.posts))
	for _, item := range s.posts {
		if item.RunID != runID {
			continue
		}
		if filter.Source != "" && item.Source != filter.Source {
			continue
		}
		if filter.Sentiment != "" && item.Sentiment != filter.Sentiment {
			continue
		}
		if filter.WarnStatus != "" && item.WarnStatus != filter.WarnStatus {
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
	start, end := paginatePosts(total, filter.Limit, filter.Offset)
	page := make([]Post, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, clonePost(item))
	}
	return page, total, nil
}

// GetPost returns the post with the given id within the run, or
// ErrPostNotFound (a post of another run is not found as well).
func (s *InMemoryStore) GetPost(_ context.Context, runID, id string) (Post, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfPost(runID, id)
	if index < 0 {
		return Post{}, ErrPostNotFound
	}
	return clonePost(s.posts[index]), nil
}

// UpdatePost replaces the post with the same id within the same run, or
// ErrPostNotFound. The id, run_id and created_at are preserved by the
// service; the store persists the updated row as-is.
func (s *InMemoryStore) UpdatePost(_ context.Context, post Post) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfPost(post.RunID, post.ID)
	if index < 0 {
		return ErrPostNotFound
	}
	s.posts[index] = clonePost(post)
	return nil
}

// DeletePost removes the post with the given id within the run, or
// ErrPostNotFound.
func (s *InMemoryStore) DeletePost(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfPost(runID, id)
	if index < 0 {
		return ErrPostNotFound
	}
	s.posts = append(s.posts[:index], s.posts[index+1:]...)
	return nil
}

func (s *InMemoryStore) indexOfPost(runID, id string) int {
	for i, item := range s.posts {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func clonePost(post Post) Post {
	cloned := post
	if post.WarnedAt != nil {
		value := *post.WarnedAt
		cloned.WarnedAt = &value
	}
	cloned.Metadata = cloneMap(post.Metadata)
	return cloned
}

// paginatePosts clamps the (limit, offset) page to the total. A missing
// limit (0) means the caller default (the httpapi layer applies the
// repository default page size before reaching the store).
func paginatePosts(total, limit, offset int) (start, end int) {
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
