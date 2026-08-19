package opinion

import (
	"context"
	"fmt"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// postWritableRun reports whether a run in the given status may receive
// opinion post writes: only 进行中. The monitoring feed is live while
// the drill runs (unlike the opinion event configuration, posts cannot
// be created before the run starts); every other status rejects
// POST/PUT/DELETE with 400 while GET is never gated.
func postWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress
}

func postWriteGateError(status drills.RunStatus) error {
	return &ValidationError{Message: "run status " + string(status) + " does not allow this operation"}
}

// CreatePost adds one opinion post (舆情信息) to the monitoring feed of
// the run and returns the created row. The run must exist (ErrRunNotFound,
// 404) and be 进行中 (ValidationError, 400); content is required; source
// defaults to 微博, sentiment to 负面, heat to 0, warn_status to 未预警
// (a new post only accepts 未预警); warned_at is nil at creation;
// metadata defaults to {} and created_by to ''; the id is a
// server-generated 26-character Crockford Base32 ULID and the timestamps
// are set by the service.
func (s *Service) CreatePost(ctx context.Context, runID string, input PostInput) (Post, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Post{}, err
	}
	if !postWritableRun(run.Status) {
		return Post{}, postWriteGateError(run.Status)
	}
	post, err := normalizePost(runID, input, s.now(), s.newID())
	if err != nil {
		return Post{}, err
	}
	if err := s.store.CreatePost(ctx, post); err != nil {
		return Post{}, err
	}
	return post, nil
}

// ListPosts returns the posts of the run matching the filter (source /
// sentiment / warn_status exact matches), ordered by created_at DESC,
// id DESC (the monitoring flow lists the newest posts first), and the
// total number of matches. A missing run is ErrRunNotFound (404). GET
// is not subject to the write gate: a run in any status with posts
// still answers 200.
func (s *Service) ListPosts(ctx context.Context, runID string, filter PostFilter) ([]Post, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListPosts(ctx, runID, filter)
}

// GetPost returns the post with the given id within the run. A missing
// run is ErrRunNotFound; a missing post is ErrPostNotFound. GET is not
// subject to the write gate.
func (s *Service) GetPost(ctx context.Context, runID, id string) (Post, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Post{}, err
	}
	return s.store.GetPost(ctx, runID, id)
}

// UpdatePost updates the post in place (partial update: omitted fields
// keep their current values; content/source/sentiment/warn_status/
// created_by are kept when empty, heat and metadata are applied only
// when explicitly provided) and returns the updated row. The run must
// exist and be 进行中 (404 / 400); a missing post is ErrPostNotFound
// (404). The warn state machine governs warn_status: 未预警 -> 已预警 is
// the only transition and sets warned_at (when it is not already set);
// 已预警 -> 未预警 is 400; a same-value no-op is legal and keeps
// warned_at unchanged; a PUT that does not touch warn_status keeps
// warned_at as well. updated_at is refreshed by the service; id,
// run_id, created_at and warned_at are preserved otherwise.
func (s *Service) UpdatePost(ctx context.Context, runID, id string, update PostUpdate) (Post, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Post{}, err
	}
	if !postWritableRun(run.Status) {
		return Post{}, postWriteGateError(run.Status)
	}
	post, err := s.store.GetPost(ctx, runID, id)
	if err != nil {
		return Post{}, err
	}
	if update.Content != "" {
		post.Content = update.Content
	}
	if update.Source != "" {
		if !update.Source.Valid() {
			return Post{}, &ValidationError{Message: fmt.Sprintf("invalid source: %q", update.Source)}
		}
		post.Source = update.Source
	}
	if update.Sentiment != "" {
		if !update.Sentiment.Valid() {
			return Post{}, &ValidationError{Message: fmt.Sprintf("invalid sentiment: %q", update.Sentiment)}
		}
		post.Sentiment = update.Sentiment
	}
	if update.HasHeat {
		if update.Heat < 0 || update.Heat > 100 {
			return Post{}, &ValidationError{Message: fmt.Sprintf("invalid heat: %d (must be between 0 and 100)", update.Heat)}
		}
		post.Heat = update.Heat
	}
	if update.WarnStatus != "" {
		if !update.WarnStatus.Valid() {
			return Post{}, &ValidationError{Message: fmt.Sprintf("invalid warn_status: %q", update.WarnStatus)}
		}
		if post.WarnStatus == WarnStatusWarned && update.WarnStatus == WarnStatusPending {
			return Post{}, &ValidationError{
				Message: fmt.Sprintf("illegal opinion post warn_status transition: %s -> %s", post.WarnStatus, update.WarnStatus),
			}
		}
		if update.WarnStatus == WarnStatusWarned && post.WarnStatus != WarnStatusWarned {
			now := s.now()
			post.WarnedAt = &now
		}
		post.WarnStatus = update.WarnStatus
	}
	if update.HasMetadata {
		post.Metadata = update.Metadata
	}
	if update.CreatedBy != "" {
		post.CreatedBy = update.CreatedBy
	}
	post.UpdatedAt = s.now()
	if err := s.store.UpdatePost(ctx, post); err != nil {
		return Post{}, err
	}
	return post, nil
}

// DeletePost removes the post with the given id within the run. The run
// must exist and be 进行中 (404 / 400); a missing post is
// ErrPostNotFound (404).
func (s *Service) DeletePost(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !postWritableRun(run.Status) {
		return postWriteGateError(run.Status)
	}
	return s.store.DeletePost(ctx, runID, id)
}
