package drills

import (
	"context"
	"sort"
	"sync"
)

// Store persists the drill objects. The prototype ships the in-memory
// implementation; the interface keeps the service layer independent of
// the storage backend. The cascade rules of the database (steps and
// assessment points on scenario deletion, step records / sim events /
// assessments on run deletion) are implemented at the service layer
// through the cleaner hooks; the store itself only manages the rows.
type Store interface {
	// Scenarios
	CreateScenario(ctx context.Context, scenario Scenario) error
	ListScenarios(ctx context.Context, filter ScenarioFilter) ([]Scenario, int, error)
	GetScenario(ctx context.Context, id string) (Scenario, error)
	UpdateScenario(ctx context.Context, scenario Scenario) error
	DeleteScenario(ctx context.Context, id string) error
	// Steps
	CreateStep(ctx context.Context, step ScenarioStep) error
	ListStepsByScenario(ctx context.Context, scenarioID string) ([]ScenarioStep, error)
	GetStep(ctx context.Context, id string) (ScenarioStep, error)
	UpdateStep(ctx context.Context, step ScenarioStep) error
	DeleteStep(ctx context.Context, id string) error
	DeleteStepsByScenario(ctx context.Context, scenarioID string) error
	// Assessment points
	CreatePoint(ctx context.Context, point AssessmentPoint) error
	ListPointsByScenario(ctx context.Context, scenarioID string) ([]AssessmentPoint, error)
	GetPoint(ctx context.Context, id string) (AssessmentPoint, error)
	UpdatePoint(ctx context.Context, point AssessmentPoint) error
	DeletePoint(ctx context.Context, id string) error
	DeletePointsByScenario(ctx context.Context, scenarioID string) error
	// Runs
	CreateRun(ctx context.Context, run Run) error
	ListRuns(ctx context.Context, filter RunFilter) ([]Run, int, error)
	GetRun(ctx context.Context, id string) (Run, error)
	UpdateRun(ctx context.Context, run Run) error
	DeleteRun(ctx context.Context, id string) error
	// Step records
	UpsertStepRecord(ctx context.Context, record StepRecord) error
	GetStepRecord(ctx context.Context, runID, stepID string) (StepRecord, error)
	ListStepRecordsByRun(ctx context.Context, runID string) ([]StepRecord, error)
	DeleteStepRecord(ctx context.Context, runID, stepID string) error
	DeleteStepRecordsByRun(ctx context.Context, runID string) error
	// Sim events
	CreateSimEvent(ctx context.Context, event SimEvent) error
	ListSimEvents(ctx context.Context, runID string, filter SimEventFilter) ([]SimEvent, int, error)
	GetSimEvent(ctx context.Context, runID, id string) (SimEvent, error)
	UpdateSimEvent(ctx context.Context, event SimEvent) error
	DeleteSimEvent(ctx context.Context, runID, id string) error
	DeleteSimEventsByRun(ctx context.Context, runID string) error
	// Assessments
	UpsertAssessment(ctx context.Context, assessment Assessment) error
	GetAssessment(ctx context.Context, runID, pointID string) (Assessment, error)
	ListAssessmentsByRun(ctx context.Context, runID string) ([]Assessment, error)
	DeleteAssessment(ctx context.Context, runID, pointID string) error
	DeleteAssessmentsByRun(ctx context.Context, runID string) error
}

// InMemoryStore keeps the drill rows in insertion-ordered slices guarded
// by a mutex. It implements Store for the prototype and never touches a
// database; a database-backed store arrives with a later slice. The
// listing methods return the rows in the repository sort order (see the
// individual list comments) and the paginated page.
type InMemoryStore struct {
	mu         sync.Mutex
	scenarios  []Scenario
	steps      []ScenarioStep
	points     []AssessmentPoint
	runs       []Run
	stepRecord []StepRecord
	simEvents  []SimEvent
	assess     []Assessment
}

// NewInMemoryStore returns an empty in-memory drill store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// CreateScenario appends the scenario to the store.
func (s *InMemoryStore) CreateScenario(_ context.Context, scenario Scenario) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.scenarios = append(s.scenarios, cloneScenario(scenario))
	return nil
}

