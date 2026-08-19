package opinion

import (
	"context"
	"fmt"
)

// The complaint write gate reuses postWritableRun / postWriteGateError:
// like the monitoring feed, the release flow and the press conference,
// the 「投诉处理」 complaint flow is live while the drill runs, so only a
// 进行中 run accepts POST/PUT/DELETE (every other status — 未开始 /
// 已完成 / 已终止 — is 400) while GET is never gated. This is the
// pinned gate of this card: it does not reuse the opinion-event
// writableRun semantics (which also allows 未开始).

// CreateComplaint adds one complaint ticket (投诉处理记录) to the run
// and returns the created row. The run must exist (ErrRunNotFound,
// 404) and be 进行中 (ValidationError, 400); complainant and content
// are required; channel defaults to 现场, complaint_type to 入馆受阻,
// status to 待受理 (a new complaint only accepts 待受理), handling /
// handler to ”; closed_at is nil at creation; metadata defaults to {}
// and created_by to ”; the id is a server-generated 26-character
// Crockford Base32 ULID and the timestamps are set by the service.
func (s *Service) CreateComplaint(ctx context.Context, runID string, input ComplaintInput) (Complaint, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Complaint{}, err
	}
	if !postWritableRun(run.Status) {
		return Complaint{}, postWriteGateError(run.Status)
	}
	complaint, err := normalizeComplaint(runID, input, s.now(), s.newID())
	if err != nil {
		return Complaint{}, err
	}
	if err := s.store.CreateComplaint(ctx, complaint); err != nil {
		return Complaint{}, err
	}
	return complaint, nil
}

// ListComplaints returns the complaints of the run matching the filter
// (channel / complaint_type / status exact matches), ordered by
// created_at ASC, id ASC (the complaint flow lists the tickets in
// intake order, 受理顺序), and the total number of matches. A missing
// run is ErrRunNotFound (404). GET is not subject to the write gate: a
// run in any status with complaints still answers 200.
func (s *Service) ListComplaints(ctx context.Context, runID string, filter ComplaintFilter) ([]Complaint, int, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return nil, 0, err
	}
	return s.store.ListComplaints(ctx, runID, filter)
}

// GetComplaint returns the complaint with the given id within the run.
// A missing run is ErrRunNotFound; a missing complaint is
// ErrComplaintNotFound. GET is not subject to the write gate.
func (s *Service) GetComplaint(ctx context.Context, runID, id string) (Complaint, error) {
	if _, err := s.source.GetRun(ctx, runID); err != nil {
		return Complaint{}, err
	}
	return s.store.GetComplaint(ctx, runID, id)
}

// UpdateComplaint updates the complaint in place (partial update:
// omitted fields keep their current values, except complainant and
// content which are required on both entries; channel / complaint_type
// / handling / handler / status / created_by are kept when empty,
// metadata is applied only when explicitly provided) and returns the
// updated row. The run must exist and be 进行中 (404 / 400); a missing
// complaint is ErrComplaintNotFound (404). The handling state machine
// governs status: 待受理 -> 处理中 -> 已办结 are the only transitions, a
// same-value no-op is legal, skips and backward steps (including
// 已办结 -> 处理中) are 400. The transition into 已办结 sets closed_at
// (when it is not already set); every other status keeps closed_at
// null; a PUT that does not touch status (including 已办结 no-ops and
// updates that only change business fields like handling / handler /
// content) keeps closed_at unchanged. updated_at is refreshed by the
// service; id, run_id and created_at are preserved.
func (s *Service) UpdateComplaint(ctx context.Context, runID, id string, update ComplaintUpdate) (Complaint, error) {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return Complaint{}, err
	}
	if !postWritableRun(run.Status) {
		return Complaint{}, postWriteGateError(run.Status)
	}
	complaint, err := s.store.GetComplaint(ctx, runID, id)
	if err != nil {
		return Complaint{}, err
	}
	if update.Complainant == "" {
		return Complaint{}, &ValidationError{Message: "complainant required"}
	}
	if update.Content == "" {
		return Complaint{}, &ValidationError{Message: "content required"}
	}
	if update.Channel != "" {
		if !update.Channel.Valid() {
			return Complaint{}, &ValidationError{Message: fmt.Sprintf("invalid channel: %q", update.Channel)}
		}
		complaint.Channel = update.Channel
	}
	if update.ComplaintType != "" {
		if !update.ComplaintType.Valid() {
			return Complaint{}, &ValidationError{Message: fmt.Sprintf("invalid complaint_type: %q", update.ComplaintType)}
		}
		complaint.ComplaintType = update.ComplaintType
	}
	if update.Status != "" {
		if !update.Status.Valid() {
			return Complaint{}, &ValidationError{Message: fmt.Sprintf("invalid status: %q", update.Status)}
		}
		if !legalComplaintStatusTransition(complaint.Status, update.Status) {
			return Complaint{}, &ValidationError{
				Message: fmt.Sprintf("illegal opinion complaint status transition: %s -> %s", complaint.Status, update.Status),
			}
		}
		if update.Status == ComplaintStatusClosed && complaint.Status != ComplaintStatusClosed {
			now := s.now()
			complaint.ClosedAt = &now
		}
		if update.Status != ComplaintStatusClosed {
			complaint.ClosedAt = nil
		}
		complaint.Status = update.Status
	}
	if update.Handling != "" {
		complaint.Handling = update.Handling
	}
	if update.Handler != "" {
		complaint.Handler = update.Handler
	}
	if update.HasMetadata {
		complaint.Metadata = update.Metadata
	}
	if update.CreatedBy != "" {
		complaint.CreatedBy = update.CreatedBy
	}
	complaint.Complainant = update.Complainant
	complaint.Content = update.Content
	complaint.UpdatedAt = s.now()
	if err := s.store.UpdateComplaint(ctx, complaint); err != nil {
		return Complaint{}, err
	}
	return complaint, nil
}

// DeleteComplaint removes the complaint with the given id within the
// run. The run must exist and be 进行中 (404 / 400); a missing
// complaint is ErrComplaintNotFound (404).
func (s *Service) DeleteComplaint(ctx context.Context, runID, id string) error {
	run, err := s.source.GetRun(ctx, runID)
	if err != nil {
		return err
	}
	if !postWritableRun(run.Status) {
		return postWriteGateError(run.Status)
	}
	return s.store.DeleteComplaint(ctx, runID, id)
}
