package dispatch

import (
	"context"
	"errors"
	"sort"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ErrZoneDensityNotFound is returned when the run exists but the
// zone-density report does not. It maps to HTTP 404 in the routing
// layer.
var ErrZoneDensityNotFound = errors.New("zone density report not found")

// ZoneDensity is a zone crowd-density report (区域人流热力上报) of a
// drill run: field personnel report the crowd density of each zone
// (zone_name is required; people_count is the required non-negative
// head count) and the reports drive the command-center big-screen heat
// map. A report is recorded the moment it arrives: reported_at is set
// by the service at creation and refreshed on every update (the column
// stays nullable so the in-memory model mirrors the same optional
// instant shape as sent_at of dispatch_messages, but it always echoes
// non-null after creation). The id and the timestamps are
// server-generated; run_id comes from the route path.
type ZoneDensity struct {
	ID          string     `json:"id"`
	RunID       string     `json:"run_id"`
	ZoneName    string     `json:"zone_name"`
	PeopleCount int        `json:"people_count"`
	ReportedAt  *time.Time `json:"reported_at"`
	CreatedBy   string     `json:"created_by"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
}

// ZoneDensityInput carries the client-supplied fields of a zone-density
// report. id, run_id, reported_at and the timestamps are never part of
// the input: they are decided by the route path and the service.
// zone_name is required (empty or whitespace is rejected); people_count
// is required and must be non-negative (a nil PeopleCount means the
// field was omitted); created_by passes through at creation (the
// prototype has no auth context) and is preserved on update.
type ZoneDensityInput struct {
	ZoneName    string
	PeopleCount *int
	CreatedBy   string
}

// ZoneDensityFilter selects zone-density reports for listing. An empty
// zone_name matches everything; Limit and Offset paginate the matching
// set.
type ZoneDensityFilter struct {
	ZoneName string
	Limit    int
	Offset   int
}

// normalizeZoneDensity validates client input and produces a complete
// zone-density report. zone_name is required (empty or whitespace is
// rejected); people_count is required and must be non-negative.
// reported_at is set by the caller at creation (a report is recorded
// the moment it arrives); created_by passes through. The run and the
// timestamps come from the caller.
func normalizeZoneDensity(runID string, input ZoneDensityInput, now time.Time, id string) (ZoneDensity, error) {
	zoneName := strings.TrimSpace(input.ZoneName)
	if zoneName == "" {
		return ZoneDensity{}, &ValidationError{Message: "zone_name required"}
	}
	if input.PeopleCount == nil {
		return ZoneDensity{}, &ValidationError{Message: "people_count required"}
	}
	if *input.PeopleCount < 0 {
		return ZoneDensity{}, &ValidationError{Message: "people_count must be non-negative"}
	}
	reportedAt := now
	return ZoneDensity{
		ID:          id,
		RunID:       runID,
		ZoneName:    zoneName,
		PeopleCount: *input.PeopleCount,
		ReportedAt:  &reportedAt,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// zoneDensityWritableRun reports whether a run in the given status may
// receive zone-density writes: like the dispatch orders and messages,
// the reports are only writable while the run is 进行中.
func zoneDensityWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress
}

// CreateZoneDensity records a zone crowd-density report within the run
// and returns the created row. The run must be 进行中 (400 otherwise); a
// missing run is ErrRunNotFound (404). reported_at is set by the
// service at creation.
func (s *Service) CreateZoneDensity(ctx context.Context, runID string, input ZoneDensityInput) (ZoneDensity, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return ZoneDensity{}, err
	}
	if !zoneDensityWritableRun(run.Status) {
		return ZoneDensity{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	density, err := normalizeZoneDensity(runID, input, now, s.newID())
	if err != nil {
		return ZoneDensity{}, err
	}
	if err := s.store.CreateZoneDensity(ctx, density); err != nil {
		return ZoneDensity{}, err
	}
	return density, nil
}

// GetZoneDensity returns the zone-density report with the given id
// within the run. A missing run is ErrRunNotFound; a missing report is
// ErrZoneDensityNotFound. GET is not subject to the write gate: a run
// in 已完成/已终止 with reports still answers 200.
func (s *Service) GetZoneDensity(ctx context.Context, runID, id string) (ZoneDensity, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return ZoneDensity{}, err
	}
	return s.store.GetZoneDensity(ctx, runID, id)
}

// ListZoneDensities returns the zone-density reports of the run
// matching the filter (zone_name exact match, ordered by reported_at
// DESC, id DESC — the newest report first) and the total number of
// matches. A missing run is ErrRunNotFound; GET is not subject to the
// write gate.
func (s *Service) ListZoneDensities(ctx context.Context, runID string, filter ZoneDensityFilter) ([]ZoneDensity, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListZoneDensities(ctx, runID, filter)
}

// UpdateZoneDensity updates the zone-density report in place (full
// replacement of zone_name and people_count, both required; reported_at
// is refreshed to the server time — a report re-arrives the moment it
// is updated). The run must be 进行中 (400 otherwise); a missing run or
// report is a 404 (the run existence check comes first). id, run_id,
// created_at and created_by are preserved.
func (s *Service) UpdateZoneDensity(ctx context.Context, runID, id string, input ZoneDensityInput) (ZoneDensity, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return ZoneDensity{}, err
	}
	if !zoneDensityWritableRun(run.Status) {
		return ZoneDensity{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	existing, err := s.store.GetZoneDensity(ctx, runID, id)
	if err != nil {
		return ZoneDensity{}, err
	}
	now := s.now()
	updated, err := normalizeZoneDensity(runID, input, now, existing.ID)
	if err != nil {
		return ZoneDensity{}, err
	}
	updated.CreatedBy = existing.CreatedBy
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdateZoneDensity(ctx, updated); err != nil {
		return ZoneDensity{}, err
	}
	return updated, nil
}

// DeleteZoneDensity removes the zone-density report with the given id
// within the run. The run must be 进行中 (400 otherwise); a missing run
// or report is a 404 (the run existence check comes first, so a missing
// run never surfaces as a gate error).
func (s *Service) DeleteZoneDensity(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !zoneDensityWritableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteZoneDensity(ctx, runID, id)
}

// ─── In-memory store ─────────────────────────────────────────────────

// CreateZoneDensity appends the report to the store.
func (s *InMemoryStore) CreateZoneDensity(_ context.Context, density ZoneDensity) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.zoneDensities = append(s.zoneDensities, cloneZoneDensity(density))
	return nil
}

// ListZoneDensities returns the reports of the run matching the filter
// (zone_name exact match) ordered by reported_at DESC, id DESC (the
// newest report first), the total number of matches and the paginated
// page.
func (s *InMemoryStore) ListZoneDensities(_ context.Context, runID string, filter ZoneDensityFilter) ([]ZoneDensity, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]ZoneDensity, 0, len(s.zoneDensities))
	for _, item := range s.zoneDensities {
		if item.RunID != runID {
			continue
		}
		if filter.ZoneName != "" && item.ZoneName != filter.ZoneName {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		// The service always sets reported_at, but the column is
		// nullable; a nil instant sorts as the oldest.
		left := time.Time{}
		if matched[i].ReportedAt != nil {
			left = *matched[i].ReportedAt
		}
		right := time.Time{}
		if matched[j].ReportedAt != nil {
			right = *matched[j].ReportedAt
		}
		if left.Equal(right) {
			return matched[i].ID > matched[j].ID
		}
		return left.After(right)
	})
	total := len(matched)
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]ZoneDensity, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneZoneDensity(item))
	}
	return page, total, nil
}

// GetZoneDensity returns the report with the given id within the run,
// or ErrZoneDensityNotFound (a report of another run is not found as
// well).
func (s *InMemoryStore) GetZoneDensity(_ context.Context, runID, id string) (ZoneDensity, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfZoneDensity(runID, id)
	if index < 0 {
		return ZoneDensity{}, ErrZoneDensityNotFound
	}
	return cloneZoneDensity(s.zoneDensities[index]), nil
}

// UpdateZoneDensity replaces the report with the same id (within the
// same run), or ErrZoneDensityNotFound.
func (s *InMemoryStore) UpdateZoneDensity(_ context.Context, density ZoneDensity) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfZoneDensity(density.RunID, density.ID)
	if index < 0 {
		return ErrZoneDensityNotFound
	}
	s.zoneDensities[index] = cloneZoneDensity(density)
	return nil
}

// DeleteZoneDensity removes the report with the given id within the
// run, or ErrZoneDensityNotFound.
func (s *InMemoryStore) DeleteZoneDensity(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfZoneDensity(runID, id)
	if index < 0 {
		return ErrZoneDensityNotFound
	}
	s.zoneDensities = append(s.zoneDensities[:index], s.zoneDensities[index+1:]...)
	return nil
}

// DeleteZoneDensitiesByRun removes every zone-density report of the
// run (the in-memory counterpart of the DB's ON DELETE CASCADE; the
// uniform cleanup entry the drills service calls through
// SetRunSessionCleaner). Removing no reports is not an error.
func (s *InMemoryStore) DeleteZoneDensitiesByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.zoneDensities[:0]
	for _, item := range s.zoneDensities {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.zoneDensities = kept
	return nil
}

func (s *InMemoryStore) indexOfZoneDensity(runID, id string) int {
	for i, item := range s.zoneDensities {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func cloneZoneDensity(density ZoneDensity) ZoneDensity {
	cloned := density
	if density.ReportedAt != nil {
		reportedAt := *density.ReportedAt
		cloned.ReportedAt = &reportedAt
	}
	return cloned
}
