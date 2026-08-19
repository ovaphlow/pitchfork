package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// scenariosBase is the unified resource path of the drill scenario
// templates.
const scenariosBase = prototypePrefix + "/scenarios"

// scenariosHandler adapts the drills service to the HTTP routing layer.
// It serves the collection (GET list / POST create) and the item routes
// (GET / PUT / DELETE by id); other methods yield a JSON 405 with Allow.
type scenariosHandler struct {
	service *drills.Service
}

func newScenariosHandler(store drills.Store) *scenariosHandler {
	return &scenariosHandler{service: drills.NewService(store)}
}

func (h *scenariosHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *scenariosHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// scenarioBody mirrors the client-supplied fields of the request body.
// created_by is optional (empty when omitted) because the prototype has
// no auth context; metadata is an optional JSON object echoed verbatim.
type scenarioBody struct {
	Name       string         `json:"name"`
	Category   string         `json:"category"`
	Background string         `json:"background"`
	Status     string         `json:"status"`
	Metadata   map[string]any `json:"metadata"`
	CreatedBy  string         `json:"created_by"`
}

// decodeScenarioBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeScenarioBody(w http.ResponseWriter, r *http.Request) (scenarioBody, bool) {
	var body scenarioBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return scenarioBody{}, false
	}
	return body, true
}

func (h *scenariosHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeScenarioBody(w, r)
	if !ok {
		return
	}
	scenario, err := h.service.CreateScenario(r.Context(), drills.ScenarioInput{
		Name:       body.Name,
		Category:   drills.Category(body.Category),
		Background: body.Background,
		Status:     drills.ScenarioStatus(body.Status),
		Metadata:   body.Metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeScenarioError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, scenario)
}

// scenarioListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type scenarioListResponse struct {
	Records []drills.Scenario `json:"records"`
	Meta    metaResponse      `json:"meta"`
}

func (h *scenariosHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseScenarioListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListScenarios(r.Context(), filter)
	if err != nil {
		writeScenarioError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, scenarioListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseScenarioListFilter reads the category/status/limit/offset query
// parameters. A non-empty enum filter must be one of the allowed values
// and limit/offset must be non-negative integers, otherwise 400.
func parseScenarioListFilter(w http.ResponseWriter, r *http.Request) (drills.ScenarioFilter, bool) {
	query := r.URL.Query()
	filter := drills.ScenarioFilter{Limit: defaultPageSize}

	if raw := query.Get("category"); raw != "" {
		category := drills.Category(raw)
		if !category.Valid() {
			writeError(w, http.StatusBadRequest, "invalid category")
			return drills.ScenarioFilter{}, false
		}
		filter.Category = category
	}
	if raw := query.Get("status"); raw != "" {
		status := drills.ScenarioStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return drills.ScenarioFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return drills.ScenarioFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return drills.ScenarioFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *scenariosHandler) get(w http.ResponseWriter, r *http.Request) {
	scenario, err := h.service.GetScenario(r.Context(), r.PathValue("id"))
	if err != nil {
		writeScenarioError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, scenario)
}

func (h *scenariosHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeScenarioBody(w, r)
	if !ok {
		return
	}
	scenario, err := h.service.UpdateScenario(r.Context(), r.PathValue("id"), drills.ScenarioInput{
		Name:       body.Name,
		Category:   drills.Category(body.Category),
		Background: body.Background,
		Status:     drills.ScenarioStatus(body.Status),
		Metadata:   body.Metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeScenarioError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, scenario)
}

func (h *scenariosHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteScenario(r.Context(), r.PathValue("id")); err != nil {
		writeScenarioError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeScenarioError maps store/service errors to JSON error responses:
// validation errors become 400, unknown scenarios 404, everything else
// 500.
func writeScenarioError(w http.ResponseWriter, err error) {
	var validationError *drills.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrScenarioNotFound):
		writeError(w, http.StatusNotFound, drills.ErrScenarioNotFound.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
