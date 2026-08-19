package dispatch

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ErrDepartmentNotFound is returned when the run exists but the
// linkage-disposal report of the (run, department) pair does not. It
// maps to HTTP 404 in the routing layer.
var ErrDepartmentNotFound = errors.New("department report not found")

// Department is a linkage department (联动部门) joining the joint
// disposal of a drill run. The five kinds are a fixed business enum —
// the prototype simulates the joint disposal without connecting real
// systems.
type Department string

const (
	DepartmentFire   Department = "消防"
	DepartmentPolice Department = "公安"
	DepartmentHealth Department = "卫健"
	DepartmentVenue  Department = "场馆应急组"
	DepartmentOther  Department = "其他"
)

var validDepartments = []Department{
	DepartmentFire,
	DepartmentPolice,
	DepartmentHealth,
	DepartmentVenue,
	DepartmentOther,
}

// Valid reports whether department is one of the allowed linkage
// departments.
func (department Department) Valid() bool {
	for _, candidate := range validDepartments {
		if department == candidate {
			return true
		}
	}
	return false
}

// DepartmentStatus is the linkage-disposal progress of a department
// report. The state machine is 未响应→已响应→已到位→处置中→已完成 and the
// service only allows adjacent forward transitions (same-status no-ops
// are legal; backward moves and skips are rejected).
type DepartmentStatus string

const (
	DepartmentStatusNotResponded DepartmentStatus = "未响应"
	DepartmentStatusResponded    DepartmentStatus = "已响应"
	DepartmentStatusArrived      DepartmentStatus = "已到位"
	DepartmentStatusHandling     DepartmentStatus = "处置中"
	DepartmentStatusCompleted    DepartmentStatus = "已完成"
)

// DefaultDepartmentStatus is applied when a request omits the status
// field.
const DefaultDepartmentStatus = DepartmentStatusNotResponded

var validDepartmentStatuses = []DepartmentStatus{
	DepartmentStatusNotResponded,
	DepartmentStatusResponded,
	DepartmentStatusArrived,
	DepartmentStatusHandling,
	DepartmentStatusCompleted,
}

