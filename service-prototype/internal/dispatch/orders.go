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

// ErrOrderNotFound is returned when the run exists but the dispatch
// order does not. It maps to HTTP 404 in the routing layer.
var ErrOrderNotFound = errors.New("dispatch order not found")

// OrderStatus is the execution-progress state of a dispatch order. The
// state machine is 待接收→已接收→执行中→已完成 and the service only allows
// adjacent forward transitions (same-status no-ops are legal; backward
// moves and skips are rejected).
type OrderStatus string

const (
	OrderStatusPending   OrderStatus = "待接收"
	OrderStatusReceived  OrderStatus = "已接收"
	OrderStatusExecuting OrderStatus = "执行中"
	OrderStatusCompleted OrderStatus = "已完成"
)

// DefaultOrderStatus is applied when a request omits the status field.
const DefaultOrderStatus = OrderStatusPending

var validOrderStatuses = []OrderStatus{
	OrderStatusPending,
	OrderStatusReceived,
	OrderStatusExecuting,
	OrderStatusCompleted,
}

// Valid reports whether status is one of the allowed order statuses.
func (status OrderStatus) Valid() bool {
	for _, candidate := range validOrderStatuses {
		if status == candidate {
			return true
		}
	}
	return false
}

// adjacentOrderTransition reports whether the status change is exactly
// the next step of the 待接收→已接收→执行中→已完成 chain. Same-status
// requests are no-ops (not migrations) and handled by the caller.
func adjacentOrderTransition(from, to OrderStatus) bool {
	switch from {
	case OrderStatusPending:
		return to == OrderStatusReceived
	case OrderStatusReceived:
		return to == OrderStatusExecuting
	case OrderStatusExecuting:
		return to == OrderStatusCompleted
	default:
		return false
	}
}

// Priority is the urgency of a dispatch order (指令优先级).
type Priority string

const (
	PriorityNormal   Priority = "普通"
	PriorityUrgent   Priority = "紧急"
	PriorityCritical Priority = "特急"
)

// DefaultPriority is applied when a request omits the priority field.
const DefaultPriority = PriorityNormal

var validPriorities = []Priority{PriorityNormal, PriorityUrgent, PriorityCritical}

// Valid reports whether priority is one of the allowed values.
func (priority Priority) Valid() bool {
	for _, candidate := range validPriorities {
		if priority == candidate {
			return true
		}
	}
	return false
}

// TargetType is the receiver kind of a dispatch order (指令对象类型):
// a department, a group or an individual.
type TargetType string

const (
	TargetTypeDepartment TargetType = "部门"
	TargetTypeGroup      TargetType = "小组"
	TargetTypePerson     TargetType = "个人"
)

var validTargetTypes = []TargetType{TargetTypeDepartment, TargetTypeGroup, TargetTypePerson}

// Valid reports whether targetType is one of the allowed values.
func (targetType TargetType) Valid() bool {
	for _, candidate := range validTargetTypes {
		if targetType == candidate {
			return true
		}
	}
	return false
}

// Order is a dispatch order (调度指令) issued by the commander to a
// department/group/person within a drill run: the instruction text
// (title/content), the priority (普通/紧急/特急), the receiver
// (target_type + target_name), the execution-progress status with the
// feedback trail, and the optional required-completion deadline.
// issued_at is set by the service at creation; completed_at is managed
// by the service together with the status. The id and the timestamps
// are server-generated; run_id comes from the route path.
type Order struct {
	ID          string      `json:"id"`
	RunID       string      `json:"run_id"`
	Title       string      `json:"title"`
	Content     string      `json:"content"`
	Priority    Priority    `json:"priority"`
	TargetType  TargetType  `json:"target_type"`
	TargetName  string      `json:"target_name"`
	Status      OrderStatus `json:"status"`
	Feedback    string      `json:"feedback"`
	Deadline    *time.Time  `json:"deadline"`
	IssuedAt    *time.Time  `json:"issued_at"`
	CompletedAt *time.Time  `json:"completed_at"`
	CreatedBy   string      `json:"created_by"`
	CreatedAt   time.Time   `json:"created_at"`
	UpdatedAt   time.Time   `json:"updated_at"`
}

// OrderInput carries the client-supplied fields of an order creation.
// id, run_id, issued_at and the timestamps are never part of the input:
// they are decided by the route path and the service. title/content/
// target_type/target_name are required; priority defaults to 普通 and
// status to 待接收 (an explicit status only accepts 待接收, because an
// order starts its lifecycle as issued-but-not-received); feedback
// defaults to an empty string; deadline is optional; created_by passes
// through (the prototype has no auth context).
type OrderInput struct {
	Title      string
	Content    string
	Priority   Priority
	TargetType TargetType
	TargetName string
	Status     OrderStatus
	Feedback   string
	Deadline   *time.Time
	CreatedBy  string
}

// OrderUpdate carries the client-supplied fields of an order update
// (partial update: nil means "keep the current value"; HasDeadline
// tells an omitted deadline from an explicit null that clears it).
// issued_at/created_at/created_by are never updatable.
type OrderUpdate struct {
	Title       *string
	Content     *string
	Priority    *Priority
	TargetType  *TargetType
	TargetName  *string
	Status      *OrderStatus
	Feedback    *string
	HasDeadline bool
	Deadline    *time.Time
}

