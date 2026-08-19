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

// ErrMessageNotFound is returned when the run exists but the dispatch
// message does not. It maps to HTTP 404 in the routing layer.
var ErrMessageNotFound = errors.New("dispatch message not found")

// SenderType is the sender side of a dispatch message (消息发送方): the
// simulated real-time communication stream runs between the command
// center (指挥中心) and the field personnel (现场人员).
type SenderType string

const (
	SenderTypeCommand SenderType = "指挥中心"
	SenderTypeField   SenderType = "现场人员"
)

var validSenderTypes = []SenderType{SenderTypeCommand, SenderTypeField}

// Valid reports whether senderType is one of the allowed values.
func (senderType SenderType) Valid() bool {
	for _, candidate := range validSenderTypes {
		if senderType == candidate {
			return true
		}
	}
	return false
}

// Message is a dispatch message (即时通讯消息) of a drill run: the
// simulated real-time communication between the command center and the
// field personnel. sender_type is required (指挥中心/现场人员); sender_name
// is the display name of the sender and defaults to an empty string;
// content is required. sent_at is set by the service at creation (a
// message is sent the moment it is created) and always echoes non-null
// after creation. Messages are immutable: there is no update path. The
// id and the timestamps are server-generated; run_id comes from the
// route path.
type Message struct {
	ID         string     `json:"id"`
	RunID      string     `json:"run_id"`
	SenderType SenderType `json:"sender_type"`
	SenderName string     `json:"sender_name"`
	Content    string     `json:"content"`
	SentAt     *time.Time `json:"sent_at"`
	CreatedBy  string     `json:"created_by"`
	CreatedAt  time.Time  `json:"created_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
}

// MessageInput carries the client-supplied fields of a message
// creation. id, run_id, sent_at and the timestamps are never part of
// the input: they are decided by the route path and the service.
// sender_type and content are required; sender_name defaults to an
// empty string; created_by passes through (the prototype has no auth
// context).
type MessageInput struct {
	SenderType SenderType
	SenderName string
	Content    string
	CreatedBy  string
}

// MessageFilter selects messages for listing. An empty sender_type
// matches everything; Limit and Offset paginate the matching set.
type MessageFilter struct {
	SenderType SenderType
	Limit      int
	Offset     int
}

// normalizeMessage validates client input and produces a complete
// message. sender_type and content are required (empty or whitespace
// content is rejected); sender_name and created_by pass through with an
// empty default. sent_at is set by the caller at creation (a message is
// sent the moment it is created).
func normalizeMessage(runID string, input MessageInput, now time.Time, id string) (Message, error) {
	senderType := input.SenderType
	if senderType == "" {
		return Message{}, &ValidationError{Message: "sender_type required"}
	}
	if !senderType.Valid() {
		return Message{}, &ValidationError{Message: fmt.Sprintf("invalid sender_type: %q", input.SenderType)}
	}
	content := strings.TrimSpace(input.Content)
	if content == "" {
		return Message{}, &ValidationError{Message: "content required"}
	}
	sentAt := now
	return Message{
		ID:         id,
		RunID:      runID,
		SenderType: senderType,
		SenderName: input.SenderName,
		Content:    content,
		SentAt:     &sentAt,
		CreatedBy:  input.CreatedBy,
		CreatedAt:  now,
		UpdatedAt:  now,
	}, nil
}

// messageWritableRun reports whether a run in the given status may
// receive message writes: like the dispatch orders, messages are only
// writable while the run is 进行中.
func messageWritableRun(status drills.RunStatus) bool {
	return status == drills.RunStatusInProgress
}

// CreateMessage sends a dispatch message within the run and returns the
// created row. The run must be 进行中 (400 otherwise); a missing run is
// ErrRunNotFound (404). sent_at is set by the service at creation.
func (s *Service) CreateMessage(ctx context.Context, runID string, input MessageInput) (Message, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Message{}, err
	}
	if !messageWritableRun(run.Status) {
		return Message{}, &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	now := s.now()
	message, err := normalizeMessage(runID, input, now, s.newID())
	if err != nil {
		return Message{}, err
	}
	if err := s.store.CreateMessage(ctx, message); err != nil {
		return Message{}, err
	}
	return message, nil
}

// GetMessage returns the message with the given id within the run. A
// missing run is ErrRunNotFound; a missing message is ErrMessageNotFound.
// GET is not subject to the write gate: a run in 已完成/已终止 with
// messages still answers 200.
func (s *Service) GetMessage(ctx context.Context, runID, id string) (Message, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Message{}, err
	}
	return s.store.GetMessage(ctx, runID, id)
}

// ListMessages returns the messages of the run matching the filter
// (sender_type exact match, ordered by created_at ASC, id ASC — the
// chat order) and the total number of matches. A missing run is
// ErrRunNotFound; GET is not subject to the write gate.
func (s *Service) ListMessages(ctx context.Context, runID string, filter MessageFilter) ([]Message, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListMessages(ctx, runID, filter)
}

// DeleteMessage removes the message with the given id within the run.
// The run must be 进行中 (400 otherwise); a missing run or message is a
// 404 (the run existence check comes first, so a missing run never
// surfaces as a gate error).
func (s *Service) DeleteMessage(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !messageWritableRun(run.Status) {
		return &ValidationError{
			Message: "run status " + string(run.Status) + " does not allow this operation",
		}
	}
	return s.store.DeleteMessage(ctx, runID, id)
}

// ─── In-memory store ─────────────────────────────────────────────────

// CreateMessage appends the message to the store.
func (s *InMemoryStore) CreateMessage(_ context.Context, message Message) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.messages = append(s.messages, cloneMessage(message))
	return nil
}

// ListMessages returns the messages of the run matching the filter
// (sender_type exact match) ordered by created_at ASC, id ASC (the
// chat order), the total number of matches and the paginated page.
func (s *InMemoryStore) ListMessages(_ context.Context, runID string, filter MessageFilter) ([]Message, int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	matched := make([]Message, 0, len(s.messages))
	for _, item := range s.messages {
		if item.RunID != runID {
			continue
		}
		if filter.SenderType != "" && item.SenderType != filter.SenderType {
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
	page := make([]Message, 0, end-start)
	for _, item := range matched[start:end] {
		page = append(page, cloneMessage(item))
	}
	return page, total, nil
}

// GetMessage returns the message with the given id within the run, or
// ErrMessageNotFound (a message of another run is not found as well).
func (s *InMemoryStore) GetMessage(_ context.Context, runID, id string) (Message, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfMessage(runID, id)
	if index < 0 {
		return Message{}, ErrMessageNotFound
	}
	return cloneMessage(s.messages[index]), nil
}

// DeleteMessage removes the message with the given id within the run,
// or ErrMessageNotFound.
func (s *InMemoryStore) DeleteMessage(_ context.Context, runID, id string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	index := s.indexOfMessage(runID, id)
	if index < 0 {
		return ErrMessageNotFound
	}
	s.messages = append(s.messages[:index], s.messages[index+1:]...)
	return nil
}

// DeleteMessagesByRun removes every message of the run (the in-memory
// counterpart of the DB's ON DELETE CASCADE; the uniform cleanup entry
// the drills service calls through SetRunSessionCleaner). Removing no
// messages is not an error.
func (s *InMemoryStore) DeleteMessagesByRun(_ context.Context, runID string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	kept := s.messages[:0]
	for _, item := range s.messages {
		if item.RunID != runID {
			kept = append(kept, item)
		}
	}
	s.messages = kept
	return nil
}

func (s *InMemoryStore) indexOfMessage(runID, id string) int {
	for i, item := range s.messages {
		if item.RunID == runID && item.ID == id {
			return i
		}
	}
	return -1
}

func cloneMessage(message Message) Message {
	cloned := message
	if message.SentAt != nil {
		sentAt := *message.SentAt
		cloned.SentAt = &sentAt
	}
	return cloned
}
