package opinion

import (
	"context"
	"fmt"
)

// The release write gate reuses postWritableRun / postWriteGateError:
// like the monitoring feed, the 「信息发布」 release flow is live while
// the drill runs, so only a 进行中 run accepts POST/PUT/DELETE (every
// other status is 400) while GET is never gated.

// CreateRelease adds one opinion release (信息发布记录) to the run and
// returns the created row. The run must exist (ErrRunNotFound, 404) and
// be 进行中 (ValidationError, 400); title and content are required;
// channel defaults to 官网公告, status to 草稿 (a new release only
// accepts 草稿), media_name to '' (not coupled to the channel value);
// published_at is nil at creation; metadata defaults to {} and
// created_by to ''; the id is a server-generated 26-character Crockford
// Base32 ULID and the timestamps are set by the service.
func (s *Service) CreateRelease(ctx context.Context, runID string, input ReleaseInput) (Release, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Release{}, err
	}
	if !postWritableRun(run.Status) {
		return Release{}, postWriteGateError(run.Status)
	}
	release, err := normalizeRelease(runID, input, s.now(), s.newID())
	if err != nil {
		return Release{}, err
	}
	if err := s.store.CreateRelease(ctx, release); err != nil {
		return Release{}, err
	}
	return release, nil
}

// ListReleases returns the releases of the run matching the filter
// (channel / status exact matches), ordered by created_at DESC, id DESC
// (the release flow lists the newest releases first), and the total
// number of matches. A missing run is ErrRunNotFound (404). GET is not
// subject to the write gate: a run in any status with releases still
// answers 200.
func (s *Service) ListReleases(ctx context.Context, runID string, filter ReleaseFilter) ([]Release, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListReleases(ctx, runID, filter)
}

// GetRelease returns the release with the given id within the run. A
// missing run is ErrRunNotFound; a missing release is
// ErrReleaseNotFound. GET is not subject to the write gate.
func (s *Service) GetRelease(ctx context.Context, runID, id string) (Release, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Release{}, err
	}
	return s.store.GetRelease(ctx, runID, id)
}

// UpdateRelease updates the release in place (partial update: omitted
// fields keep their current values, except title and content which are
// required on both entries; channel/media_name/status/created_by are
// kept when empty, metadata is applied only when explicitly provided)
// and returns the updated row. The run must exist and be 进行中 (404 /
// 400); a missing release is ErrReleaseNotFound (404). The publication
// state machine governs status: 草稿 -> 待审核 -> 已发布 -> 已撤回 are
// the only transitions, a same-value no-op is legal, skips and backward
// steps (including 已发布 -> 待审核 and away from 已撤回) are 400. The
// transition into 已发布 sets published_at (when it is not already
// set); every other status resets published_at to null (已撤回 clears
// it again); a PUT that does not touch status keeps published_at
// unchanged. updated_at is refreshed by the service; id, run_id and
// created_at are preserved.
func (s *Service) UpdateRelease(ctx context.Context, runID, id string, update ReleaseUpdate) (Release, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Release{}, err
	}
	if !postWritableRun(run.Status) {
		return Release{}, postWriteGateError(run.Status)
	}
	release, err := s.store.GetRelease(ctx, runID, id)
	if err != nil {
		return Release{}, err
	}
	if update.Title == "" {
		return Release{}, &ValidationError{Message: "title required"}
	}
	if update.Content == "" {
		return Release{}, &ValidationError{Message: "content required"}
	}
	if update.Channel != "" {
		if !update.Channel.Valid() {
			return Release{}, &ValidationError{Message: fmt.Sprintf("invalid channel: %q", update.Channel)}
		}
		release.Channel = update.Channel
	}
	if update.MediaName != "" {
		release.MediaName = update.MediaName
	}
	if update.Status != "" {
		if !update.Status.Valid() {
			return Release{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", update.Status)}
		}
		if !legalReleaseStatusTransition(release.Status, update.Status) {
			return Release{}, &ValidationError{
				Message: fmt.Sprintf("illegal opinion release status transition: %s -> %s", release.Status, update.Status),
			}
		}
		if update.Status == ReleaseStatusPublished && release.Status != ReleaseStatusPublished {
			now := s.now()
			release.PublishedAt = &now
		}
		if update.Status != ReleaseStatusPublished {
			release.PublishedAt = nil
		}
		release.Status = update.Status
	}
	if update.HasMetadata {
		release.Metadata = update.Metadata
	}
	if update.CreatedBy != "" {
		release.CreatedBy = update.CreatedBy
	}
	release.Title = update.Title
	release.Content = update.Content
	release.UpdatedAt = s.now()
	if err := s.store.UpdateRelease(ctx, release); err != nil {
		return Release{}, err
	}
	return release, nil
}

// DeleteRelease removes the release with the given id within the run.
// The run must exist and be 进行中 (404 / 400); a missing release is
// ErrReleaseNotFound (404).
func (s *Service) DeleteRelease(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !postWritableRun(run.Status) {
		return postWriteGateError(run.Status)
	}
	return s.store.DeleteRelease(ctx, runID, id)
}