// OrderFilter selects orders for listing. Empty enum values match
// everything; Limit and Offset paginate the matching set.
type OrderFilter struct {
	Status     OrderStatus
	Priority   Priority
	TargetType TargetType
	Limit      int
	Offset     int
}

// normalizeOrder validates client input and produces a complete order.
// title/content/target_type/target_name are required; priority defaults
// to 普通; status defaults to 待接收 and an explicit status must be
// exactly 待接收 (creation means issuing, so the order always starts
// un-received). deadline/feedback/created_by pass through. issued_at is
// set by the caller at creation; completed_at stays null (a fresh order
// is never completed).
func normalizeOrder(runID string, input OrderInput, now time.Time, id string) (Order, error) {
	title := strings.TrimSpace(input.Title)
	if title == "" {
		return Order{}, &ValidationError{Message: "title required"}
	}
	content := strings.TrimSpace(input.Content)
	if content == "" {
		return Order{}, &ValidationError{Message: "content required"}
	}
	priority := input.Priority
	if priority == "" {
		priority = DefaultPriority
	}
	if !priority.Valid() {
		return Order{}, &ValidationError{Message: fmt.Sprintf("invalid priority: %q", input.Priority)}
	}
	targetType := input.TargetType
	if targetType == "" {
		return Order{}, &ValidationError{Message: "target_type required"}
	}
	if !targetType.Valid() {
		return Order{}, &ValidationError{Message: fmt.Sprintf("invalid target_type: %q", input.TargetType)}
	}
	targetName := strings.TrimSpace(input.TargetName)
	if targetName == "" {
		return Order{}, &ValidationError{Message: "target_name required"}
	}
	status := input.Status
	if status == "" {
		status = DefaultOrderStatus
	}
	if !status.Valid() {
		return Order{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", input.Status)}
	}
	if status != OrderStatusPending {
		return Order{}, &ValidationError{Message: "status must be 待接收 at creation"}
	}
	issuedAt := now
	return Order{
		ID:          id,
		RunID:       runID,
		Title:       title,
		Content:     content,
		Priority:    priority,
		TargetType:  targetType,
		TargetName:  targetName,
		Status:      status,
		Feedback:    input.Feedback,
		Deadline:    input.Deadline,
		IssuedAt:    &issuedAt,
		CompletedAt: nil,
		CreatedBy:   input.CreatedBy,
		CreatedAt:   now,
		UpdatedAt:   now,
	}, nil
}

// orderWritableRun reports whether a run in the given status may
// receive order writes: unlike the command sessions (writable in
// 未开始/进行中), dispatch orders are only writable while the run is
// 进行中.
func orderWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress
}

// CreateOrder issues a dispatch order within the run and returns the
// created row. The run must be 进行中 (400 otherwise); a missing run is
// ErrRunNotFound (404). issued_at is set by the service at creation.
func (s *Service) CreateOrder(ctx context.Context, runID string, input OrderInput) (Order, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Order{}, err
	}
	if !orderWritableRun(run.Status) {
		return Order{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	order, err := normalizeOrder(runID, input, now, s.newID())
	if err != nil {
		return Order{}, err
	}
	if err := s.store.CreateOrder(ctx, order); err != nil {
		return Order{}, err
	}
	return order, nil
}

// GetOrder returns the order with the given id within the run. A
// missing run is ErrRunNotFound; a missing order is ErrOrderNotFound.
// GET is not subject to the write gate: a run in 已完成/已终止 with
// orders still answers 200.
func (s *Service) GetOrder(ctx context.Context, runID, id string) (Order, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Order{}, err
	}
	return s.store.GetOrder(ctx, runID, id)
}

// ListOrders returns the orders of the run matching the filter (status,
// priority and target_type exact matches, ordered by created_at DESC,
// id DESC) and the total number of matches. A missing run is
// ErrRunNotFound; GET is not subject to the write gate.
func (s *Service) ListOrders(ctx context.Context, runID string, filter OrderFilter) ([]Order, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListOrders(ctx, runID, filter)
}

