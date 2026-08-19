package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// commandSessionBase is the unified resource path of the dispatch
// command session (指挥调度会话配置). The item route lives under the
// owning run (/drills/{rid}/command-session); the literal
// command-session segment is more specific than the /drills/{id} item
// route and never collides with it (same pattern as the step-record,
// sim-event and assessment routes). The resource is a single-object
// PUT-upsert family (no POST and no collection/list endpoint): the first
// PUT of a run creates the session, later PUTs update it in place.
const commandSessionBase = prototypePrefix + "/drills/{rid}/command-session"

// commandSessionHandler adapts the dispatch service to the HTTP routing
// layer. It serves the item route (GET / PUT upsert / DELETE by run);
// other methods yield a JSON 405 with Allow. The owning run comes from
// the route path: a missing run is a 404 on every route.
type commandSessionHandler struct {
	service *dispatch.Service
}

// newCommandSessionHandler builds the handler over the dispatch store
// and the drill store; the drill store backs the run source of the
// service (the run existence check and the write gate).
func newCommandSessionHandler(drillStore drills.Store, dispatchStore dispatch.Store) *commandSessionHandler {
	return &commandSessionHandler{service: dispatch.NewService(dispatchStore, dispatch.NewRunSource(drillStore))}
}

func (h *commandSessionHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// commandSessionBody mirrors the client-supplied fields of the request
// body. run_id and id are never part of the body: they are decided by
// the route path and the service (a body that carries them has them
// ignored). mode defaults to 实战演练 when omitted (or empty); an
// explicit value must be one of 桌面推演/实战演练/远程协同. main_venue
// passes through (empty is legal); joint_venues must be a JSON array of
// strings and metadata a JSON object (any other shape fails the JSON
// decode with 400); created_by defaults to an empty string.
type commandSessionBody struct {
	Mode        dispatch.Mode  `json:"mode"`
	MainVenue   string         `json:"main_venue"`
	JointVenues []string       `json:"joint_venues"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
}

// decodeCommandSessionBody reads a single JSON object from the request
// body. An empty, malformed or non-object body (including a JSON null)
// yields a 400 { "error": ... } response, matching the decode convention
// of the other PUT resources; an empty object {} is legal (an
// all-default create or update).
func decodeCommandSessionBody(w http.ResponseWriter, r *http.Request) (commandSessionBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return commandSessionBody{}, false
	}
	var body commandSessionBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return commandSessionBody{}, false
	}
	return body, true
}

func (h *commandSessionHandler) upsert(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeCommandSessionBody(w, r)
	if !ok {
		return
	}
	session, err := h.service.UpsertSession(r.Context(), r.PathValue("rid"), dispatch.SessionInput{
		Mode:        body.Mode,
		MainVenue:   body.MainVenue,
		JointVenues: body.JointVenues,
		Metadata:    body.Metadata,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeCommandSessionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, session)
}

func (h *commandSessionHandler) get(w http.ResponseWriter, r *http.Request) {
	session, err := h.service.GetSession(r.Context(), r.PathValue("rid"))
	if err != nil {
		writeCommandSessionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, session)
}

func (h *commandSessionHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteSession(r.Context(), r.PathValue("rid")); err != nil {
		writeCommandSessionError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeCommandSessionError maps the dispatch service errors to JSON
// error responses: validation errors become 400, unknown runs or
// unconfigured sessions 404, everything else 500.
func writeCommandSessionError(w http.ResponseWriter, err error) {
	var validationError *dispatch.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, dispatch.ErrRunNotFound),
		errors.Is(err, dispatch.ErrSessionNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
