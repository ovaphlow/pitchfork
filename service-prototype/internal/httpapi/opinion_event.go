package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
)

// opinionEventBase is the unified resource path of the opinion event
// configuration (舆情事件配置). The item route lives under the owning
// run (/drills/{rid}/opinion-event); the literal opinion-event segment
// is more specific than the /drills/{id} item route and never collides
// with it (same pattern as the step-record, sim-event, assessment and
// command-session routes). The resource is a single-object PUT-upsert
// family (no POST and no collection/list endpoint): the first PUT of a
// run creates the event, later PUTs update it in place.
const opinionEventBase = prototypePrefix + "/drills/{rid}/opinion-event"

// opinionEventHandler adapts the opinion service to the HTTP routing
// layer. It serves the item route (GET / PUT upsert / DELETE by run);
// other methods yield a JSON 405 with Allow. The owning run comes from
// the route path: a missing run is a 404 on every route.
type opinionEventHandler struct {
	service *opinion.Service
}

// newOpinionEventHandler builds the handler over the opinion store and
// the drill store; the drill store backs the run source of the service
// (the run existence check and the write gate).
func newOpinionEventHandler(drillStore drills.Store, opinionStore opinion.Store) *opinionEventHandler {
	return &opinionEventHandler{service: opinion.NewService(opinionStore, opinion.NewRunSource(drillStore))}
}

func (h *opinionEventHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r)
	case http.MethodPut:
		h.upsert(w, r)
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "GET, PUT, DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// opinionEventBody mirrors the client-supplied fields of the request
// body. run_id and id are never part of the body: they are decided by
// the route path and the service (a body that carries them has them
// ignored). event_name is required; subject and summary pass through
// (empty is legal); level defaults to 中热 when omitted (or empty) and
// must be one of 高热/中热/低热; status defaults to 监测中 and must be
// one of 监测中/已预警/已处置 (a new event only accepts 监测中; later PUTs
// follow the disposition state machine); occurred_at is kept as raw
// JSON so the handler can tell an omitted field from an explicit null
// (both mean unset) and reject anything that is not an RFC3339 string;
// metadata must be a JSON object (any other shape fails the JSON decode
// with 400); created_by defaults to an empty string.
type opinionEventBody struct {
	EventName  string          `json:"event_name"`
	Subject    string          `json:"subject"`
	Summary    string          `json:"summary"`
	OccurredAt json.RawMessage `json:"occurred_at"`
	Level      opinion.Level   `json:"level"`
	Status     opinion.Status  `json:"status"`
	Metadata   map[string]any  `json:"metadata"`
	CreatedBy  string          `json:"created_by"`
}

// decodeOpinionEventBody reads a single JSON object from the request
// body. An empty, malformed or non-object body (including a JSON null)
// yields a 400 { "error": ... } response, matching the decode convention
// of the other PUT resources; an empty object {} is legal (an
// all-default create or update).
func decodeOpinionEventBody(w http.ResponseWriter, r *http.Request) (opinionEventBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionEventBody{}, false
	}
	var body opinionEventBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionEventBody{}, false
	}
	return body, true
}

// parseOpinionEventOccurredAt converts the raw occurred_at field. An
// omitted field and an explicit null both yield a nil instant (unset);
// any other value must be a JSON string in RFC3339 format, otherwise
// 400.
func parseOpinionEventOccurredAt(w http.ResponseWriter, raw json.RawMessage) (*time.Time, bool) {
	if raw == nil {
		return nil, true
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		return nil, true
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid occurred_at")
		return nil, false
	}
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid occurred_at")
		return nil, false
	}
	return &parsed, true
}

func (h *opinionEventHandler) upsert(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionEventBody(w, r)
	if !ok {
		return
	}
	occurredAt, ok := parseOpinionEventOccurredAt(w, body.OccurredAt)
	if !ok {
		return
	}
	event, err := h.service.UpsertEvent(r.Context(), r.PathValue("rid"), opinion.EventInput{
		EventName:  body.EventName,
		Subject:    body.Subject,
		Summary:    body.Summary,
		OccurredAt: occurredAt,
		Level:      body.Level,
		Status:     body.Status,
		Metadata:   body.Metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeOpinionEventError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, event)
}

func (h *opinionEventHandler) get(w http.ResponseWriter, r *http.Request) {
	event, err := h.service.GetEvent(r.Context(), r.PathValue("rid"))
	if err != nil {
		writeOpinionEventError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, event)
}

func (h *opinionEventHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteEvent(r.Context(), r.PathValue("rid")); err != nil {
		writeOpinionEventError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOpinionEventError maps the opinion service errors to JSON error
// responses: validation errors become 400, unknown runs or unconfigured
// events 404, everything else 500.
func writeOpinionEventError(w http.ResponseWriter, err error) {
	var validationError *opinion.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, opinion.ErrRunNotFound),
		errors.Is(err, opinion.ErrEventNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
