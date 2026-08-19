package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// runsBase is the unified resource path of the drill runs.
const runsBase = prototypePrefix + "/drills"

// runsHandler adapts the drills service to the HTTP routing layer. It
// serves the collection (GET list / POST create), the item routes
// (GET / PUT / DELETE by id) and the state-machine transitions
// (POST /{id}/start|complete|terminate); other methods yield a JSON 405
// with Allow.
type runsHandler struct {
	service *drills.Service
}

func newRunsHandler(store drills.Store) *runsHandler {
	return &runsHandler{service: drills.NewService(store)}
}

func (h *runsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *runsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// runBody mirrors the client-supplied fields of the request body.
// status, started_at and completed_at are server-managed and never part
// of the input: a request body that carries them has them ignored by the
// decoder. created_by is optional (empty when omitted) because the
// prototype has no auth context; metadata is an optional JSON object
// echoed verbatim.
type runBody struct {
	ScenarioID string         `json:"scenario_id"`
	Title      string         `json:"title"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
}

// decodeRunBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeRunBody(w http.ResponseWriter, r *http.Request) (runBody, bool) {
	var body runBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return runBody{}, false
	}
	return body, true
}

func (h *runsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeRunBody(w, r)
	if !ok {
		return
	}
	run, err := h.service.CreateRun(r.Context(), drills.RunInput{
		ScenarioID: body.ScenarioID,
		Title:      body.Title,
		Metadata:   body.Metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeRunError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, run)
}

// runListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type runListResponse struct {
	Records []drills.Run `json:"records"`
	Meta    metaResponse `json:"meta"`
}

func (h *runsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseRunListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListRuns(r.Context(), filter)
	if err != nil {
		writeRunError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, runListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseRunListFilter reads the status/scenario_id/limit/offset query
// parameters. A non-empty status filter must be one of the allowed run
// statuses and limit/offset must be non-negative integers, otherwise
// 400. scenario_id is an exact match with no further validation.
func parseRunListFilter(w http.ResponseWriter, r *http.Request) (drills.RunFilter, bool) {
	query := r.URL.Query()
	filter := drills.RunFilter{Limit: defaultPageSize}

	if raw := query.Get("status"); raw != "" {
		status := drills.RunStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return drills.RunFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("scenario_id"); raw != "" {
		filter.ScenarioID = raw
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return drills.RunFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return drills.RunFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *runsHandler) get(w http.ResponseWriter, r *http.Request) {
	run, err := h.service.GetRun(r.Context(), r.PathValue("id"))
	if err != nil {
		writeRunError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, run)
}

func (h *runsHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeRunBody(w, r)
	if !ok {
		return
	}
	run, err := h.service.UpdateRun(r.Context(), r.PathValue("id"), drills.RunInput{
		ScenarioID: body.ScenarioID,
		Title:      body.Title,
		Metadata:   body.Metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeRunError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, run)
}

func (h *runsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteRun(r.Context(), r.PathValue("id")); err != nil {
		writeRunError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// transition serves one step of the run state machine
// (POST /drills/{id}/start|complete|terminate) and returns the updated
// run. A missing run is a 404; an illegal transition is a 400.
func (h *runsHandler) transition(w http.ResponseWriter, r *http.Request, apply func(*http.Request) (drills.Run, error)) {
	run, err := apply(r)
	if err != nil {
		writeRunError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, run)
}

func (h *runsHandler) handleStart(w http.ResponseWriter, r *http.Request) {
	h.transition(w, r, func(req *http.Request) (drills.Run, error) {
		return h.service.StartRun(req.Context(), req.PathValue("id"))
	})
}

func (h *runsHandler) handleComplete(w http.ResponseWriter, r *http.Request) {
	h.transition(w, r, func(req *http.Request) (drills.Run, error) {
		return h.service.CompleteRun(req.Context(), req.PathValue("id"))
	})
}

func (h *runsHandler) handleTerminate(w http.ResponseWriter, r *http.Request) {
	h.transition(w, r, func(req *http.Request) (drills.Run, error) {
		return h.service.TerminateRun(req.Context(), req.PathValue("id"))
	})
}

// writeRunError maps the drills service errors of the run resource to
// JSON error responses: validation errors become 400, unknown runs or
// scenarios 404, everything else 500.
func writeRunError(w http.ResponseWriter, err error) {
	var validationError *drills.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrRunNotFound),
		errors.Is(err, drills.ErrScenarioNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