// Valid reports whether status is one of the allowed department
// statuses.
func (status DepartmentStatus) Valid() bool {
	for _, candidate := range validDepartmentStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// adjacentDepartmentTransition reports whether the status change is
// exactly the next step of the 未响应→已响应→已到位→处置中→已完成 chain.
// Same-status requests are no-ops (not migrations) and handled by the
// caller.
func adjacentDepartmentTransition(from, to DepartmentStatus) bool {
	switch from {
	case DepartmentStatusNotResponded:
		return to == DepartmentStatusResponded
	case DepartmentStatusResponded:
		return to == DepartmentStatusArrived
	case DepartmentStatusArrived:
		return to == DepartmentStatusHandling
	case DepartmentStatusHandling:
		return to == DepartmentStatusCompleted
	default:
		return false
	}
}

// DepartmentReport is the linkage-disposal record (部门联动处置记录) of
// one linkage department of one drill run: the disposal progress
// (status through the 5-step state machine), the disposal description
// (note) and the optional arrival time (arrived_at, passed through by
// the client and null by default). At most one report exists per
// (run, department) pair; the id and the timestamps are
// server-generated, run_id and department come from the route path.
type DepartmentReport struct {
	ID         string           `json:"id"`
	RunID      string           `json:"run_id"`
	Department Department       `json:"department"`
	Status     DepartmentStatus `json:"status"`
	Note       string           `json:"note"`
	ArrivedAt  *time.Time       `json:"arrived_at"`
	CreatedBy  string           `json:"created_by"`
	CreatedAt  time.Time        `json:"created_at"`
	UpdatedAt  time.Time        `json:"updated_at"`
}

// DepartmentReportInput carries the client-supplied fields of a
// department-report upsert. id, run_id and department are never part of
// the input: they are decided by the route path and the service. There
// are no required fields: status defaults to 未响应 (an explicit value
// must be 未响应 at creation; on update an omitted status keeps the
// current value and an explicit one must be an adjacent forward
// transition); note defaults to an empty string; arrived_at is optional
// (null when omitted); created_by passes through (the prototype has no
// auth context).
type DepartmentReportInput struct {
	Status    DepartmentStatus
	Note      string
	ArrivedAt *time.Time
	CreatedBy string
}

// DepartmentFilter selects department reports for listing. Empty enum
// values match everything; Limit and Offset paginate the matching set.
type DepartmentFilter struct {
	Department Department
	Status     DepartmentStatus
	Limit      int
	Offset     int
}

// normalizeDepartmentReport validates client input and produces a
// complete department report. The department must be one of the five
// linkage departments; status defaults to 未响应 and must be one of the
// allowed values (the caller applies the creation-only and
// adjacent-transition rules). note/arrived_at/created_by pass through.
// The run and the timestamps come from the caller.
func normalizeDepartmentReport(runID string, department Department, input DepartmentReportInput, now time.Time, id string) (DepartmentReport, error) {
	if !department.Valid() {
		return DepartmentReport{}, &ValidationError{Message: fmt.Sprintf("invalid department: %q", department)}
	}
	status := input.Status
	if status == "" {
		status = DefaultDepartmentStatus
	}
	if !status.Valid() {
		return DepartmentReport{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	return DepartmentReport{
		ID:         id,
		RunID:      runID,
		Department: department,
		Status:     status,
		Note:       input.Note,
		ArrivedAt:  input.ArrivedAt,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}

// departmentWritableRun reports whether a run in the given status may
// receive department-report writes: like the dispatch orders, the
// linkage reports are only writable while the run is 进行中.
func departmentWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress
}

// UpsertDepartment records the linkage-disposal report of the
// (run, department) pair and returns the row. The first PUT of a pair
// creates the report (status defaults to 未响应; an explicit status must
// be 未响应 at creation, anything else is a skipped transition); later
// PUTs update it in place — the id and created_at are preserved,
// updated_at is refreshed, an omitted status keeps the current value
// (never resetting the progress) while an explicit one must be an
// adjacent forward transition of 未响应→已响应→已到位→处置中→已完成
// (same-status no-ops are legal), and note/arrived_at/created_by follow
// full replacement semantics (omitted fields reset to their defaults).
// The run must be 进行中 (400 otherwise); a missing run is
// ErrRunNotFound (404).
func (s *Service) UpsertDepartment(ctx context.Context, runID string, department Department, input DepartmentReportInput) (DepartmentReport, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return DepartmentReport{}, err
	}
	if !departmentWritableRun(run.Status) {
		return DepartmentReport{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	existing, err := s.store.GetDepartment(ctx, runID, department)
	if err != nil && !errors.Is(err, ErrDepartmentNotFound) {
		return DepartmentReport{}, err
	}
	if errors.Is(err, ErrDepartmentNotFound) {
		report, err := normalizeDepartmentReport(runID, department, input, now, s.newID())
		if err != nil {
			return DepartmentReport{}, err
		}
		if input.Status != "" && input.Status != DefaultDepartmentStatus {
			return DepartmentReport{}, &ValidationError{
				Message: "status must be 未响应 at creation",
			}
		}
		if err := s.store.UpsertDepartment(ctx, report); err != nil {
			return DepartmentReport{}, err
		}
		return report, nil
	}
	report, err := normalizeDepartmentReport(runID, department, input, now, existing.ID)
	if err != nil {
		return DepartmentReport{}, err
	}
	if input.Status == "" {
		// An omitted status never resets the disposal progress.
		report.Status = existing.Status
	} else if report.Status != existing.Status {
		if !adjacentDepartmentTransition(existing.Status, report.Status) {
			return DepartmentReport{}, &ValidationError{
				Message: fmt.Sprintf("invalid status transition: %q -> %q", existing.Status, report.Status),
			}
		}
	}
	report.CreatedAt = existing.CreatedAt
	if err := s.store.UpsertDepartment(ctx, report); err != nil {
		return DepartmentReport{}, err
	}
	return report, nil
}

// ListDepartments returns the department reports of the run matching
// the filter (department and status exact matches, ordered by
// created_at ASC, id ASC) and the total number of matches. A missing
// run is ErrRunNotFound; GET is not subject to the write gate.
func (s *Service) ListDepartments(ctx context.Context, runID string, filter DepartmentFilter) ([]DepartmentReport, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListDepartments(ctx, runID, filter)
}

// DeleteDepartment removes the linkage-disposal report of the
// (run, department) pair. A missing run or report is a 404; the checks
// follow the pinned order — run existence, then report existence, then
// the write gate (a run in 未开始/已完成/已终止 is a ValidationError
// 400). The report can be created again by a later PUT (upsert
// semantics).
func (s *Service) DeleteDepartment(ctx context.Context, runID string, department Department) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !department.Valid() {
		return &ValidationError{Message: fmt.Sprintf("invalid department: %q", department)}
	}
	if _, err := s.store.GetDepartment(ctx, runID, department); err != nil {
		return err
	}
	if !departmentWritableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteDepartment(ctx, runID, department)
}

// ─── In-memory store ─────────────────────────────────────────────────

// UpsertDepartment inserts the report or replaces the report with the
// same (run_id, department) pair.
func (s *InMemoryStore) UpsertDepartment(_ context.Context, report DepartmentReport) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.departments {
		if item.RunID == report.RunID && item.Department == report.Department {
			s.departments[i] = cloneDepartmentReport(report)
			return nil
		}
	}
	s.departments = append(s.departments, cloneDepartmentReport(report))
	return nil
}

// ListDepartments returns the reports of the run matching the filter
// (department and status exact matches) ordered by created_at ASC,
// id ASC, the total number of matches and the paginated page.
func (s *InMemoryStore) ListDepartments(_ context.Context, runID string, filter DepartmentFilter) ([]DepartmentReport, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]DepartmentReport, 0, len(s.departments))
	for _, item := range s.departments {
		if item.RunID != runID {
			continue
		}
		if filter.Department != "" && item.Department != filter.Department {
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
	page := make([]DepartmentReport, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneDepartmentReport(item))
	}
	return page, total, nil
}

// GetDepartment returns the report of the (run, department) pair, or
// ErrDepartmentNotFound.
func (s *InMemoryStore) GetDepartment(_ context.Context, runID string, department Department) (DepartmentReport, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfDepartment(runID, department)
	if index < 0 {
		return DepartmentReport{}, ErrDepartmentNotFound
	}
	return cloneDepartmentReport(s.departments[index]), nil
}

// DeleteDepartment removes the report of the (run, department) pair,
// or ErrDepartmentNotFound.
func (s *InMemoryStore) DeleteDepartment(_ context.Context, runID string, department Department) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfDepartment(runID, department)
	if index < 0 {
		return ErrDepartmentNotFound
	}
	s.departments = append(s.departments[:index], s.departments[index+1:]...)
	return nil
}

// DeleteDepartmentsByRun removes every department report of the run
// (the in-memory counterpart of the DB's ON DELETE CASCADE; the uniform
// cleanup entry the drills service calls through SetRunSessionCleaner).
// Removing no reports is not an error.
func (s *InMemoryStore) DeleteDepartmentsByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.departments[:0]
	for _, item := range s.departments {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.departments = kept
	return nil
}

func (s *InMemoryStore) indexOfDepartment(runID string, department Department) int {
	for i, item := range s.departments {
		if item.RunID == runID && item.Department == department {
			return i
		}
	}
	return -1
}

func cloneDepartmentReport(report DepartmentReport) DepartmentReport {
	cloned := report
	if report.ArrivedAt != nil {
		arrivedAt := *report.ArrivedAt
		cloned.ArrivedAt = &arrivedAt
	}
	return cloned
}
