package drills

import (
	"context"
	"errors"
	"fmt"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
)

// Service applies the drills business rules (validation, defaults, the
// run state machine, the writable-state checks, cascade deletion and
// server-generated ids and timestamps) on top of the store.
type Service struct {
	store             Store
	scenarioChildren  ScenarioChildCleaner    // nil until wired: cascade deletion of steps and points
	runChildren       RunChildCleaner         // nil until wired: cascade deletion of child records
	sessionChildren   RunSessionCleaner       // nil until wired: cascade deletion of dispatch sessions/orders/reports
	evaluationScores  EvaluationScoreCleaner  // nil until wired: cascade deletion of evaluation scores
	evaluationReports EvaluationReportCleaner // nil until wired: cascade deletion of evaluation reports
	opinionChildren   RunOpinionCleaner       // nil until wired: cascade deletion of opinion objects
	now               func() time.Time
	newID             func() string
}

// NewService builds a service over the given store. The server-generated
// id is a 26-character Crockford Base32 ULID.
func NewService(store Store) *Service {
	return &Service{store: store, now: time.Now, newID: ulid.New}
}

// ScenarioChildCleaner removes the steps and the assessment points of a
// scenario. Deleting a scenario cascades to them through this injected
// dependency (the database carries ON DELETE CASCADE; the in-memory
// store implements the same rule here). Wired at the composition root,
// never by the routing layer.
type ScenarioChildCleaner interface {
	DeleteStepsByScenario(ctx context.Context, scenarioID string) error
	DeletePointsByScenario(ctx context.Context, scenarioID string) error
}

// SetScenarioChildCleaner wires cascade deletion: from then on
// DeleteScenario removes the steps and the assessment points of the
// scenario together with the scenario itself. Calling it is optional;
// without a cleaner DeleteScenario behaves exactly as before.
func (s *Service) SetScenarioChildCleaner(cleaner ScenarioChildCleaner) {
	s.scenarioChildren = cleaner
}

// RunChildCleaner removes the step records, the sim events and the
// assessments of a run. Deleting a run cascades to them through this
// injected dependency (the database carries ON DELETE CASCADE; the
// in-memory store implements the same rule here). Wired at the
// composition root, never by the routing layer.
type RunChildCleaner interface {
	DeleteStepRecordsByRun(ctx context.Context, runID string) error
	DeleteSimEventsByRun(ctx context.Context, runID string) error
	DeleteAssessmentsByRun(ctx context.Context, runID string) error
}

// SetRunChildCleaner wires cascade deletion: from then on DeleteRun
// removes the step records, the sim events and the assessments of the
// run together with the run itself. Calling it is optional; without a
// cleaner DeleteRun behaves exactly as before.
func (s *Service) SetRunChildCleaner(cleaner RunChildCleaner) {
	s.runChildren = cleaner
}

// RunSessionCleaner removes the dispatch children of a run: the command
// sessions, the dispatch orders, the department reports, the dispatch
// messages, the zone-density reports and the device status reports.
// Deleting a run cascades to them through this injected dependency (the
// database carries ON DELETE CASCADE; the in-memory dispatch store
// implements the same rule here through DeleteSessionsByRun,
// DeleteOrdersByRun, DeleteDepartmentsByRun, DeleteMessagesByRun,
// DeleteZoneDensitiesByRun and DeleteDevicesByRun). Wired at the
// composition root, never by the routing layer.
type RunSessionCleaner interface {
	DeleteSessionsByRun(ctx context.Context, runID string) error
	DeleteOrdersByRun(ctx context.Context, runID string) error
	DeleteDepartmentsByRun(ctx context.Context, runID string) error
	DeleteMessagesByRun(ctx context.Context, runID string) error
	DeleteZoneDensitiesByRun(ctx context.Context, runID string) error
	DeleteDevicesByRun(ctx context.Context, runID string) error
}

