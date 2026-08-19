package httpapi

import (
	"encoding/json"
	"io"
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// assessmentPointsBase is the unified resource path of the drill
// assessment points. The collection lives under the owning scenario
// (/scenarios/{sid}/assessment-points); the item routes live at
// /assessment-points/{id}.
const assessmentPointsBase = prototypePrefix + "/assessment-points"

// assessmentPointsHandler adapts the drills service to the HTTP routing
// layer. It serves the per-scenario collection (GET list / POST create)
// and the item routes (GET / PUT / DELETE by id); other methods yield a
// JSON 405 with Allow. The owning scenario comes from the route path; a
// missing scenario is a 404 on both collection routes.
type assessmentPointsHandler struct {
	service *drills.Service
}

func newAssessmentPointsHandler(store drills.Store) *assessmentPointsHandler {
	return &assessmentPointsHandler{service: drills.NewService(store)}
}

func (h *assessmentPointsHandler) handleScenarioPoints(w http.ResponseWriter, r *http.Request) {
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

func (h *assessmentPointsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// pointBody mirrors the client-supplied fields of the request body.
// scenario_id is never part of the body: it is decided by the route
// path. description is optional (default ”); created_by is optional
// (empty when omitted) because the prototype has no auth context.
type pointBody struct {
	Title       string `json:"title"`
	Description string `json:"description"`
	CreatedBy   string `json:"created_by"`
}

// decodePointBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodePointBody(w http.ResponseWriter, r *http.Request) (pointBody, bool) {
	var body pointBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return pointBody{}, false
	}
	return body, true
}

func (h *assessmentPointsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodePointBody(w, r)
	if !ok {
		return
	}
	point, err := h.service.CreatePoint(r.Context(), r.PathValue("sid"), drills.PointInput{
		Title:       body.Title,
		Description: body.Description,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, point)
}

// pointListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type pointListResponse struct {
	Records []drills.AssessmentPoint `json:"records"`
	Meta    metaResponse             `json:"meta"`
}

func (h *assessmentPointsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseDrillChildListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListPoints(r.Context(), r.PathValue("sid"), filter)
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, pointListResponse{Records: records, Meta: metaResponse{Total: total}})
}

func (h *assessmentPointsHandler) get(w http.ResponseWriter, r *http.Request) {
	point, err := h.service.GetPoint(r.Context(), r.PathValue("id"))
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, point)
}

func (h *assessmentPointsHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodePointBody(w, r)
	if !ok {
		return
	}
	point, err := h.service.UpdatePoint(r.Context(), r.PathValue("id"), drills.PointInput{
		Title:       body.Title,
		Description: body.Description,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeDrillChildError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, point)
}

func (h *assessmentPointsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeletePoint(r.Context(), r.PathValue("id")); err != nil {
		writeDrillChildError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}