// ListScenarios returns the scenarios matching the filter (category and
// status exact matches) ordered by created_at ASC, id ASC, the total
// number of matches and the paginated page.
func (s *InMemoryStore) ListScenarios(_ context.Context, filter ScenarioFilter) ([]Scenario, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Scenario, 0, len(s.scenarios))
	for _, item := range s.scenarios {
		if filter.Category != "" && item.Category != filter.Category {
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
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]Scenario, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneScenario(item))
	}
	return page, total, nil
}

// GetScenario returns the scenario with the given id, or ErrScenarioNotFound.
func (s *InMemoryStore) GetScenario(_ context.Context, id string) (Scenario, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfScenario(id)
	if index < 0 {
		return Scenario{}, ErrScenarioNotFound
	}
	return cloneScenario(s.scenarios[index]), nil
}

// UpdateScenario replaces the scenario with the same id, or returns
// ErrScenarioNotFound.
func (s *InMemoryStore) UpdateScenario(_ context.Context, scenario Scenario) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfScenario(scenario.ID)
	if index < 0 {
		return ErrScenarioNotFound
	}
	s.scenarios[index] = cloneScenario(scenario)
	return nil
}

// DeleteScenario removes the scenario with the given id, or returns
// ErrScenarioNotFound.
func (s *InMemoryStore) DeleteScenario(_ context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfScenario(id)
	if index < 0 {
		return ErrScenarioNotFound
	}
	s.scenarios = append(s.scenarios[:index], s.scenarios[index+1:]...)
	return nil
}

// CreateStep appends the step to the store.
func (s *InMemoryStore) CreateStep(_ context.Context, step ScenarioStep) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.steps = append(s.steps, cloneStep(step))
	return nil
}

// ListStepsByScenario returns the steps of the scenario ordered by
// sort_order ASC, created_at ASC.
func (s *InMemoryStore) ListStepsByScenario(_ context.Context, scenarioID string) ([]ScenarioStep, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]ScenarioStep, 0, len(s.steps))
	for _, item := range s.steps {
		if item.ScenarioID == scenarioID {
			matched = append(matched, item)
		}
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].SortOrder == matched[j].SortOrder {
			return matched[i].CreatedAt.Before(matched[j].CreatedAt)
		}
		return matched[i].SortOrder < matched[j].SortOrder
	})
	cloned := make([]ScenarioStep, 0, len(matched))
	for _, item := range matched {
		cloned = append(cloned, cloneStep(item))
	}
	return cloned, nil
}

// GetStep returns the step with the given id, or ErrStepNotFound.
func (s *InMemoryStore) GetStep(_ context.Context, id string) (ScenarioStep, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfStep(id)
	if index < 0 {
		return ScenarioStep{}, ErrStepNotFound
	}
	return cloneStep(s.steps[index]), nil
}

// UpdateStep replaces the step with the same id, or returns
// ErrStepNotFound.
func (s *InMemoryStore) UpdateStep(_ context.Context, step ScenarioStep) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfStep(step.ID)
	if index < 0 {
		return ErrStepNotFound
	}
	s.steps[index] = cloneStep(step)
	return nil
}

// DeleteStep removes the step with the given id, or returns
// ErrStepNotFound.
func (s *InMemoryStore) DeleteStep(_ context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfStep(id)
	if index < 0 {
		return ErrStepNotFound
	}
	s.steps = append(s.steps[:index], s.steps[index+1:]...)
	return nil
}

// DeleteStepsByScenario removes every step of the scenario (the
// in-memory counterpart of the DB's ON DELETE CASCADE). Removing no
// steps is not an error.
func (s *InMemoryStore) DeleteStepsByScenario(_ context.Context, scenarioID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.steps[:0]
	for _, item := range s.steps {
		if item.ScenarioID != scenarioID {
			kept = append(kept, item)
		}
	}
	s.steps = kept
	return nil
}

// CreatePoint appends the assessment point to the store.
func (s *InMemoryStore) CreatePoint(_ context.Context, point AssessmentPoint) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.points = append(s.points, clonePoint(point))
	return nil
}