// SetRunSessionCleaner wires cascade deletion: from then on DeleteRun
// removes the dispatch command sessions, the dispatch orders, the
// department reports, the dispatch messages, the zone-density reports
// and the device status reports of the run together with the run
// itself. Calling it is optional; without a cleaner DeleteRun behaves
// exactly as before.
func (s *Service) SetRunSessionCleaner(cleaner RunSessionCleaner) {
	s.sessionChildren = cleaner
}

// EvaluationScoreCleaner removes the evaluation scores of a run.
// Deleting a run cascades to them through this injected dependency (the
// database carries ON DELETE CASCADE; the in-memory evaluation score
// store implements the same rule here through DeleteScoresByRun). Wired
// at the composition root, never by the routing layer.
type EvaluationScoreCleaner interface {
	DeleteScoresByRun(ctx context.Context, runID string) error
}

// SetEvaluationScoreCleaner wires cascade deletion: from then on
// DeleteRun removes the evaluation scores of the run together with the
// run itself. Calling it is optional; without a cleaner DeleteRun
// behaves exactly as before.
func (s *Service) SetEvaluationScoreCleaner(cleaner EvaluationScoreCleaner) {
	s.evaluationScores = cleaner
}

// EvaluationReportCleaner removes the evaluation reports of a run.
// Deleting a run cascades to them through this injected dependency (the
// database carries ON DELETE CASCADE; the in-memory evaluation report
// store implements the same rule here through DeleteReportsByRun).
// Wired at the composition root, never by the routing layer.
type EvaluationReportCleaner interface {
	DeleteReportsByRun(ctx context.Context, runID string) error
}

// SetEvaluationReportCleaner wires cascade deletion: from then on
// DeleteRun removes the evaluation reports of the run together with the
// run itself. Calling it is optional; without a cleaner DeleteRun
// behaves exactly as before.
func (s *Service) SetEvaluationReportCleaner(cleaner EvaluationReportCleaner) {
	s.evaluationReports = cleaner
}

// RunOpinionCleaner removes the opinion objects of a run (module 5 of
// the public-opinion-response training): the opinion event
// configuration (later opinion object cards append their cleanup to
// the same entry). Deleting a run cascades to them through this
// injected dependency (the database carries ON DELETE CASCADE; the
// in-memory opinion store implements the same rule here through
// DeleteByRun). Wired at the composition root, never by the routing
// layer.
type RunOpinionCleaner interface {
	DeleteByRun(ctx context.Context, runID string) error
}

// SetOpinionCleaner wires cascade deletion: from then on DeleteRun
// removes the opinion objects of the run (the opinion event
// configuration, and whatever later opinion object cards append to the
// cleanup entry) together with the run itself. Calling it is optional;
// without a cleaner DeleteRun behaves exactly as before.
func (s *Service) SetOpinionCleaner(cleaner RunOpinionCleaner) {
	s.opinionChildren = cleaner
}

// ─── Scenarios ───────────────────────────────────────────────────────

// CreateScenario validates the input, assigns a server-generated id and
// the timestamps, and stores the new scenario template.
func (s *Service) CreateScenario(ctx context.Context, input ScenarioInput) (Scenario, error) {
	scenario, err := normalizeScenario(input, s.now(), s.newID())
	if err != nil {
		return Scenario{}, err
	}
	if err := s.store.CreateScenario(ctx, scenario); err != nil {
		return Scenario{}, err
	}
	return scenario, nil
}

// ListScenarios returns the scenarios matching the filter and the total
// number of matches (before pagination).
func (s *Service) ListScenarios(ctx context.Context, filter ScenarioFilter) ([]Scenario, int, error) {
	return s.store.ListScenarios(ctx, filter)
}

// GetScenario returns the scenario with the given id, or
// ErrScenarioNotFound.
func (s *Service) GetScenario(ctx context.Context, id string) (Scenario, error) {
	return s.store.GetScenario(ctx, id)
}

