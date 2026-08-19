// Package drills implements the scenario-simulation-drill business
// objects of prototyped (module 2 of the museum safety platform): the
// drill scenario templates with their steps and assessment points, the
// drill runs with their step records, simulated events and assessments,
// and the built-in seed data. The package defines the models with their
// enum validation, a store interface with an in-memory implementation,
// and the service layer. It never touches a database; a PostgreSQL-backed
// store can be swapped in later behind the same interface.
package drills

import (
	"errors"
	"fmt"
	"strings"
	"time"
)

// ErrScenarioNotFound is returned when a drill scenario id does not
// exist. It maps to HTTP 404 in the routing layer.
var ErrScenarioNotFound = errors.New("scenario not found")

// ErrStepNotFound is returned when a scenario step id does not exist or
// does not belong to the scenario of the run being executed. It maps to
// HTTP 404 in the routing layer.
var ErrStepNotFound = errors.New("step not found")

// ErrPointNotFound is returned when an assessment point id does not
// exist or does not belong to the scenario of the run being assessed.
// It maps to HTTP 404 in the routing layer.
var ErrPointNotFound = errors.New("assessment point not found")

// ErrRunNotFound is returned when a drill run id does not exist. It maps
// to HTTP 404 in the routing layer.
var ErrRunNotFound = errors.New("drill not found")

// ErrStepRecordNotFound is returned when the step record of a (run,
// step) pair does not exist. It maps to HTTP 404 in the routing layer.
var ErrStepRecordNotFound = errors.New("step record not found")

// ErrSimEventNotFound is returned when a simulated event does not exist
// (or does not belong to the run in the route path). It maps to HTTP 404
// in the routing layer.
var ErrSimEventNotFound = errors.New("sim event not found")

// ErrAssessmentNotFound is returned when the assessment of a (run,
// point) pair does not exist. It maps to HTTP 404 in the routing layer.
var ErrAssessmentNotFound = errors.New("assessment not found")

// ValidationError describes a request that violates the drills business
// rules (missing required fields, invalid enum values, illegal state
// transitions or writes on a run that does not allow them). It maps to
// HTTP 400 in the routing layer.
type ValidationError struct{ Message string }

func (e *ValidationError) Error() string { return e.Message }

// Category is the scenario category (场景分类). The four built-in
// categories mirror the required drill scenarios; each maps to exactly
// one simulated event type (see validEventTypeForCategory).
type Category string

const (
	CategoryPassengerFlow Category = "大客流聚集"
	CategoryPowerOutage   Category = "停电与基础设施"
	CategoryFire          Category = "火灾"
	CategoryWeather       Category = "气象灾害"
)

var validCategories = []Category{
	CategoryPassengerFlow,
	CategoryPowerOutage,
	CategoryFire,
	CategoryWeather,
}

// Valid reports whether category is one of the allowed category values.
func (category Category) Valid() bool {
	for _, candidate := range validCategories {
		if category == candidate {
			return true
		}
	}
	return false
}

// ScenarioStatus is the lifecycle state of a scenario template.
type ScenarioStatus string

const (
	ScenarioStatusEnabled  ScenarioStatus = "启用"
	ScenarioStatusDisabled ScenarioStatus = "停用"
)

// DefaultScenarioStatus is applied when a request omits the status field.
const DefaultScenarioStatus = ScenarioStatusEnabled

var validScenarioStatuses = []ScenarioStatus{ScenarioStatusEnabled, ScenarioStatusDisabled}

