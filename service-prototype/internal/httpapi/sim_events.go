package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// simEventsBase is the unified resource path of the drill simulated
// events (演练模拟事件演示). The collection and the item routes live under
// the owning run (/drills/{rid}/sim-events and
// /drills/{rid}/sim-events/{eid}); the literal sim-events segment is
// more specific than the /drills/{id} item route and never collides with
// it (same pattern as the step-record routes).
const simEventsBase = prototypePrefix + "/drills/{rid}/sim-events"

// simEventsHandler adapts the drills service to the HTTP routing layer.
// It serves the per-run sim event collection (GET list / POST create)
// and the item routes (GET / PUT / DELETE by event id); other methods
// yield a JSON 405 with Allow. The owning run comes from the route path:
// writes require the run to be 进行中 (400 otherwise) and a missing run
// is a 404 on every route.
type simEventsHandler struct {
	service *drills.Service
}

func newSimEventsHandler(store drills.Store) *simEventsHandler {
	return &simEventsHandler{service: drills.NewService(store)}
}

func (h *simEventsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.list(w, r)
	case http.MethodPost:
		h.create(w, r)
	default:
		w.Header().Set("Allow", "GET, POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (h *simEventsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r)
	case http.MethodPut:
		h.update(w, r)
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "GET, PUT, DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// simEventBody mirrors the client-supplied fields of the request body.
// run_id and the timestamps are never part of the body: run_id comes
// from the route path, triggered_at is server-managed (set at creation)
// and handled_at is managed by the service together with the status.
// payload is an optional JSON object (an omitted field is replaced by {}
// on create and kept on update); event_type is required on create;
// status defaults to 已触发 on create; created_by is optional (empty when
// omitted) because the prototype has no auth context. payload is kept as
// raw JSON so the handler can tell an omitted field from an explicit
// null and reject every non-object value.
type simEventBody struct {
	EventType string          `json:"event_type"`
	Payload   json.RawMessage `json:"payload"`
	Status    string          `json:"status"`
	CreatedBy string          `json:"created_by"`
}

// decodeSimEventBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeSimEventBody(w http.ResponseWriter, r *http.Request) (simEventBody, bool) {
	var body simEventBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return simEventBody{}, false
	}
	return body, true
}

// parseSimEventPayload converts the raw payload field. An omitted field
// yields (nil, true): the service replaces it with {} on create and
// keeps the current value on update. A provided payload must be a JSON
// object (arrays, scalars and explicit null are rejected with 400); an
// empty object {} is legal.
func parseSimEventPayload(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool) {
	if raw == nil {
		return nil, true
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "payload must be a JSON object")
		return nil, false
	}
	var payload map[string]any
	if err := json.Unmarshal(raw, &payload); err != nil {
		writeError(w, http.StatusBadRequest, "payload must be a JSON object")
		return nil, false
	}
	return payload, true
}

func (h *simEventsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeSimEventBody(w, r)
	if !ok {
		return
	}
	payload, ok := parseSimEventPayload(w, body.Payload)
	if !ok {
		return
	}
	event, err := h.service.CreateSimEvent(r.Context(), r.PathValue("rid"), drills.SimEventInput{
		EventType: drills.SimEventType(body.EventType),
		Payload:   payload,
		Status:    drills.SimEventStatus(body.Status),
		CreatedBy: body.CreatedBy,
	})
	if err != nil {
		writeSimEventError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, event)
}

// simEventListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type simEventListResponse struct {
	Records []drills.SimEvent `json:"records"`
	Meta    metaResponse      `json:"meta"`
}

func (h *simEventsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseSimEventListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListSimEvents(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeSimEventError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, simEventListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseSimEventListFilter reads the event_type/status/limit/offset query
// parameters. A non-empty event_type or status filter must be one of the
// allowed sim event values, otherwise 400; limit/offset must be
// non-negative integers (default limit 50), otherwise 400.
func parseSimEventListFilter(w http.ResponseWriter, r *http.Request) (drills.SimEventFilter, bool) {
	query := r.URL.Query()
	filter := drills.SimEventFilter{Limit: defaultPageSize}

	if raw := query.Get("event_type"); raw != "" {
		eventType := drills.SimEventType(raw)
		if !eventType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid event_type")
			return drills.SimEventFilter{}, false
		}
		filter.EventType = eventType
	}
	if raw := query.Get("status"); raw != "" {
		status := drills.SimEventStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return drills.SimEventFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return drills.SimEventFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return drills.SimEventFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *simEventsHandler) get(w http.ResponseWriter, r *http.Request) {
	event, err := h.service.GetSimEvent(r.Context(), r.PathValue("rid"), r.PathValue("eid"))
	if err != nil {
		writeSimEventError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, event)
}

func (h *simEventsHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeSimEventBody(w, r)
	if !ok {
		return
	}
	payload, ok := parseSimEventPayload(w, body.Payload)
	if !ok {
		return
	}
	event, err := h.service.UpdateSimEvent(r.Context(), r.PathValue("rid"), r.PathValue("eid"), drills.SimEventUpdate{
		EventType:  drills.SimEventType(body.EventType),
		Payload:    payload,
		Status:     drills.SimEventStatus(body.Status),
		HasPayload: body.Payload != nil,
	})
	if err != nil {
		writeSimEventError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, event)
}

func (h *simEventsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteSimEvent(r.Context(), r.PathValue("rid"), r.PathValue("eid")); err != nil {
		writeSimEventError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeSimEventError maps the drills service errors of the sim event
// resource to JSON error responses: validation errors become 400,
// unknown runs or events 404, everything else 500.
func writeSimEventError(w http.ResponseWriter, err error) {
	var validationError *drills.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrRunNotFound),
		errors.Is(err, drills.ErrSimEventNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
