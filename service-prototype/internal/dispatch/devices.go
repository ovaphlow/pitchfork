package dispatch

import (
	"context"
	"errors"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ErrDeviceNotFound is returned when the run exists but the device
// status report does not. It maps to HTTP 404 in the routing layer.
var ErrDeviceNotFound = errors.New("device not found")

// DeviceType is the kind of a simulated venue device (设备类型): the
// categories shown in the device-monitoring area of the command-center
// big screen. It is required (no default).
type DeviceType string

const (
	DeviceTypePowerSupply DeviceType = "供配电"
	DeviceTypeFire        DeviceType = "消防"
	DeviceTypeSecurity    DeviceType = "安防"
	DeviceTypeElevator    DeviceType = "电梯"
	DeviceTypeHVAC        DeviceType = "空调通风"
	DeviceTypeBroadcast   DeviceType = "广播通信"
	DeviceTypeOther       DeviceType = "其他"
)

var validDeviceTypes = []DeviceType{
	DeviceTypePowerSupply,
	DeviceTypeFire,
	DeviceTypeSecurity,
	DeviceTypeElevator,
	DeviceTypeHVAC,
	DeviceTypeBroadcast,
	DeviceTypeOther,
}

// Valid reports whether deviceType is one of the allowed values.
func (deviceType DeviceType) Valid() bool {
	for _, candidate := range validDeviceTypes {
		if deviceType == candidate {
			return true
		}
	}
	return false
}

// DeviceStatus is the running status of a venue device (运行状态):
// 正常/告警/离线. 告警 and 离线 devices are highlighted on the
// command-center big screen.
type DeviceStatus string

const (
	DeviceStatusNormal  DeviceStatus = "正常"
	DeviceStatusWarning DeviceStatus = "告警"
	DeviceStatusOffline DeviceStatus = "离线"
)

// DefaultDeviceStatus is applied when a request omits the status field.
const DefaultDeviceStatus = DeviceStatusNormal

var validDeviceStatuses = []DeviceStatus{
	DeviceStatusNormal,
	DeviceStatusWarning,
	DeviceStatusOffline,
}

// Valid reports whether status is one of the allowed device statuses.
func (status DeviceStatus) Valid() bool {
	for _, candidate := range validDeviceStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// Device is the running-status report (设备运行状态上报) of a simulated
// venue device within a drill run: the required device name
// (device_name), the device kind (device_type) and the running status
// (status, defaulting to 正常) with an optional fault description
// (note). The reports drive the device-monitoring area of the
// command-center big screen, where 告警/离线 devices are highlighted;
// the feeds of the other systems (IoT device reporting) appear as
// simulated data. The id and the timestamps are server-generated;
// run_id comes from the route path.
type Device struct {
	ID         string       `json:"id"`
	RunID      string       `json:"run_id"`
	DeviceName string       `json:"device_name"`
	DeviceType DeviceType   `json:"device_type"`
	Status     DeviceStatus `json:"status"`
	Note       string       `json:"note"`
	CreatedBy  string       `json:"created_by"`
	CreatedAt  time.Time    `json:"created_at"`
	UpdatedAt  time.Time    `json:"updated_at"`
}

// DeviceInput carries the client-supplied fields of a device status
// report. id, run_id and the timestamps are never part of the input:
// they are decided by the route path and the service. device_name is
// required (empty or whitespace is rejected); device_type is required
// and must be one of 供配电/消防/安防/电梯/空调通风/广播通信/其他; status
// defaults to 正常 and must be one of 正常/告警/离线; note passes through
// and defaults to an empty string; created_by passes through at
// creation (the prototype has no auth context) and is preserved on
// update.
type DeviceInput struct {
	DeviceName string
	DeviceType DeviceType
	Status     DeviceStatus
	Note       string
	CreatedBy  string
}

// DeviceFilter selects device status reports for listing. Empty enum
// values match everything; Limit and Offset paginate the matching set.
type DeviceFilter struct {
	DeviceType DeviceType
	Status     DeviceStatus
	Limit      int
	Offset     int
}

// normalizeDevice validates client input and produces a complete
// device status report. device_name is required (empty or whitespace
// is rejected); device_type is required and must be one of the allowed
// values; status defaults to 正常 and must be one of 正常/告警/离线;
// note and created_by pass through. The run and the timestamps come
// from the caller.
func normalizeDevice(runID string, input DeviceInput, now time.Time, id string) (Device, error) {
	deviceName := strings.TrimSpace(input.DeviceName)
	if deviceName == "" {
		return Device{}, &ValidationError{Message: "device_name required"}
	}
	deviceType := input.DeviceType
	if deviceType == "" {
		return Device{}, &ValidationError{Message: "device_type required"}
	}
	if !deviceType.Valid() {
		return Device{}, &ValidationError{Message: fmt.Sprintf("invalid device_type: %q", input.DeviceType)}
	}
	status := input.Status
	if status == "" {
		status = DefaultDeviceStatus
	}
	if !status.Valid() {
		return Device{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	return Device{
		ID:         id,
		RunID:      runID,
		DeviceName: deviceName,
		DeviceType: deviceType,
		Status:     status,
		Note:       input.Note,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}

// deviceWritableRun reports whether a run in the given status may
// receive device writes: like the dispatch orders, messages and
// zone-density reports, the device status reports are only writable
// while the run is 进行中.
func deviceWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress
}

// CreateDevice records a device status report within the run and
// returns the created row. The run must be 进行中 (400 otherwise); a
// missing run is ErrRunNotFound (404).
func (s *Service) CreateDevice(ctx context.Context, runID string, input DeviceInput) (Device, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Device{}, err
	}
	if !deviceWritableRun(run.Status) {
		return Device{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	device, err := normalizeDevice(runID, input, now, s.newID())
	if err != nil {
		return Device{}, err
	}
	if err := s.store.CreateDevice(ctx, device); err != nil {
		return Device{}, err
	}
	return device, nil
}

// GetDevice returns the device status report with the given id within
// the run. A missing run is ErrRunNotFound; a missing report is
// ErrDeviceNotFound. GET is not subject to the write gate: a run in
// 已完成/已终止 with reports still answers 200.
func (s *Service) GetDevice(ctx context.Context, runID, id string) (Device, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Device{}, err
	}
	return s.store.GetDevice(ctx, runID, id)
}

// ListDevices returns the device status reports of the run matching the
// filter (device_type and status exact matches, ordered by created_at
// ASC, id ASC — the oldest report first) and the total number of
// matches. A missing run is ErrRunNotFound; GET is not subject to the
// write gate.
func (s *Service) ListDevices(ctx context.Context, runID string, filter DeviceFilter) ([]Device, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListDevices(ctx, runID, filter)
}

// UpdateDevice updates the device status report in place (full
// replacement of device_name/device_type/status/note: device_name and
// device_type are required, status defaults to 正常 and note to an
// empty string — omitted fields reset to their defaults). The run must
// be 进行中 (400 otherwise); a missing run or report is a 404 (the run
// existence check comes first). id, run_id, created_at and created_by
// are preserved.
func (s *Service) UpdateDevice(ctx context.Context, runID, id string, input DeviceInput) (Device, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Device{}, err
	}
	if !deviceWritableRun(run.Status) {
		return Device{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	existing, err := s.store.GetDevice(ctx, runID, id)
	if err != nil {
		return Device{}, err
	}
	now := s.now()
	updated, err := normalizeDevice(runID, input, now, existing.ID)
	if err != nil {
		return Device{}, err
	}
	updated.CreatedBy = existing.CreatedBy
	updated.CreatedAt = existing.CreatedAt
	if err := s.store.UpdateDevice(ctx, updated); err != nil {
		return Device{}, err
	}
	return updated, nil
}

// DeleteDevice removes the device status report with the given id
// within the run. The run must be 进行中 (400 otherwise); a missing run
// or report is a 404 (the run existence check comes first, so a
// missing run never surfaces as a gate error).
func (s *Service) DeleteDevice(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !deviceWritableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteDevice(ctx, runID, id)
}

// ─── In-memory store ─────────────────────────────────────────────────

// CreateDevice appends the report to the store.
func (s *InMemoryStore) CreateDevice(_ context.Context, device Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.devices = append(s.devices, device)
	return nil
}

// ListDevices returns the reports of the run matching the filter
// (device_type and status exact matches) ordered by created_at ASC,
// id ASC (the oldest report first), the total number of matches and
// the paginated page.
func (s *InMemoryStore) ListDevices(_ context.Context, runID string, filter DeviceFilter) ([]Device, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Device, 0, len(s.devices))
	for _, item := range s.devices {
		if item.RunID != runID {
			continue
		}
		if filter.DeviceType != "" && item.DeviceType != filter.DeviceType {
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
	page := make([]Device, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, item)
	}
	return page, total, nil
}

// GetDevice returns the report with the given id within the run, or
// ErrDeviceNotFound (a report of another run is not found as well).
func (s *InMemoryStore) GetDevice(_ context.Context, runID, id string) (Device, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfDevice(runID, id)
	if index < 0 {
		return Device{}, ErrDeviceNotFound
	}
	return s.devices[index], nil
}

// UpdateDevice replaces the report with the same id (within the same
// run), or ErrDeviceNotFound.
func (s *InMemoryStore) UpdateDevice(_ context.Context, device Device) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfDevice(device.RunID, device.ID)
	if index < 0 {
		return ErrDeviceNotFound
	}
	s.devices[index] = device
	return nil
}

// DeleteDevice removes the report with the given id within the run, or
// ErrDeviceNotFound.
func (s *InMemoryStore) DeleteDevice(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfDevice(runID, id)
	if index < 0 {
		return ErrDeviceNotFound
	}
	s.devices = append(s.devices[:index], s.devices[index+1:]...)
	return nil
}

// DeleteDevicesByRun removes every device status report of the run
// (the in-memory counterpart of the DB's ON DELETE CASCADE; the
// uniform cleanup entry the drills service calls through
// SetRunSessionCleaner). Removing no reports is not an error.
func (s *InMemoryStore) DeleteDevicesByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.devices[:0]
	for _, item := range s.devices {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.devices = kept
	return nil
}

func (s *InMemoryStore) indexOfDevice(runID, id string) int {
	for i, item := range s.devices {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}