// ListPointsByScenario returns the points of the scenario ordered by
// created_at ASC.
func (s *InMemoryStore) ListPointsByScenario(_ context.Context, scenarioID string) ([]AssessmentPoint, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]AssessmentPoint, 0, len(s.points))
	for _, item := range s.points {
		if item.ScenarioID == scenarioID {
			matched = append(matched, item)
		}
	}
	sort.SliceStable(matched, func(i, j int) bool {
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	cloned := make([]AssessmentPoint, 0, len(matched))
	for _, item := range matched {
		cloned = append(cloned, clonePoint(item))
	}
	return cloned, nil
}

// GetPoint returns the assessment point with the given id, or
// ErrPointNotFound.
func (s *InMemoryStore) GetPoint(_ context.Context, id string) (AssessmentPoint, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfPoint(id)
	if index < 0 {
		return AssessmentPoint{}, ErrPointNotFound
	}
	return clonePoint(s.points[index]), nil
}

// UpdatePoint replaces the assessment point with the same id, or returns
// ErrPointNotFound.
func (s *InMemoryStore) UpdatePoint(_ context.Context, point AssessmentPoint) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfPoint(point.ID)
	if index < 0 {
		return ErrPointNotFound
	}
	s.points[index] = clonePoint(point)
	return nil
}

// DeletePoint removes the assessment point with the given id, or returns
// ErrPointNotFound.
func (s *InMemoryStore) DeletePoint(_ context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfPoint(id)
	if index < 0 {
		return ErrPointNotFound
	}
	s.points = append(s.points[:index], s.points[index+1:]...)
	return nil
}

// DeletePointsByScenario removes every assessment point of the scenario
// (the in-memory counterpart of the DB's ON DELETE CASCADE). Removing no
// points is not an error.
func (s *InMemoryStore) DeletePointsByScenario(_ context.Context, scenarioID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.points[:0]
	for _, item := range s.points {
		if item.ScenarioID != scenarioID {
			kept = append(kept, item)
		}
	}
	s.points = kept
	return nil
}

// CreateRun appends the run to the store.
func (s *InMemoryStore) CreateRun(_ context.Context, run Run) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.runs = append(s.runs, cloneRun(run))
	return nil
}

// ListRuns returns the runs matching the filter (status and scenario_id
// exact matches) ordered by created_at DESC, the total number of matches
// and the paginated page.
func (s *InMemoryStore) ListRuns(_ context.Context, filter RunFilter) ([]Run, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Run, 0, len(s.runs))
	for _, item := range s.runs {
		if filter.Status != "" && item.Status != filter.Status {
			continue
		}
		if filter.ScenarioID != "" && item.ScenarioID != filter.ScenarioID {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		return matched[i].CreatedAt.After(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]Run, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneRun(item))
	}
	return page, total, nil
}

// GetRun returns the run with the given id, or ErrRunNotFound.
func (s *InMemoryStore) GetRun(_ context.Context, id string) (Run, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfRun(id)
	if index < 0 {
		return Run{}, ErrRunNotFound
	}
	return cloneRun(s.runs[index]), nil
}

// UpdateRun replaces the run with the same id, or returns ErrRunNotFound.
func (s *InMemoryStore) UpdateRun(_ context.Context, run Run) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfRun(run.ID)
	if index < 0 {
		return ErrRunNotFound
	}
	s.runs[index] = cloneRun(run)
	return nil
}

// DeleteRun removes the run with the given id, or returns ErrRunNotFound.
func (s *InMemoryStore) DeleteRun(_ context.Context, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfRun(id)
	if index < 0 {
		return ErrRunNotFound
	}
	s.runs = append(s.runs[:index], s.runs[index+1:]...)
	return nil
}

// UpsertStepRecord inserts the record or replaces the record with the
// same (run, step) pair (its id is preserved by the service).
func (s *InMemoryStore) UpsertStepRecord(_ context.Context, record StepRecord) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.stepRecord {
		if item.RunID == record.RunID && item.StepID == record.StepID {
			s.stepRecord[i] = cloneStepRecord(record)
			return nil
		}
	}
	s.stepRecord = append(s.stepRecord, cloneStepRecord(record))
	return nil
}

// GetStepRecord returns the record of the (run, step) pair, or
// ErrStepRecordNotFound.
func (s *InMemoryStore) GetStepRecord(_ context.Context, runID, stepID string) (StepRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfStepRecord(runID, stepID)
	if index < 0 {
		return StepRecord{}, ErrStepRecordNotFound
	}
	return cloneStepRecord(s.stepRecord[index]), nil
}

