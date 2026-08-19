package dispatch

import (
	"context"
	"sync"
)

// Store persists the dispatch command sessions, the dispatch orders,
// the dispatch department reports, the dispatch messages, the
// dispatch zone densities and the dispatch device status reports
// (module 3 of the command-and-dispatch training). The prototype ships
// the in-memory implementation; the interface keeps the service layer
// independent of the storage backend. The cascade rules of the database
// (sessions, orders, department reports, messages, zone densities and
// devices vanish when their run is deleted) are implemented by
// DeleteSessionsByRun, DeleteOrdersByRun, DeleteDepartmentsByRun,
// DeleteMessagesByRun, DeleteZoneDensitiesByRun and DeleteDevicesByRun,
// the uniform cleanup entries the drills service calls through its
// run-session cleaner hook.
type Store interface {
	UpsertSession(ctx context.Context, session Session) error
	GetSession(ctx context.Context, runID string) (Session, error)
	DeleteSession(ctx context.Context, runID string) error
	DeleteSessionsByRun(ctx context.Context, runID string) error
	CreateOrder(ctx context.Context, order Order) error
	GetOrder(ctx context.Context, runID, id string) (Order, error)
	ListOrders(ctx context.Context, runID string, filter OrderFilter) ([]Order, int, error)
	UpdateOrder(ctx context.Context, order Order) error
	DeleteOrder(ctx context.Context, runID, id string) error
	DeleteOrdersByRun(ctx context.Context, runID string) error
	UpsertDepartment(ctx context.Context, report DepartmentReport) error
	GetDepartment(ctx context.Context, runID string, department Department) (DepartmentReport, error)
	ListDepartments(ctx context.Context, runID string, filter DepartmentFilter) ([]DepartmentReport, int, error)
	DeleteDepartment(ctx context.Context, runID string, department Department) error
	DeleteDepartmentsByRun(ctx context.Context, runID string) error
	CreateMessage(ctx context.Context, message Message) error
	GetMessage(ctx context.Context, runID, id string) (Message, error)
	ListMessages(ctx context.Context, runID string, filter MessageFilter) ([]Message, int, error)
	DeleteMessage(ctx context.Context, runID, id string) error
	DeleteMessagesByRun(ctx context.Context, runID string) error
	CreateZoneDensity(ctx context.Context, density ZoneDensity) error
	GetZoneDensity(ctx context.Context, runID, id string) (ZoneDensity, error)
	ListZoneDensities(ctx context.Context, runID string, filter ZoneDensityFilter) ([]ZoneDensity, int, error)
	UpdateZoneDensity(ctx context.Context, density ZoneDensity) error
	DeleteZoneDensity(ctx context.Context, runID, id string) error
	DeleteZoneDensitiesByRun(ctx context.Context, runID string) error
	CreateDevice(ctx context.Context, device Device) error
	GetDevice(ctx context.Context, runID, id string) (Device, error)
	ListDevices(ctx context.Context, runID string, filter DeviceFilter) ([]Device, int, error)
	UpdateDevice(ctx context.Context, device Device) error
	DeleteDevice(ctx context.Context, runID, id string) error
	DeleteDevicesByRun(ctx context.Context, runID string) error
}

// InMemoryStore keeps the dispatch sessions, the dispatch orders, the
// dispatch department reports, the dispatch messages, the dispatch
// zone densities and the dispatch device status reports in
// insertion-ordered slices guarded by a mutex. It implements Store for
// the prototype and never touches a database; a database-backed store
// arrives with a later slice. At most one session row exists per run
// (the service upserts by run_id); orders are keyed by (run_id, id);
// department reports by (run_id, department); messages, zone densities
// and devices by (run_id, id).
type InMemoryStore struct {
	mu            sync.Mutex
	sessions      []Session
	orders        []Order
	departments   []DepartmentReport
	messages      []Message
	zoneDensities []ZoneDensity
	devices       []Device
}

// NewInMemoryStore returns an empty in-memory dispatch store.
func NewInMemoryStore() *InMemoryStore {
	return &InMemoryStore{}
}

// UpsertSession inserts the session or replaces the session with the
// same run_id (its id is preserved by the service).
func (s *InMemoryStore) UpsertSession(_ context.Context, session Session) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	for i, item := range s.sessions {
		if item.RunID == session.RunID {
			s.sessions[i] = cloneSession(session)
			return nil
		}
	}
	s.sessions = append(s.sessions, cloneSession(session))
	return nil
}

// GetSession returns the session of the run, or ErrSessionNotFound.
func (s *InMemoryStore) GetSession(_ context.Context, runID string) (Session, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfSession(runID)
	if index < 0 {
		return Session{}, ErrSessionNotFound
	}
	return cloneSession(s.sessions[index]), nil
}

// DeleteSession removes the session of the run, or ErrSessionNotFound.
func (s *InMemoryStore) DeleteSession(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfSession(runID)
	if index < 0 {
		return ErrSessionNotFound
	}
	s.sessions = append(s.sessions[:index], s.sessions[index+1:]...)
	return nil
}

// DeleteSessionsByRun removes every session of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE; the uniform cleanup entry
// the drills service calls through SetRunSessionCleaner). Removing no
// sessions is not an error.
func (s *InMemoryStore) DeleteSessionsByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.sessions[:0]
	for _, item := range s.sessions {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.sessions = kept
	return nil
}

func (s *InMemoryStore) indexOfSession(runID string) int {
	for i, item := range s.sessions {
		if item.RunID == runID {
			return i
		}
	}
	return -1
}

func cloneSession(session Session) Session {
	cloned := session
	cloned.JointVenues = append([]string(nil), session.JointVenues...)
	cloned.Metadata = cloneMap(session.Metadata)
	return cloned
}

func cloneMap(source map[string]any) map[string]any {
	cloned := make(map[string]any, len(source))
	for key, value := range source {
		cloned[key] = value
	}
	return cloned
}
