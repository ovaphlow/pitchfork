package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// assessmentsBase is the unified resource path of the drill assessments
// (演练考核评估). The collection and the item routes live under the
// owning run (/drills/{rid}/assessments and
// /drills/{rid}/assessments/{pointId}); the literal assessments segment
// is more specific than the /drills/{id} item route and never collides
// with it (same pattern as the step-record and sim-event routes).
const assessmentsBase = prototypePrefix + "/drills/{rid}/assessments"

// assessmentHandler adapts the drills service to the HTTP routing layer.
// It serves the per-run assessment collection (GET list) and the item
// routes (GET / PUT upsert / DELETE by point); other methods yield a
// JSON 405 with Allow. The resource is a PUT-upsert family (no POST):
// the first PUT of a (run, point) pair creates the assessment, later
// PUTs update it in place. The owning run comes from the route path: a
// missing run is a 404 on every route.
type assessmentHandler struct {
	service *drills.Service
}

func newAssessmentHandler(store drills.Store) *assessmentHandler {
	return &assessmentHandler{service: drills.NewService(store)}
}

func (h *assessmentHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.list(w, r)
	default:
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (h *assessmentHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// assessmentBody mirrors the client-supplied fields of the request body.
// run_id and point_id are never part of the body: they are decided by
// the route path. Score is a pointer so the handler can tell a missing
// field from an explicit 0: 0 is a legal score, so the Go zero value
// must not pass for the required field; only a provided value may.
// comment and created_by default to empty strings.
type assessmentBody struct {
	Score     *int   `json:"score"`
	Comment   string `json:"comment"`
	CreatedBy string `json:"created_by"`
}

// decodeAssessmentBody reads a single JSON object from the request body.
// An empty, malformed or non-object body (including a JSON null) yields
// a 400 { "error": ... } response, matching the decode convention of the
// other PUT resources; an empty object {} is legal but fails later on
// the missing score.
func decodeAssessmentBody(w http.ResponseWriter, r *http.Request) (assessmentBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return assessmentBody{}, false
	}
	var body assessmentBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return assessmentBody{}, false
	}
	return body, true
}

func (h *assessmentHandler) upsert(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeAssessmentBody(w, r)
	if !ok {
		return
	}
	if body.Score == nil {
		writeError(w, http.StatusBadRequest, "score required")
		return
	}
	assessment, err := h.service.UpsertAssessment(r.Context(), r.PathValue("rid"), r.PathValue("pointId"), drills.AssessmentInput{
		Score:     *body.Score,
		Comment:   body.Comment,
		CreatedBy: body.CreatedBy,
	})
	if err != nil {
		writeAssessmentError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, assessment)
}

// assessmentListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type assessmentListResponse struct {
	Records []drills.Assessment `json:"records"`
	Meta    metaResponse        `json:"meta"`
}

func (h *assessmentHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseDrillChildListFilter(w, r)
	if !ok {
		return
	}
	assessments, total, err := h.service.ListAssessments(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeAssessmentError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, assessmentListResponse{Records: assessments, Meta: metaResponse{Total: total}})
}

func (h *assessmentHandler) get(w http.ResponseWriter, r *http.Request) {
	assessment, err := h.service.GetAssessment(r.Context(), r.PathValue("rid"), r.PathValue("pointId"))
	if err != nil {
		writeAssessmentError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, assessment)
}

func (h *assessmentHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteAssessment(r.Context(), r.PathValue("rid"), r.PathValue("pointId")); err != nil {
		writeAssessmentError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeAssessmentError maps the drills service errors of the assessment
// resource to JSON error responses: validation errors become 400,
// unknown runs, points or assessments 404, everything else 500.
func writeAssessmentError(w http.ResponseWriter, err error) {
	var validationError *drills.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrRunNotFound),
		errors.Is(err, drills.ErrPointNotFound),
		errors.Is(err, drills.ErrAssessmentNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