// UpdateScenario validates the input with the same rules as
// CreateScenario, replaces the scenario with the given id and returns
// the updated record. The original creation timestamp is preserved; the
// update timestamp is refreshed.
func (s *Service) UpdateScenario(ctx context.Context, id string, input ScenarioInput) (Scenario, error) {
	existing, err := s.store.GetScenario(ctx, id)
	if err != nil {
		return Scenario{}, err
	}
	updated, err := normalizeScenario(input, s.now(), id)
	if err != nil {
		return Scenario{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdateScenario(ctx, updated); err != nil {
		return Scenario{}, err
	}
	return updated, nil
}

// DeleteScenario removes the scenario with the given id, or returns
// ErrScenarioNotFound. When a scenario child cleaner is wired, the steps
// and the assessment points of the scenario are removed together with
// the scenario (cascade delete). The children are removed first so a
// failing cleanup cannot leave the scenario deleted while its steps and
// points survive.
func (s *Service) DeleteScenario(ctx context.Context, id string) error {
	// Verify the scenario exists first: a missing scenario must still
	// answer ErrScenarioNotFound without touching any children.
	if _, err := s.store.GetScenario(ctx, id); err != nil {
		return err
	}
	if s.scenarioChildren != nil {
		if err := s.scenarioChildren.DeleteStepsByScenario(ctx, id); err != nil {
			return err
		}
		if err := s.scenarioChildren.DeletePointsByScenario(ctx, id); err != nil {
			return err
		}
	}
	return s.store.DeleteScenario(ctx, id)
}

// ─── Steps ───────────────────────────────────────────────────────────

// CreateStep creates a step within the scenario with the given id. A
// missing scenario is a 404.
func (s *Service) CreateStep(ctx context.Context, scenarioID string, input StepInput) (ScenarioStep, error) {
	if _, err := s.store.GetScenario(ctx, scenarioID); err != nil {
		return ScenarioStep{}, err
	}
	step, err := normalizeStep(scenarioID, input, s.now(), s.newID())
	if err != nil {
		return ScenarioStep{}, err
	}
	if err := s.store.CreateStep(ctx, step); err != nil {
		return ScenarioStep{}, err
	}
	return step, nil
}

// ListSteps returns the steps of the scenario (ordered by sort_order ASC,
// created_at ASC) and the total number of matches. A missing scenario is
// a 404.
func (s *Service) ListSteps(ctx context.Context, scenarioID string, filter ListFilter) ([]ScenarioStep, int, error) {
	if _, err := s.store.GetScenario(ctx, scenarioID); err != nil {
		return nil, 0, err
	}
	all, err := s.store.ListStepsByScenario(ctx, scenarioID)
	if err != nil {
		return nil, 0, err
	}
	start, end := paginate(len(all), filter.Limit, filter.Offset)
	return all[start:end], len(all), nil
}

// GetStep returns the step with the given id, or ErrStepNotFound.
func (s *Service) GetStep(ctx context.Context, id string) (ScenarioStep, error) {
	return s.store.GetStep(ctx, id)
}

// UpdateStep validates the input with the same rules as CreateStep and
// replaces the step with the given id. The original creation timestamp
// is preserved; the update timestamp is refreshed.
func (s *Service) UpdateStep(ctx context.Context, id string, input StepInput) (ScenarioStep, error) {
	existing, err := s.store.GetStep(ctx, id)
	if err != nil {
		return ScenarioStep{}, err
	}
	updated, err := normalizeStep(existing.ScenarioID, input, s.now(), id)
	if err != nil {
		return ScenarioStep{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdateStep(ctx, updated); err != nil {
		return ScenarioStep{}, err
	}
	return updated, nil
}

// DeleteStep removes the step with the given id, or returns
// ErrStepNotFound.
func (s *Service) DeleteStep(ctx context.Context, id string) error {
	return s.store.DeleteStep(ctx, id)
}

// ─── Assessment points ───────────────────────────────────────────────

// CreatePoint creates an assessment point within the scenario with the
// given id. A missing scenario is a 404.
func (s *Service) CreatePoint(ctx context.Context, scenarioID string, input PointInput) (AssessmentPoint, error) {
	if _, err := s.store.GetScenario(ctx, scenarioID); err != nil {
		return AssessmentPoint{}, err
	}
	point, err := normalizePoint(scenarioID, input, s.now(), s.newID())
	if err != nil {
		return AssessmentPoint{}, err
	}
	if err := s.store.CreatePoint(ctx, point); err != nil {
		return AssessmentPoint{}, err
	}
	return point, nil
}

// ListPoints returns the assessment points of the scenario (ordered by
// created_at ASC) and the total number of matches. A missing scenario is
// a 404.
func (s *Service) ListPoints(ctx context.Context, scenarioID string, filter ListFilter) ([]AssessmentPoint, int, error) {
	if _, err := s.store.GetScenario(ctx, scenarioID); err != nil {
		return nil, 0, err
	}
	all, err := s.store.ListPointsByScenario(ctx, scenarioID)
	if err != nil {
		return nil, 0, err
	}
	start, end := paginate(len(all), filter.Limit, filter.Offset)
	return all[start:end], len(all), nil
}

// GetPoint returns the assessment point with the given id, or
// ErrPointNotFound.
func (s *Service) GetPoint(ctx context.Context, id string) (AssessmentPoint, error) {
	return s.store.GetPoint(ctx, id)
}

// UpdatePoint validates the input with the same rules as CreatePoint and
// replaces the point with the given id. The original creation timestamp
// is preserved; the update timestamp is refreshed.
func (s *Service) UpdatePoint(ctx context.Context, id string, input PointInput) (AssessmentPoint, error) {
	existing, err := s.store.GetPoint(ctx, id)
	if err != nil {
		return AssessmentPoint{}, err
	}
	updated, err := normalizePoint(existing.ScenarioID, input, s.now(), id)
	if err != nil {
		return AssessmentPoint{}, err
	}
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdatePoint(ctx, updated); err != nil {
		return AssessmentPoint{}, err
	}
	return updated, nil
}

// DeletePoint removes the assessment point with the given id, or returns
// ErrPointNotFound.
func (s *Service) DeletePoint(ctx context.Context, id string) error {
	return s.store.DeletePoint(ctx, id)
}

// ─── Runs ────────────────────────────────────────────────────────────

// CreateRun validates the input (the scenario must exist, otherwise
// 404), assigns a server-generated id and the timestamps, and stores the
// new run in 未开始.
func (s *Service) CreateRun(ctx context.Context, input RunInput) (Run, error) {
	run, err := normalizeRun(input, s.now(), s.newID())
	if err != nil {
		return Run{}, err
	}
	if _, err := s.store.GetScenario(ctx, run.ScenarioID); err != nil {
		return Run{}, err
	}
	if err := s.store.CreateRun(ctx, run); err != nil {
		return Run{}, err
	}
	return run, nil
}

// ListRuns returns the runs matching the filter (status and scenario_id,
// ordered by created_at DESC) and the total number of matches.
func (s *Service) ListRuns(ctx context.Context, filter RunFilter) ([]Run, int, error) {
	return s.store.ListRuns(ctx, filter)
}

// GetRun returns the run with the given id, or ErrRunNotFound.
func (s *Service) GetRun(ctx context.Context, id string) (Run, error) {
	return s.store.GetRun(ctx, id)
}

// UpdateRun validates the input with the same rules as CreateRun and
// replaces the run with the given id. The status and the started_at /
// completed_at timestamps are server-managed: they are preserved from
// the existing record no matter what the request body carries. The
// original creation timestamp is preserved; the update timestamp is
// refreshed.
func (s *Service) UpdateRun(ctx context.Context, id string, input RunInput) (Run, error) {
	existing, err := s.store.GetRun(ctx, id)
	if err != nil {
		return Run{}, err
	}
	updated, err := normalizeRun(input, s.now(), id)
	if err != nil {
		return Run{}, err
	}
	if _, err := s.store.GetScenario(ctx, updated.ScenarioID); err != nil {
		return Run{}, err
	}
	updated.Status = existing.Status
	updated.StartedAt = existing.StartedAt
	updated.CompletedAt = existing.CompletedAt
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdateRun(ctx, updated); err != nil {
		return Run{}, err
	}
	return updated, nil
}

// DeleteRun removes the run with the given id, or returns ErrRunNotFound.
// When a run child cleaner is wired, the step records, the sim events
// and the assessments of the run are removed together with the run
// (cascade delete); when a run session cleaner is wired, the dispatch
// command sessions, the dispatch orders, the department reports, the
// dispatch messages, the zone-density reports and the device status
// reports of the run are removed as well; when an evaluation-score
// cleaner is wired, the evaluation scores of the run are removed too;
// when an evaluation-report cleaner is wired, the evaluation report of
// the run is removed too; when an opinion cleaner is wired, the
// opinion objects of the run (the opinion event configuration) are
// removed as well. The children are removed first so a failing cleanup
// cannot leave the run deleted while its children survive.
func (s *Service) DeleteRun(ctx context.Context, id string) error {
	// Verify the run exists first: a missing run must still answer
	// ErrRunNotFound without touching any children.
	if _, err := s.store.GetRun(ctx, id); err != nil {
		return err
	}
	if s.runChildren != nil {
		if err := s.runChildren.DeleteStepRecordsByRun(ctx, id); err != nil {
			return err
		}
		if err := s.runChildren.DeleteSimEventsByRun(ctx, id); err != nil {
			return err
		}
		if err := s.runChildren.DeleteAssessmentsByRun(ctx, id); err != nil {
			return err
		}
	}
	if s.sessionChildren != nil {
		if err := s.sessionChildren.DeleteSessionsByRun(ctx, id); err != nil {
			return err
		}
		if err := s.sessionChildren.DeleteOrdersByRun(ctx, id); err != nil {
			return err
		}
		if err := s.sessionChildren.DeleteDepartmentsByRun(ctx, id); err != nil {
			return err
		}
		if err := s.sessionChildren.DeleteMessagesByRun(ctx, id); err != nil {
			return err
		}
		if err := s.sessionChildren.DeleteZoneDensitiesByRun(ctx, id); err != nil {
			return err
		}
		if err := s.sessionChildren.DeleteDevicesByRun(ctx, id); err != nil {
			return err
		}
	}
	if s.evaluationScores != nil {
		if err := s.evaluationScores.DeleteScoresByRun(ctx, id); err != nil {
			return err
		}
	}
	if s.evaluationReports != nil {
		if err := s.evaluationReports.DeleteReportsByRun(ctx, id); err != nil {
			return err
		}
	}
	if s.opinionChildren != nil {
		if err := s.opinionChildren.DeleteByRun(ctx, id); err != nil {
			return err
		}
	}
	return s.store.DeleteRun(ctx, id)
}

// StartRun moves the run from 未开始 to 进行中 and records started_at.
// Any other starting state is an illegal transition (400).
func (s *Service) StartRun(ctx context.Context, id string) (Run, error) {
	return s.transition(ctx, id, RunStatusInProgress)
}

// CompleteRun moves the run from 进行中 to 已完成 and records
// completed_at. Any other starting state is an illegal transition (400).
func (s *Service) CompleteRun(ctx context.Context, id string) (Run, error) {
	return s.transition(ctx, id, RunStatusCompleted)
}

// TerminateRun moves the run from 进行中 to 已终止. Any other starting
// state is an illegal transition (400).
func (s *Service) TerminateRun(ctx context.Context, id string) (Run, error) {
	return s.transition(ctx, id, RunStatusTerminated)
}

// transition applies one step of the run state machine
// (未开始 -> 进行中 -> 已完成/已终止) and updates the server-managed
// timestamps. A transition that does not match the machine is a
// ValidationError (400); a missing run is ErrRunNotFound (404).
func (s *Service) transition(ctx context.Context, id string, target RunStatus) (Run, error) {
	run, err := s.store.GetRun(ctx, id)
	if err != nil {
		return Run{}, err
	}
	switch {
	case target == RunStatusInProgress && run.Status == RunStatusNotStarted:
		now := s.now()
		run.Status = RunStatusInProgress
		run.StartedAt = &now
	case target == RunStatusCompleted && run.Status == RunStatusInProgress:
		now := s.now()
		run.Status = RunStatusCompleted
		run.CompletedAt = &now
	case target == RunStatusTerminated && run.Status == RunStatusInProgress:
		run.Status = RunStatusTerminated
	default:
		return Run{}, &ValidationError{
			Message: "illegal run status transition: " + string(run.Status) + " -> " + string(target),
		}
	}
	run.UpdatedAt = s.now()
	if err := s.store.UpdateRun(ctx, run); err != nil {
		return Run{}, err
	}
	return run, nil
}

// ─── Step records ────────────────────────────────────────────────────

// UpsertStepRecord records the execution of one step of one run and
// returns the updated row. The first PUT of a (run, step) pair creates
// the row; later PUTs update it in place (the id and created_at are
// preserved). The run must be 进行中 (400 otherwise); the step must exist
// and belong to the scenario of the run (404 otherwise).
func (s *Service) UpsertStepRecord(ctx context.Context, runID, stepID string, input StepRecordInput) (StepRecord, error) {
	run, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress})
	if err != nil {
		return StepRecord{}, err
	}
	step, err := s.store.GetStep(ctx, stepID)
	if err != nil {
		return StepRecord{}, err
	}
	if step.ScenarioID != run.ScenarioID {
		return StepRecord{}, ErrStepNotFound
	}
	now := s.now()
	existing, err := s.store.GetStepRecord(ctx, runID, stepID)
	if err != nil && !errors.Is(err, ErrStepRecordNotFound) {
		return StepRecord{}, err
	}
	if errors.Is(err, ErrStepRecordNotFound) {
		record, err := normalizeStepRecord(runID, stepID, input, now, s.newID())
		if err != nil {
			return StepRecord{}, err
		}
		if err := s.store.UpsertStepRecord(ctx, record); err != nil {
			return StepRecord{}, err
		}
		return record, nil
	}
	record, err := normalizeStepRecord(runID, stepID, input, now, existing.ID)
	if err != nil {
		return StepRecord{}, err
	}
	record.CreatedAt = existing.CreatedAt
	if err := s.store.UpsertStepRecord(ctx, record); err != nil {
		return StepRecord{}, err
	}
	return record, nil
}

// GetStepRecord returns the step record of the (run, step) pair. A
// missing run is ErrRunNotFound; a missing record is
// ErrStepRecordNotFound.
func (s *Service) GetStepRecord(ctx context.Context, runID, stepID string) (StepRecord, error) {
	if _, err := s.store.GetRun(ctx, runID); err != nil {
		return StepRecord{}, err
	}
	return s.store.GetStepRecord(ctx, runID, stepID)
}

// ListStepRecords returns the step records of the run (ordered by
// created_at ASC, id ASC) and the total number of matches. A missing run
// is a 404.
func (s *Service) ListStepRecords(ctx context.Context, runID string, filter ListFilter) ([]StepRecord, int, error) {
	if _, err := s.store.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	all, err := s.store.ListStepRecordsByRun(ctx, runID)
	if err != nil {
		return nil, 0, err
	}
	start, end := paginate(len(all), filter.Limit, filter.Offset)
	return all[start:end], len(all), nil
}

// DeleteStepRecord removes the step record of the (run, step) pair. The
// run must be 进行中 (400 otherwise); a missing record is a 404.
func (s *Service) DeleteStepRecord(ctx context.Context, runID, stepID string) error {
	if _, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress}); err != nil {
		return err
	}
	return s.store.DeleteStepRecord(ctx, runID, stepID)
}