// Valid reports whether status is one of the allowed status values.
func (status ScenarioStatus) Valid() bool {
	for _, candidate := range validScenarioStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// RunStatus is the lifecycle state of a drill run. The state machine is
// enforced by the service: 未开始 -> 进行中 -> 已完成/已终止; every other
// transition is rejected with 400.
type RunStatus string

const (
	RunStatusNotStarted RunStatus = "未开始"
	RunStatusInProgress RunStatus = "进行中"
	RunStatusCompleted  RunStatus = "已完成"
	RunStatusTerminated RunStatus = "已终止"
)

// DefaultRunStatus is applied when a request omits the status field.
const DefaultRunStatus = RunStatusNotStarted

var validRunStatuses = []RunStatus{
	RunStatusNotStarted,
	RunStatusInProgress,
	RunStatusCompleted,
	RunStatusTerminated,
}

// Valid reports whether status is one of the allowed status values.
func (status RunStatus) Valid() bool {
	for _, candidate := range validRunStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// StepRecordStatus is the execution state of one step within a drill run.
type StepRecordStatus string

const (
	StepRecordPending  StepRecordStatus = "待执行"
	StepRecordExecuted StepRecordStatus = "已执行"
	StepRecordSkipped  StepRecordStatus = "跳过"
)

// DefaultStepRecordStatus is applied when a request omits the status
// field of a step record.
const DefaultStepRecordStatus = StepRecordPending

var validStepRecordStatuses = []StepRecordStatus{
	StepRecordPending,
	StepRecordExecuted,
	StepRecordSkipped,
}

// Valid reports whether status is one of the allowed status values.
func (status StepRecordStatus) Valid() bool {
	for _, candidate := range validStepRecordStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// SimEventType is the kind of a simulated event. Hardware sensors and
// external system feeds cannot be connected for real; the simulated
// events present them as a UI demonstration.
type SimEventType string

const (
	SimEventFlowOverflow   SimEventType = "客流密度超阈值"
	SimEventPowerAlarm     SimEventType = "供配电异常报警"
	SimEventSmokeAlarm     SimEventType = "烟感探测器触发"
	SimEventWeatherWarning SimEventType = "气象预警接收"
	SimEventOther          SimEventType = "其他"
)

var validSimEventTypes = []SimEventType{
	SimEventFlowOverflow,
	SimEventPowerAlarm,
	SimEventSmokeAlarm,
	SimEventWeatherWarning,
	SimEventOther,
}

// Valid reports whether eventType is one of the allowed event types.
func (eventType SimEventType) Valid() bool {
	for _, candidate := range validSimEventTypes {
		if eventType == candidate {
			return true
		}
	}
	return false
}

// SimEventStatus is the handling state of a simulated event.
type SimEventStatus string

const (
	SimEventTriggered SimEventStatus = "已触发"
	SimEventHandled   SimEventStatus = "已处置"
)

// DefaultSimEventStatus is applied when a request omits the status field.
const DefaultSimEventStatus = SimEventTriggered

var validSimEventStatuses = []SimEventStatus{SimEventTriggered, SimEventHandled}

// Valid reports whether status is one of the allowed status values.
func (status SimEventStatus) Valid() bool {
	for _, candidate := range validSimEventStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// validEventTypeForCategory reports whether the event type may be raised
// for a scenario of the given category. Every category maps to exactly
// one event type; 其他 is allowed for any scenario.
func validEventTypeForCategory(category Category, eventType SimEventType) bool {
	if eventType == SimEventOther {
		return true
	}
	switch category {
	case CategoryPassengerFlow:
		return eventType == SimEventFlowOverflow
	case CategoryPowerOutage:
		return eventType == SimEventPowerAlarm
	case CategoryFire:
		return eventType == SimEventSmokeAlarm
	case CategoryWeather:
		return eventType == SimEventWeatherWarning
	}
	return false
}

// Scenario is a drill scenario template (演练场景模板): a name, a
// category, a simulated background, a lifecycle status and the ordered
// steps / assessment points defined by the template. Metadata follows
// the repository JSONB extension-field convention and is always present
// (an omitted request field is stored and echoed as an empty object).
type Scenario struct {
	ID        string         `json:"id"`
	Name      string         `json:"name"`
	Category  Category       `json:"category"`
	Background string        `json:"background"`
	Status    ScenarioStatus `json:"status"`
	Metadata  map[string]any `json:"metadata"`
	CreatedBy string         `json:"created_by"`
	CreatedAt time.Time      `json:"created_at"`
	UpdatedAt time.Time      `json:"updated_at"`
}

// ScenarioInput carries the client-supplied fields shared by scenario
// create and update.
type ScenarioInput struct {
	Name       string
	Category   Category
	Background string
	Status     ScenarioStatus
	Metadata   map[string]any
	CreatedBy  string
}

// ScenarioFilter selects scenarios for listing. Empty enum values match
// everything; Limit and Offset paginate the matching set.
type ScenarioFilter struct {
	Category Category
	Status   ScenarioStatus
	Limit    int
	Offset   int
}

// ScenarioStep is one ordered step (演练流程步骤) of a scenario template.
type ScenarioStep struct {
	ID          string    `json:"id"`
	ScenarioID  string    `json:"scenario_id"`
	SortOrder   int       `json:"sort_order"`
	Title       string    `json:"title"`
	Description string    `json:"description"`
	CreatedBy   string    `json:"created_by"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// StepInput carries the client-supplied fields shared by step create and
// update. The owning scenario comes from the route path.
type StepInput struct {
	SortOrder   int
	Title       string
	Description string
	CreatedBy   string
}

// AssessmentPoint is one assessment point (考核要点模板) of a scenario
// template.
type AssessmentPoint struct {
	ID          string    `json:"id"`
	ScenarioID  string    `json:"scenario_id"`
	Title       string    `json:"title"`
	Description string    `json:"description"`
	CreatedBy   string    `json:"created_by"`
	CreatedAt   time.Time `json:"created_at"`
	UpdatedAt   time.Time `json:"updated_at"`
}

// PointInput carries the client-supplied fields shared by assessment
// point create and update. The owning scenario comes from the route path.
type PointInput struct {
	Title       string
	Description string
	CreatedBy   string
}

// Run is a drill run (演练任务): the execution of one scenario template.
// The status and the timestamps are managed by the service (the state
// machine start/complete/terminate); a PUT never changes them.
type Run struct {
	ID          string     `json:"id"`
	ScenarioID  string     `json:"scenario_id"`
	Title       string     `json:"title"`
	Status      RunStatus  `json:"status"`
	StartedAt   *time.Time `json:"started_at"`
	CompletedAt *time.Time `json:"completed_at"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string     `json:"created_by"`
	CreatedAt   time.Time  `json:"created_at"`
	UpdatedAt   time.Time  `json:"updated_at"`
}

// RunInput carries the client-supplied fields shared by run create and
// update. Status, started_at and completed_at are server-managed and
// never part of the input (a PUT body may carry them; they are ignored).
type RunInput struct {
	ScenarioID string
	Title      string
	Metadata   map[string]any
	CreatedBy  string
}

// RunFilter selects runs for listing. Empty enum values match everything;
// Limit and Offset paginate the matching set.
type RunFilter struct {
	Status     RunStatus
	ScenarioID string
	Limit      int
	Offset     int
}

// StepRecord is the execution record (步骤执行记录) of one step within a
// drill run. At most one record exists per (run, step) pair; it is
// created by the first PUT and updated in place by later PUTs.
type StepRecord struct {
	ID          string           `json:"id"`
	RunID       string           `json:"run_id"`
	StepID      string           `json:"step_id"`
	Status      StepRecordStatus `json:"status"`
	ActionNote  string           `json:"action_note"`
	PerformedBy string           `json:"performed_by"`
	PerformedAt *time.Time       `json:"performed_at"`
	CreatedBy   string           `json:"created_by"`
	CreatedAt   time.Time        `json:"created_at"`
	UpdatedAt   time.Time        `json:"updated_at"`
}

// StepRecordInput carries the client-supplied fields of a step record
// upsert. performed_at is optional and client-supplied (null when
// omitted).
type StepRecordInput struct {
	Status      StepRecordStatus
	ActionNote  string
	PerformedBy string
	PerformedAt *time.Time
	CreatedBy   string
}

// SimEvent is a simulated event (模拟事件演示) of a drill run: the UI
// stand-in for hardware sensors and external system feeds (crowd-density
// alert, power alarm, smoke detector, weather warning). The payload
// carries the mock data; triggered_at is set by the service at creation
// and handled_at is managed by the service together with the status.
type SimEvent struct {
	ID          string         `json:"id"`
	RunID       string         `json:"run_id"`
	EventType   SimEventType   `json:"event_type"`
	Payload     map[string]any `json:"payload"`
	Status      SimEventStatus `json:"status"`
	TriggeredAt *time.Time     `json:"triggered_at"`
	HandledAt   *time.Time     `json:"handled_at"`
	CreatedBy   string         `json:"created_by"`
	CreatedAt   time.Time      `json:"created_at"`
	UpdatedAt   time.Time      `json:"updated_at"`
}

// SimEventInput carries the client-supplied fields of a sim event
// creation. triggered_at is server-managed.
type SimEventInput struct {
	EventType SimEventType
	Payload   map[string]any
	Status    SimEventStatus
	CreatedBy string
}

// SimEventUpdate carries the client-supplied fields of a sim event
// update. Empty strings mean "keep the current value" (partial update);
// an omitted payload keeps the current payload.
type SimEventUpdate struct {
	EventType SimEventType
	Payload   map[string]any
	Status    SimEventStatus
	HasPayload bool
}

// SimEventFilter selects sim events for listing. Empty enum values match
// everything; Limit and Offset paginate the matching set.
type SimEventFilter struct {
	EventType SimEventType
	Status    SimEventStatus
	Limit     int
	Offset    int
}

// Assessment is the assessment (演练考核评估) of one assessment point
// within a drill run: a score from 0 to 100 and an optional comment. At
// most one assessment exists per (run, point) pair; it is created by the
// first PUT and updated in place by later PUTs.
type Assessment struct {
	ID        string    `json:"id"`
	RunID     string    `json:"run_id"`
	PointID   string    `json:"point_id"`
	Score     int       `json:"score"`
	Comment   string    `json:"comment"`
	CreatedBy string    `json:"created_by"`
	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// AssessmentInput carries the client-supplied fields of an assessment
// upsert. Score is required and must be between 0 and 100; comment
// defaults to an empty string.
type AssessmentInput struct {
	Score     int
	Comment   string
	CreatedBy string
}

// ListFilter paginates a sub-collection listing (steps, points, step
// records, assessments).
type ListFilter struct {
	Limit  int
	Offset int
}

// normalizeScenario validates client input and produces a complete
// scenario. Name, category and background are required; every enum must
// be one of the allowed values; status defaults to 启用 and metadata to
// an empty object. The timestamps and the server-generated id come from
// the caller.
func normalizeScenario(input ScenarioInput, now time.Time, id string) (Scenario, error) {
	name := strings.TrimSpace(input.Name)
	if name == "" {
		return Scenario{}, &ValidationError{Message: "name required"}
	}
	if !input.Category.Valid() {
		return Scenario{}, &ValidationError{Message: fmt.Sprintf("invalid category: %q", input.Category)}
	}
	background := strings.TrimSpace(input.Background)
	if background == "" {
		return Scenario{}, &ValidationError{Message: "background required"}
	}
	status := input.Status
	if status == "" {
		status = DefaultScenarioStatus
	}
	if !status.Valid() {
		return Scenario{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Scenario{
		ID:         id,
		Name:       name,
		Category:   input.Category,
		Background: background,
		Status:     status,
		Metadata:   metadata,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}

// normalizeStep validates client input and produces a complete step.
// Title is required and sort_order must not be negative; sort_order
// defaults to 0 and description to an empty string. The owning scenario
// and the timestamps come from the caller.
func normalizeStep(scenarioID string, input StepInput, now time.Time, id string) (ScenarioStep, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return ScenarioStep{}, &ValidationError{Message: "title required"}
	}
	if input.SortOrder < 0 {
		return ScenarioStep{}, &ValidationError{Message: "sort_order must not be negative"}
	}
	return ScenarioStep{
		ID:          id,
		ScenarioID:  scenarioID,
		SortOrder:   input.SortOrder,
		Title:       title,
		Description: input.Description,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// normalizePoint validates client input and produces a complete
// assessment point. Title is required; description defaults to an empty
// string. The owning scenario and the timestamps come from the caller.
func normalizePoint(scenarioID string, input PointInput, now time.Time, id string) (AssessmentPoint, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return AssessmentPoint{}, &ValidationError{Message: "title required"}
	}
	return AssessmentPoint{
		ID:          id,
		ScenarioID:  scenarioID,
		Title:       title,
		Description: input.Description,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// normalizeRun validates client input and produces a complete run.
// ScenarioID and title are required; status defaults to 未开始 and
// metadata to an empty object. The timestamps and the server-generated
// id come from the caller.
func normalizeRun(input RunInput, now time.Time, id string) (Run, error) {
	scenarioID := strings.TrimSpace(input.ScenarioID)
	if scenarioID == "" {
		return Run{}, &ValidationError{Message: "scenario_id required"}
	}
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return Run{}, &ValidationError{Message: "title required"}
	}
	metadata := input.Metadata
	if metadata == nil {
		metadata = map[string]any{}
	}
	return Run{
		ID:         id,
		ScenarioID: scenarioID,
		Title:      title,
		Status:     DefaultRunStatus,
		Metadata:   metadata,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}

// normalizeStepRecord validates client input and produces a complete
// step record. Status defaults to 待执行; action_note and performed_by
// default to empty strings. The run, the step and the timestamps come
// from the caller.
func normalizeStepRecord(runID, stepID string, input StepRecordInput, now time.Time, id string) (StepRecord, error) {
	status := input.Status
	if status == "" {
		status = DefaultStepRecordStatus
	}
	if !status.Valid() {
		return StepRecord{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	return StepRecord{
		ID:          id,
		RunID:       runID,
		StepID:      stepID,
		Status:      status,
		ActionNote:  input.ActionNote,
		PerformedBy: input.PerformedBy,
		PerformedAt: input.PerformedAt,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// normalizeSimEvent validates client input and produces a complete sim
// event. Event type is required, must be one of the allowed values and
// must match the scenario category (or be 其他); payload defaults to an
// empty object; status defaults to 已触发. triggered_at is set by the
// caller at creation.
func normalizeSimEvent(runID string, input SimEventInput, now time.Time, id string) (SimEvent, error) {
	if !input.EventType.Valid() {
		return SimEvent{}, &ValidationError{Message: fmt.Sprintf("invalid event_type: %q", input.EventType)}
	}
	status := input.Status
	if status == "" {
		status = DefaultSimEventStatus
	}
	if !status.Valid() {
		return SimEvent{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	payload := input.Payload
	if payload == nil {
		payload = map[string]any{}
	}
	triggeredAt := now
	return SimEvent{
		ID:          id,
		RunID:       runID,
		EventType:   input.EventType,
		Payload:     payload,
		Status:      status,
		TriggeredAt: &triggeredAt,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// normalizeAssessment validates client input and produces a complete
// assessment. Score must be between 0 and 100; comment defaults to an
// empty string. The run, the point and the timestamps come from the
// caller.
func normalizeAssessment(runID, pointID string, input AssessmentInput, now time.Time, id string) (Assessment, error) {
	if input.Score < 0 || input.Score > 100 {
		return Assessment{}, &ValidationError{Message: "score must be between 0 and 100"}
	}
	return Assessment{
		ID:        id,
		RunID:     runID,
		PointID:   pointID,
		Score:     input.Score,
		Comment:   input.Comment,
		CreatedBy: input.CreatedBy,
		CreatedAt: now,
		UpdatedAt: now,
	}, nil
}
