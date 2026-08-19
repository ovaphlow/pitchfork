package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// stepRecordsBase is the unified resource path of the drill step
// execution records. The collection and the item routes live under the
// owning run (/drills/{rid}/steps and /drills/{rid}/steps/{stepId}).
const stepRecordsBase = prototypePrefix + "/drills/{rid}/steps"

// stepRecordHandler adapts the drills service to the HTTP routing layer.
// It serves the per-run step record collection (GET list) and the item
// routes (GET / PUT upsert / DELETE by step); other methods yield a JSON
// 405 with Allow. The resource is a PUT-upsert family (no POST): the
// first PUT of a (run, step) pair creates the record, later PUTs update
// it in place. The owning run comes from the route path; a missing run
// is a 404 on every route.
type stepRecordHandler struct {
	service *drills.Service
}

func newStepRecordHandler(store drills.Store) *stepRecordHandler {
	return &stepRecordHandler{service: drills.NewService(store)}
}

func (h *stepRecordHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.list(w, r)
	default:
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (h *stepRecordHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// stepRecordBody mirrors the client-supplied fields of the request body.
// run_id and step_id are never part of the body: they are decided by the
// route path. status defaults to 待执行, action_note / performed_by /
// created_by to empty strings and performed_at to null (omitted or
// explicit JSON null); a provided performed_at must be a valid RFC 3339
// timestamp, otherwise 400.
type stepRecordBody struct {
	Status      string  `json:"status"`
	ActionNote  string  `json:"action_note"`
	PerformedBy string  `json:"performed_by"`
	PerformedAt *string `json:"performed_at"`
	CreatedBy   string  `json:"created_by"`
}

// decodeStepRecordBody reads a single JSON object from the request body.
// An empty, malformed or non-object body (including a JSON null) yields
// a 400 { "error": ... } response, matching the decode convention of the
// other PUT resources; an empty object {} is legal and resets every
// field to its default.
func decodeStepRecordBody(w http.ResponseWriter, r *http.Request) (stepRecordBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return stepRecordBody{}, false
	}
	var body stepRecordBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return stepRecordBody{}, false
	}
	return body, true
}

// parsePerformedAt converts the optional performed_at field: an omitted
// or explicit null field stays nil; a provided value must be a valid
// RFC 3339 timestamp, otherwise 400.
func parsePerformedAt(w http.ResponseWriter, raw *string) (*time.Time, bool) {
	if raw == nil {
		return nil, true
	}
	parsed, err := time.Parse(time.RFC3339, *raw)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid performed_at")
		return nil, false
	}
	return &parsed, true
}

func (h *stepRecordHandler) upsert(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeStepRecordBody(w, r)
	if !ok {
		return
	}
	performedAt, ok := parsePerformedAt(w, body.PerformedAt)
	if !ok {
		return
	}
	record, err := h.service.UpsertStepRecord(r.Context(), r.PathValue("rid"), r.PathValue("stepId"), drills.StepRecordInput{
		Status:      drills.StepRecordStatus(body.Status),
		ActionNote:  body.ActionNote,
		PerformedBy: body.PerformedBy,
		PerformedAt: performedAt,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeStepRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, record)
}

// stepRecordListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type stepRecordListResponse struct {
	Records []drills.StepRecord `json:"records"`
	Meta    metaResponse        `json:"meta"`
}

func (h *stepRecordHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseDrillChildListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListStepRecords(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeStepRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, stepRecordListResponse{Records: records, Meta: metaResponse{Total: total}})
}

func (h *stepRecordHandler) get(w http.ResponseWriter, r *http.Request) {
	record, err := h.service.GetStepRecord(r.Context(), r.PathValue("rid"), r.PathValue("stepId"))
	if err != nil {
		writeStepRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, record)
}

func (h *stepRecordHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteStepRecord(r.Context(), r.PathValue("rid"), r.PathValue("stepId")); err != nil {
		writeStepRecordError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeStepRecordError maps the drills service errors of the step record
// resource to JSON error responses: validation errors become 400,
// unknown runs, steps or records 404, everything else 500.
func writeStepRecordError(w http.ResponseWriter, err error) {
	var validationError *drills.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrRunNotFound),
		errors.Is(err, drills.ErrStepNotFound),
		errors.Is(err, drills.ErrStepRecordNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