// ─── Sim events ──────────────────────────────────────────────────────

// CreateSimEvent raises a simulated event within the run: the UI stand-in
// for a hardware sensor or an external system feed. The run must be
// 进行中 (400 otherwise); the event type must match the scenario category
// of the run (or be 其他), otherwise 400. triggered_at is set by the
// service; a status of 已处置 at creation also records handled_at.
func (s *Service) CreateSimEvent(ctx context.Context, runID string, input SimEventInput) (SimEvent, error) {
	run, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress})
	if err != nil {
		return SimEvent{}, err
	}
	if !validEventTypeForCategory(s.categoryOf(ctx, run), input.EventType) {
		return SimEvent{}, &ValidationError{
			Message: fmt.Sprintf("invalid event_type %q for scenario category %q", input.EventType, s.categoryOf(ctx, run)),
		}
	}
	now := s.now()
	event, err := normalizeSimEvent(runID, input, now, s.newID())
	if err != nil {
		return SimEvent{}, err
	}
	if event.Status == SimEventHandled {
		event.HandledAt = &now
	}
	if err := s.store.CreateSimEvent(ctx, event); err != nil {
		return SimEvent{}, err
	}
	return event, nil
}

// GetSimEvent returns the sim event with the given id within the run, or
// ErrSimEventNotFound.
func (s *Service) GetSimEvent(ctx context.Context, runID, id string) (SimEvent, error) {
	return s.store.GetSimEvent(ctx, runID, id)
}