// ListStepRecordsByRun returns the records of the run ordered by
// created_at ASC, id ASC.
func (s *InMemoryStore) ListStepRecordsByRun(_ context.Context, runID string) ([]StepRecord, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]StepRecord, 0, len(s.stepRecord))
	for _, item := range s.stepRecord {
		if item.RunID == runID {
			matched = append(matched, item)
		}
	}
	sort.SliceStable(matched, func(i, j int) bool {
		if matched[i].CreatedAt.Equal(matched[j].CreatedAt) {
			return matched[i].ID < matched[j].ID
		}
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	cloned := make([]StepRecord, 0, len(matched))
	for _, item := range matched {
		cloned = append(cloned, cloneStepRecord(item))
	}
	return cloned, nil
}

// DeleteStepRecord removes the record of the (run, step) pair, or
// returns ErrStepRecordNotFound.
func (s *InMemoryStore) DeleteStepRecord(_ context.Context, runID, stepID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfStepRecord(runID, stepID)
	if index < 0 {
		return ErrStepRecordNotFound
	}
	s.stepRecord = append(s.stepRecord[:index], s.stepRecord[index+1:]...)
	return nil
}

// DeleteStepRecordsByRun removes every record of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE). Removing no records is not
// an error.
func (s *InMemoryStore) DeleteStepRecordsByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.stepRecord[:0]
	for _, item := range s.stepRecord {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.stepRecord = kept
	return nil
}

// CreateSimEvent appends the event to the store.
func (s *InMemoryStore) CreateSimEvent(_ context.Context, event SimEvent) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.simEvents = append(s.simEvents, cloneSimEvent(event))
	return nil
}

// ListSimEvents returns the events of the run matching the filter
// (event_type and status exact matches) ordered by created_at ASC, the
// total number of matches and the paginated page.
func (s *InMemoryStore) ListSimEvents(_ context.Context, runID string, filter SimEventFilter) ([]SimEvent, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]SimEvent, 0, len(s.simEvents))
	for _, item := range s.simEvents {
		if item.RunID != runID {
			continue
		}
		if filter.EventType != "" && item.EventType != filter.EventType {
			continue
		}
		if filter.Status != "" && item.Status != filter.Status {
			continue
		}
		matched = append(matched, item)
	}
	sort.SliceStable(matched, func(i, j int) bool {
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	total := len(matched)
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]SimEvent, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneSimEvent(item))
	}
	return page, total, nil
}

// GetSimEvent returns the event with the given id within the run, or
// ErrSimEventNotFound (an event of another run is not found as well).
func (s *InMemoryStore) GetSimEvent(_ context.Context, runID, id string) (SimEvent, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfSimEvent(runID, id)
	if index < 0 {
		return SimEvent{}, ErrSimEventNotFound
	}
	return cloneSimEvent(s.simEvents[index]), nil
}

// UpdateSimEvent replaces the event with the same id (within the same
// run), or returns ErrSimEventNotFound.
func (s *InMemoryStore) UpdateSimEvent(_ context.Context, event SimEvent) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfSimEvent(event.RunID, event.ID)
	if index < 0 {
		return ErrSimEventNotFound
	}
	s.simEvents[index] = cloneSimEvent(event)
	return nil
}

// DeleteSimEvent removes the event with the given id within the run, or
// returns ErrSimEventNotFound.
func (s *InMemoryStore) DeleteSimEvent(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfSimEvent(runID, id)
	if index < 0 {
		return ErrSimEventNotFound
	}
	s.simEvents = append(s.simEvents[:index], s.simEvents[index+1:]...)
	return nil
}

// DeleteSimEventsByRun removes every event of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE). Removing no events is not
// an error.
func (s *InMemoryStore) DeleteSimEventsByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.simEvents[:0]
	for _, item := range s.simEvents {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.simEvents = kept
	return nil
}

// UpsertAssessment inserts the assessment or replaces the assessment
// with the same (run, point) pair (its id is preserved by the service).
func (s *InMemoryStore) UpsertAssessment(_ context.Context, assessment Assessment) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.assess {
		if item.RunID == assessment.RunID && item.PointID == assessment.PointID {
			s.assess[i] = cloneAssessment(assessment)
			return nil
		}
	}
	s.assess = append(s.assess, cloneAssessment(assessment))
	return nil
}

