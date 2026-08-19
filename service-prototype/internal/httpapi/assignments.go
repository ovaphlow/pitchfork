package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
)

// assignmentsBase is the unified resource path of the training task
// assignments.
const assignmentsBase = prototypePrefix + "/assignments"

// assignmentsHandler adapts the assignments service to the HTTP routing
// layer. It serves the collection (GET list / POST create) and the item
// route (DELETE by id; the card scope has no single GET or PUT); other
// methods yield a JSON 405 with Allow. The course store is injected for
// course existence checks (404) on create.
type assignmentsHandler struct {
	service *assignments.Service
}

func newAssignmentsHandler(store assignments.Store, courseStore courses.Store) *assignmentsHandler {
	return &assignmentsHandler{service: assignments.NewService(store, courseStore)}
}

func (h *assignmentsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *assignmentsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// assignmentBody mirrors the client-supplied fields of the request body.
// trigger_rule is captured raw so an omitted field (default {}) can be
// told apart from an explicit JSON null (rejected); created_by is
// optional (empty when omitted) because the prototype has no auth
// context.
type assignmentBody struct {
	CourseID    string          `json:"course_id"`
	AssignType  string          `json:"assign_type"`
	TriggerRule json.RawMessage `json:"trigger_rule"`
	Deadline    string          `json:"deadline"`
	TargetType  string          `json:"target_type"`
	TargetIDs   []string        `json:"target_ids"`
	CreatedBy   string          `json:"created_by"`
}

// decodeAssignmentBody reads a single JSON object from the request body;
// a malformed or empty body yields a 400 { "error": ... } response.
func decodeAssignmentBody(w http.ResponseWriter, r *http.Request) (assignmentBody, bool) {
	var body assignmentBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return assignmentBody{}, false
	}
	return body, true
}

// parseTriggerRule converts the raw trigger_rule field into a JSON
// object. An omitted field defaults to an empty object; a provided value
// must be a JSON object — an array, string, number or null is rejected
// with 400.
func parseTriggerRule(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool) {
	if len(raw) == 0 {
		return map[string]any{}, true
	}
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid trigger_rule")
		return nil, false
	}
	object, ok := value.(map[string]any)
	if !ok {
		writeError(w, http.StatusBadRequest, "trigger_rule must be a JSON object")
		return nil, false
	}
	return object, true
}

func (h *assignmentsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeAssignmentBody(w, r)
	if !ok {
		return
	}
	triggerRule, ok := parseTriggerRule(w, body.TriggerRule)
	if !ok {
		return
	}
	assignment, err := h.service.Create(r.Context(), assignments.Input{
		CourseID:    body.CourseID,
		AssignType:  assignments.AssignType(body.AssignType),
		TriggerRule: triggerRule,
		Deadline:    body.Deadline,
		TargetType:  assignments.TargetType(body.TargetType),
		TargetIDs:   body.TargetIDs,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeAssignmentError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, assignment)
}

// assignmentListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type assignmentListResponse struct {
	Records []assignments.Assignment `json:"records"`
	Meta    metaResponse             `json:"meta"`
}

func (h *assignmentsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseAssignmentListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.List(r.Context(), filter)
	if err != nil {
		writeAssignmentError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, assignmentListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseAssignmentListFilter reads the course_id/employee_id/target_type/
// limit/offset query parameters. A non-empty target_type must be one of
// the allowed values and limit/offset must be non-negative integers,
// otherwise 400. course_id and employee_id are plain strings (a
// course_id pointing to a missing course matches nothing).
func parseAssignmentListFilter(w http.ResponseWriter, r *http.Request) (assignments.Filter, bool) {
	query := r.URL.Query()
	filter := assignments.Filter{Limit: defaultPageSize}
	filter.CourseID = query.Get("course_id")
	filter.EmployeeID = query.Get("employee_id")
	if raw := query.Get("target_type"); raw != "" {
		targetType := assignments.TargetType(raw)
		if !targetType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid target_type")
			return assignments.Filter{}, false
		}
		filter.TargetType = targetType
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return assignments.Filter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return assignments.Filter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *assignmentsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.Delete(r.Context(), r.PathValue("id")); err != nil {
		writeAssignmentError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeAssignmentError maps store/service errors to JSON error
// responses: validation errors become 400, unknown assignments and
// unknown courses 404, everything else 500.
func writeAssignmentError(w http.ResponseWriter, err error) {
	var validationError *assignments.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, assignments.ErrNotFound), errors.Is(err, assignments.ErrCourseNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