// ListSimEvents returns the sim events of the run matching the filter
// (event_type and status, ordered by created_at ASC) and the total
// number of matches. A missing run is a 404.
func (s *Service) ListSimEvents(ctx context.Context, runID string, filter SimEventFilter) ([]SimEvent, int, error) {
	if _, err := s.store.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListSimEvents(ctx, runID, filter)
}

// UpdateSimEvent updates the sim event in place (partial update: omitted
// fields keep their current values; the payload, when provided, must be
// a JSON object). The run must be 进行中 (400 otherwise). The status
// transition to 已处置 sets handled_at; switching back to 已触发 clears
// it. triggered_at and created_at are preserved.
func (s *Service) UpdateSimEvent(ctx context.Context, runID, id string, update SimEventUpdate) (SimEvent, error) {
	run, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress})
	if err != nil {
		return SimEvent{}, err
	}
	event, err := s.store.GetSimEvent(ctx, runID, id)
	if err != nil {
		return SimEvent{}, err
	}
	if update.EventType != "" {
		if !update.EventType.Valid() {
			return SimEvent{}, &ValidationError{Message: fmt.Sprintf("invalid event_type: %q", update.EventType)}
		}
		if !validEventTypeForCategory(s.categoryOf(ctx, run), update.EventType) {
			return SimEvent{}, &ValidationError{
				Message: fmt.Sprintf("invalid event_type %q for scenario category %q", update.EventType, s.categoryOf(ctx, run)),
			}
		}
		event.EventType = update.EventType
	}
	if update.HasPayload {
		event.Payload = update.Payload
	}
	if update.Status != "" {
		if !update.Status.Valid() {
			return SimEvent{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", update.Status)}
		}
		now := s.now()
		switch update.Status {
		case SimEventHandled:
			if event.HandledAt == nil {
				event.HandledAt = &now
			}
		case SimEventTriggered:
			event.HandledAt = nil
		}
		event.Status = update.Status
	}
	event.UpdatedAt = s.now()
	if err := s.store.UpdateSimEvent(ctx, event); err != nil {
		return SimEvent{}, err
	}
	return event, nil
}