// UpdateOrder updates the order in place (partial update: omitted
// fields keep their current values; an explicitly provided
// title/content/target_type/target_name must not be empty). The run
// must be 进行中 (400 otherwise); a missing run or order is a 404. The
// status state machine only allows adjacent forward transitions
// 待接收→已接收→执行中→已完成 (same-status no-ops are legal); the
// transition to 已完成 sets completed_at and a change away from it
// clears it. issued_at/created_at/created_by are preserved.
func (s *Service) UpdateOrder(ctx context.Context, runID, id string, update OrderUpdate) (Order, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Order{}, err
	}
	if !orderWritableRun(run.Status) {
		return Order{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	order, err := s.store.GetOrder(ctx, runID, id)
	if err != nil {
		return Order{}, err
	}
	if update.Title != nil {
		title := strings.TrimSpace(*update.Title)
		if title == "" {
			return Order{}, &ValidationError{Message: "title required"}
		}
		order.Title = title
	}
	if update.Content != nil {
		content := strings.TrimSpace(*update.Content)
		if content == "" {
			return Order{}, &ValidationError{Message: "content required"}
		}
		order.Content = content
	}
	if update.Priority != nil {
		if !update.Priority.Valid() {
			return Order{}, &ValidationError{Message: fmt.Sprintf("invalid priority: %q", *update.Priority)}
		}
		order.Priority = *update.Priority
	}
	if update.TargetType != nil {
		if !update.TargetType.Valid() {
			return Order{}, &ValidationError{Message: fmt.Sprintf("invalid target_type: %q", *update.TargetType)}
		}
		order.TargetType = *update.TargetType
	}
	if update.TargetName != nil {
		targetName := strings.TrimSpace(*update.TargetName)
		if targetName == "" {
			return Order{}, &ValidationError{Message: "target_name required"}
		}
		order.TargetName = targetName
	}
	if update.Feedback != nil {
		order.Feedback = *update.Feedback
	}
	if update.HasDeadline {
		order.Deadline = update.Deadline
	}
	if update.Status != nil {
		if !update.Status.Valid() {
			return Order{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", *update.Status)}
		}
		if *update.Status != order.Status {
			if !adjacentOrderTransition(order.Status, *update.Status) {
				return Order{}, &ValidationError{
					Message: fmt.Sprintf("invalid status transition: %q -> %q", order.Status, *update.Status),
				}
			}
			now := s.now()
			if *update.Status == OrderStatusCompleted {
				if order.CompletedAt == nil {
					order.CompletedAt = &now
				}
			} else {
				order.CompletedAt = nil
			}
			order.Status = *update.Status
		}
	}
	order.UpdatedAt = s.now()
	if err := s.store.UpdateOrder(ctx, order); err != nil {
		return Order{}, err
	}
	return order, nil
}

// DeleteOrder removes the order with the given id within the run. The
// run must be 进行中 (400 otherwise); a missing run or order is a 404.
func (s *Service) DeleteOrder(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !orderWritableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteOrder(ctx, runID, id)
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

// ─── In-memory store ─────────────────────────────────────────────────

// CreateOrder appends the order to the store.
func (s *InMemoryStore) CreateOrder(_ context.Context, order Order) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.orders = append(s.orders, cloneOrder(order))
	return nil
}

// ListOrders returns the orders of the run matching the filter (status,
// priority and target_type exact matches) ordered by created_at DESC,
// id DESC, the total number of matches and the paginated page.
func (s *InMemoryStore) ListOrders(_ context.Context, runID string, filter OrderFilter) ([]Order, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Order, 0, len(s.orders))
	for _, item := range s.orders {
		if item.RunID != runID {
			continue
		}
		if filter.Status != "" && item.Status != filter.Status {
			continue
		}
		if filter.Priority != "" && item.Priority != filter.Priority {
			continue
		}
		if filter.TargetType != "" && item.TargetType != filter.TargetType {
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
	start, end := paginate(total, filter.Limit, filter.Offset)
	page := make([]Order, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneOrder(item))
	}
	return page, total, nil
}

// GetOrder returns the order with the given id within the run, or
// ErrOrderNotFound (an order of another run is not found as well).
func (s *InMemoryStore) GetOrder(_ context.Context, runID, id string) (Order, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfOrder(runID, id)
	if index < 0 {
		return Order{}, ErrOrderNotFound
	}
	return cloneOrder(s.orders[index]), nil
}

// UpdateOrder replaces the order with the same id (within the same
// run), or ErrOrderNotFound.
func (s *InMemoryStore) UpdateOrder(_ context.Context, order Order) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfOrder(order.RunID, order.ID)
	if index < 0 {
		return ErrOrderNotFound
	}
	s.orders[index] = cloneOrder(order)
	return nil
}

// DeleteOrder removes the order with the given id within the run, or
// ErrOrderNotFound.
func (s *InMemoryStore) DeleteOrder(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfOrder(runID, id)
	if index < 0 {
		return ErrOrderNotFound
	}
	s.orders = append(s.orders[:index], s.orders[index+1:]...)
	return nil
}

// DeleteOrdersByRun removes every order of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE; the uniform cleanup entry
// the drills service calls through SetRunSessionCleaner). Removing no
// orders is not an error.
func (s *InMemoryStore) DeleteOrdersByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.orders[:0]
	for _, item := range s.orders {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.orders = kept
	return nil
}

func (s *InMemoryStore) indexOfOrder(runID, id string) int {
	for i, item := range s.orders {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func cloneOrder(order Order) Order {
	cloned := order
	if order.Deadline != nil {
		deadline := *order.Deadline
		cloned.Deadline = &deadline
	}
	if order.IssuedAt != nil {
		issuedAt := *order.IssuedAt
		cloned.IssuedAt = &issuedAt
	}
	if order.CompletedAt != nil {
		completedAt := *order.CompletedAt
		cloned.CompletedAt = &completedAt
	}
	return cloned
}
