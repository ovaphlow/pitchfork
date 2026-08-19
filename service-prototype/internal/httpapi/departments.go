package httpapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// departmentsBase is the unified resource path of the dispatch
// department reports (部门联动处置记录). The collection and the item
// routes live under the owning run (/drills/{rid}/departments and
// /drills/{rid}/departments/{department}); the literal departments
// segment is more specific than the /drills/{id} item route and never
// collides with it (same pattern as the step-record, sim-event,
// assessment, command-session and orders routes). The report of a
// (run, department) pair is upserted with PUT and removed with DELETE;
// it is never created via POST, so the collection only serves GET.
const departmentsBase = prototypePrefix + "/drills/{rid}/departments"

// departmentsHandler adapts the dispatch service to the HTTP routing
// layer. It serves the per-run collection (GET list only) and the item
// routes (PUT upsert / DELETE by department); other methods yield a
// JSON 405 with Allow. The owning run and the linkage department come
// from the route path: writes require the run to be 进行中 (400
// otherwise) and a missing run is a 404 on every route.
type departmentsHandler struct {
	service *dispatch.Service
}

// newDepartmentsHandler builds the handler over the dispatch store and
// the drill store; the drill store backs the run source of the service
// (the run existence check and the write gate).
func newDepartmentsHandler(drillStore drills.Store, dispatchStore dispatch.Store) *departmentsHandler {
	return &departmentsHandler{service: dispatch.NewService(dispatchStore, dispatch.NewRunSource(drillStore))}
}

func (h *departmentsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.list(w, r)
	default:
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (h *departmentsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodPut:
		h.upsert(w, r)
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "PUT, DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// departmentReportBody mirrors the client-supplied fields of a
// department-report upsert. id, run_id and department are never part of
// the body: they are decided by the route path and the service (a body
// that carries them has them ignored). There are no required fields:
// status defaults to 未响应 on create and keeps the current value on
// update (an explicit value must be one of the five statuses and an
// adjacent forward transition on update); note defaults to an empty
// string and must be a JSON string; arrived_at is an optional RFC3339
// instant (explicit null clears it; non-string values are rejected);
// created_by passes through (empty when omitted) because the prototype
// has no auth context.
type departmentReportBody struct {
	Status    string          `json:"status"`
	Note      string          `json:"note"`
	ArrivedAt json.RawMessage `json:"arrived_at"`
	CreatedBy string          `json:"created_by"`
}

// parseDepartmentArrivedAt converts the raw arrived_at field. An
// omitted field or an explicit null yields a nil instant; any other
// value must be a JSON string in RFC3339 format, otherwise 400.
func parseDepartmentArrivedAt(w http.ResponseWriter, raw json.RawMessage) (*time.Time, bool) {
	if raw == nil {
		return nil, true
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		return nil, true
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid arrived_at")
		return nil, false
	}
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid arrived_at")
		return nil, false
	}
	return &parsed, true
}

func (h *departmentsHandler) upsert(w http.ResponseWriter, r *http.Request) {
	var body departmentReportBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	arrivedAt, ok := parseDepartmentArrivedAt(w, body.ArrivedAt)
	if !ok {
		return
	}
	report, err := h.service.UpsertDepartment(r.Context(), r.PathValue("rid"),
		dispatch.Department(r.PathValue("department")), dispatch.DepartmentReportInput{
			Status:    dispatch.DepartmentStatus(body.Status),
			Note:      body.Note,
			ArrivedAt: arrivedAt,
			CreatedBy: body.CreatedBy,
		})
	if err != nil {
		writeDepartmentError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, report)
}

// departmentListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type departmentListResponse struct {
	Records []dispatch.DepartmentReport `json:"records"`
	Meta    metaResponse                `json:"meta"`
}

func (h *departmentsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseDepartmentListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListDepartments(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeDepartmentError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, departmentListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseDepartmentListFilter reads the department/status/limit/offset
// query parameters. A non-empty enum filter must be one of the allowed
// values, otherwise 400; limit/offset must be non-negative integers
// (default limit 50), otherwise 400.
func parseDepartmentListFilter(w http.ResponseWriter, r *http.Request) (dispatch.DepartmentFilter, bool) {
	query := r.URL.Query()
	filter := dispatch.DepartmentFilter{Limit: defaultPageSize}

	if raw := query.Get("department"); raw != "" {
		department := dispatch.Department(raw)
		if !department.Valid() {
			writeError(w, http.StatusBadRequest, "invalid department")
			return dispatch.DepartmentFilter{}, false
		}
		filter.Department = department
	}
	if raw := query.Get("status"); raw != "" {
		status := dispatch.DepartmentStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return dispatch.DepartmentFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return dispatch.DepartmentFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return dispatch.DepartmentFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *departmentsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteDepartment(r.Context(), r.PathValue("rid"),
		dispatch.Department(r.PathValue("department"))); err != nil {
		writeDepartmentError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeDepartmentError maps the dispatch service errors of the
// department-report resource to JSON error responses: validation errors
// become 400, unknown runs or reports 404, everything else 500.
func writeDepartmentError(w http.ResponseWriter, err error) {
	var validationError *dispatch.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, dispatch.ErrRunNotFound),
		errors.Is(err, dispatch.ErrDepartmentNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