// DeleteSimEvent removes the sim event with the given id within the run.
// The run must be 进行中 (400 otherwise); a missing event is a 404.
func (s *Service) DeleteSimEvent(ctx context.Context, runID, id string) error {
	if _, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress}); err != nil {
		return err
	}
	return s.store.DeleteSimEvent(ctx, runID, id)
}

// ─── Assessments ─────────────────────────────────────────────────────

// UpsertAssessment records the assessment of one assessment point of one
// run and returns the updated row. The first PUT of a (run, point) pair
// creates the row; later PUTs update it in place (the id and created_at
// are preserved). The run must be 进行中 or 已完成 (400 otherwise); the
// point must exist and belong to the scenario of the run (404
// otherwise); the score must be between 0 and 100.
func (s *Service) UpsertAssessment(ctx context.Context, runID, pointID string, input AssessmentInput) (Assessment, error) {
	run, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress, RunStatusCompleted})
	if err != nil {
		return Assessment{}, err
	}
	point, err := s.store.GetPoint(ctx, pointID)
	if err != nil {
		return Assessment{}, err
	}
	if point.ScenarioID != run.ScenarioID {
		return Assessment{}, ErrPointNotFound
	}
	now := s.now()
	existing, err := s.store.GetAssessment(ctx, runID, pointID)
	if err != nil && !errors.Is(err, ErrAssessmentNotFound) {
		return Assessment{}, err
	}
	if errors.Is(err, ErrAssessmentNotFound) {
		assessment, err := normalizeAssessment(runID, pointID, input, now, s.newID())
		if err != nil {
			return Assessment{}, err
		}
		if err := s.store.UpsertAssessment(ctx, assessment); err != nil {
			return Assessment{}, err
		}
		return assessment, nil
	}
	assessment, err := normalizeAssessment(runID, pointID, input, now, existing.ID)
	if err != nil {
		return Assessment{}, err
	}
	assessment.CreatedAt = existing.CreatedAt
	if err := s.store.UpsertAssessment(ctx, assessment); err != nil {
		return Assessment{}, err
	}
	return assessment, nil
}

