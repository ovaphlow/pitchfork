package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// stepsBase is the unified resource path of the drill scenario steps.
// The collection lives under the owning scenario
// (/scenarios/{sid}/steps); the item routes live at /steps/{id}.
const stepsBase = prototypePrefix + "/steps"

// stepsHandler adapts the drills service to the HTTP routing layer. It
// serves the per-scenario collection (GET list / POST create) and the
// item routes (GET / PUT / DELETE by id); other methods yield a JSON 405
// with Allow. The owning scenario comes from the route path; a missing
// scenario is a 404 on both collection routes.
type stepsHandler struct {
	service *drills.Service
}

func newStepsHandler(store drills.Store) *stepsHandler {
	return &stepsHandler{service: drills.NewService(store)}
}

func (h *stepsHandler) handleScenarioSteps(w http.ResponseWriter, r *http.Request) {
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

func (h *stepsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// stepBody mirrors the client-supplied fields of the request body.
// scenario_id is never part of the body: it is decided by the route
// path. sort_order and description are optional (defaults 0 and ”);
// created_by is optional (empty when omitted) because the prototype has
// no auth context.
type stepBody struct {
	SortOrder   int    `json:"sort_order"`
	Title       string `json:"title"`
	Description string `json:"description"`
	CreatedBy   string `json:"created_by"`
}

// decodeStepBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeStepBody(w http.ResponseWriter, r *http.Request) (stepBody, bool) {
	var body stepBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return stepBody{}, false
	}
	return body, true
}

func (h *stepsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeStepBody(w, r)
	if !ok {
		return
	}
	step, err := h.service.CreateStep(r.Context(), r.PathValue("sid"), drills.StepInput{
		SortOrder:   body.SortOrder,
		Title:       body.Title,
		Description: body.Description,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, step)
}

// stepListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type stepListResponse struct {
	Records []drills.ScenarioStep `json:"records"`
	Meta    metaResponse          `json:"meta"`
}

func (h *stepsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseDrillChildListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListSteps(r.Context(), r.PathValue("sid"), filter)
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, stepListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseDrillChildListFilter reads the limit/offset query parameters of
// the scenario child collections (steps and assessment points share the
// same pagination contract). They must be non-negative integers (default
// limit 50), otherwise 400. The children have no other filter
// dimensions: the owning scenario is fixed by the route path.
func parseDrillChildListFilter(w http.ResponseWriter, r *http.Request) (drills.ListFilter, bool) {
	query := r.URL.Query()
	filter := drills.ListFilter{Limit: defaultPageSize}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return drills.ListFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return drills.ListFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *stepsHandler) get(w http.ResponseWriter, r *http.Request) {
	step, err := h.service.GetStep(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, step)
}

func (h *stepsHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeStepBody(w, r)
	if !ok {
		return
	}
	step, err := h.service.UpdateStep(r.Context(), r.PathValue("id"), drills.StepInput{
		SortOrder:   body.SortOrder,
		Title:       body.Title,
		Description: body.Description,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, step)
}

func (h *stepsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteStep(r.Context(), r.PathValue("id")); err != nil {
		writeDrillChildError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeDrillChildError maps the drills service errors of the scenario
// child resources (steps and assessment points) to JSON error responses:
// validation errors become 400, unknown scenarios / steps / points 404,
// everything else 500.
func writeDrillChildError(w http.ResponseWriter, err error) {
	var validationError *drills.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrScenarioNotFound),
		errors.Is(err, drills.ErrStepNotFound),
		errors.Is(err, drills.ErrPointNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