// GetAssessment returns the assessment of the (run, point) pair, or
// ErrAssessmentNotFound.
func (s *InMemoryStore) GetAssessment(_ context.Context, runID, pointID string) (Assessment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfAssessment(runID, pointID)
	if index < 0 {
		return Assessment{}, ErrAssessmentNotFound
	}
	return cloneAssessment(s.assess[index]), nil
}

// ListAssessmentsByRun returns the assessments of the run ordered by
// created_at ASC.
func (s *InMemoryStore) ListAssessmentsByRun(_ context.Context, runID string) ([]Assessment, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Assessment, 0, len(s.assess))
	for _, item := range s.assess {
		if item.RunID == runID {
			matched = append(matched, item)
		}
	}
	sort.SliceStable(matched, func(i, j int) bool {
		return matched[i].CreatedAt.Before(matched[j].CreatedAt)
	})
	cloned := make([]Assessment, 0, len(matched))
	for _, item := range matched {
		cloned = append(cloned, cloneAssessment(item))
	}
	return cloned, nil
}

// DeleteAssessment removes the assessment of the (run, point) pair, or
// returns ErrAssessmentNotFound.
func (s *InMemoryStore) DeleteAssessment(_ context.Context, runID, pointID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfAssessment(runID, pointID)
	if index < 0 {
		return ErrAssessmentNotFound
	}
	s.assess = append(s.assess[:index], s.assess[index+1:]...)
	return nil
}

// DeleteAssessmentsByRun removes every assessment of the run (the
// in-memory counterpart of the DB's ON DELETE CASCADE). Removing no
// assessments is not an error.
func (s *InMemoryStore) DeleteAssessmentsByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.assess[:0]
	for _, item := range s.assess {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.assess = kept
	return nil
}

func (s *InMemoryStore) indexOfScenario(id string) int {
	for i, item := range s.scenarios {
		if item.ID == id {
			return i
		}
	}
	return -1
}

func (s *InMemoryStore) indexOfStep(id string) int {
	for i, item := range s.steps {
		if item.ID == id {
			return i
		}
	}
	return -1
}

func (s *InMemoryStore) indexOfPoint(id string) int {
	for i, item := range s.points {
		if item.ID == id {
			return i
		}
	}
	return -1
}

func (s *InMemoryStore) indexOfRun(id string) int {
	for i, item := range s.runs {
		if item.ID == id {
			return i
		}
	}
	return -1
}

func (s *InMemoryStore) indexOfStepRecord(runID, stepID string) int {
	for i, item := range s.stepRecord {
		if item.RunID == runID && item.StepID == stepID {
			return i
		}
	}
	return -1
}

func (s *InMemoryStore) indexOfSimEvent(runID, id string) int {
	for i, item := range s.simEvents {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func (s *InMemoryStore) indexOfAssessment(runID, pointID string) int {
	for i, item := range s.assess {
		if item.RunID == runID && item.PointID == pointID {
			return i
		}
	}
	return -1
}

// paginate computes the page bounds for a list of total items: the page
// starts at offset and holds up to limit items (a negative limit means
// no limit).
func paginate(total, limit, offset int) (start, end int) {
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

func cloneScenario(scenario Scenario) Scenario {
	cloned := scenario
	cloned.Metadata = cloneMap(scenario.Metadata)
	return cloned
}

func cloneStep(step ScenarioStep) ScenarioStep {
	return step
}

func clonePoint(point AssessmentPoint) AssessmentPoint {
	return point
}

func cloneRun(run Run) Run {
	cloned := run
	cloned.Metadata = cloneMap(run.Metadata)
	return cloned
}

func cloneStepRecord(record StepRecord) StepRecord {
	return record
}

func cloneSimEvent(event SimEvent) SimEvent {
	cloned := event
	cloned.Payload = cloneMap(event.Payload)
	return cloned
}

func cloneAssessment(assessment Assessment) Assessment {
	return assessment
}

func cloneMap(source map[string]any) map[string]any {
	cloned := make(map[string]any, len(source))
	for key, value := range source {
		cloned[key] = value
	}
	return cloned
}