// GetAssessment returns the assessment of the (run, point) pair. A
// missing run is ErrRunNotFound; a missing record is
// ErrAssessmentNotFound.
func (s *Service) GetAssessment(ctx context.Context, runID, pointID string) (Assessment, error) {
	if _, err := s.store.GetRun(ctx, runID); err != nil {
		return Assessment{}, err
	}
	return s.store.GetAssessment(ctx, runID, pointID)
}

// ListAssessments returns the assessments of the run (ordered by
// created_at ASC) and the total number of matches. A missing run is a
// 404.
func (s *Service) ListAssessments(ctx context.Context, runID string, filter ListFilter) ([]Assessment, int, error) {
	if _, err := s.store.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	all, err := s.store.ListAssessmentsByRun(ctx, runID)
	if err != nil {
		return nil, 0, err
	}
	start, end := paginate(len(all), filter.Limit, filter.Offset)
	return all[start:end], len(all), nil
}

// DeleteAssessment removes the assessment of the (run, point) pair. The
// run must be 进行中 or 已完成 (400 otherwise); a missing assessment is a
// 404.
func (s *Service) DeleteAssessment(ctx context.Context, runID, pointID string) error {
	if _, err := s.requireWritableRun(ctx, runID, []RunStatus{RunStatusInProgress, RunStatusCompleted}); err != nil {
		return err
	}
	return s.store.DeleteAssessment(ctx, runID, pointID)
}

// requireWritableRun loads the run and checks that its status is one of
// the writable statuses. A missing run maps to ErrRunNotFound; a run in
// any other status is a ValidationError (400).
func (s *Service) requireWritableRun(ctx context.Context, runID string, writable []RunStatus) (Run, error) {
	run, err := s.store.GetRun(ctx, runID)
	if err != nil {
		return Run{}, err
	}
	for _, status := range writable {
		if run.Status == status {
			return run, nil
		}
	}
	return Run{}, &ValidationError{
		Message: "run status " + string(run.Status) + " does not allow this operation",
	}
}

// categoryOf resolves the category of the scenario the run executes.
// The scenario reference of a run is validated at creation, so a missing
// scenario here is an internal inconsistency; the category is returned
// empty in that case.
func (s *Service) categoryOf(ctx context.Context, run Run) Category {
	scenario, err := s.store.GetScenario(ctx, run.ScenarioID)
	if err != nil {
		return ""
	}
	return scenario.Category
}
