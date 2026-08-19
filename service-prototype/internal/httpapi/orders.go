package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ordersBase is the unified resource path of the dispatch orders (调度指
// 令). The collection and the item routes live under the owning run
// (/drills/{rid}/orders and /drills/{rid}/orders/{oid}); the literal
// orders segment is more specific than the /drills/{id} item route and
// never collides with it (same pattern as the step-record, sim-event
// and command-session routes).
const ordersBase = prototypePrefix + "/drills/{rid}/orders"

// ordersHandler adapts the dispatch service to the HTTP routing layer.
// It serves the per-run order collection (GET list / POST create) and
// the item routes (GET / PUT / DELETE by order id); other methods yield
// a JSON 405 with Allow. The owning run comes from the route path:
// writes require the run to be 进行中 (400 otherwise) and a missing run
// is a 404 on every route.
type ordersHandler struct {
	service *dispatch.Service
}

// newOrdersHandler builds the handler over the dispatch store and the
// drill store; the drill store backs the run source of the service (the
// run existence check and the write gate).
func newOrdersHandler(drillStore drills.Store, dispatchStore dispatch.Store) *ordersHandler {
	return &ordersHandler{service: dispatch.NewService(dispatchStore, dispatch.NewRunSource(drillStore))}
}

func (h *ordersHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *ordersHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// orderBody mirrors the client-supplied fields of an order creation.
// id, run_id and the timestamps are never part of the body: id is
// server-generated, run_id comes from the route path (a body that
// carries run_id has it ignored), issued_at is set by the service at
// creation. title/content/target_type/target_name are required;
// priority defaults to 普通; status defaults to 待接收 (an explicit value
// must be exactly 待接收); feedback defaults to an empty string;
// deadline is an optional RFC3339 instant; created_by passes through
// (empty when omitted) because the prototype has no auth context.
// deadline is kept as raw JSON so the handler can tell an omitted field
// from an explicit null.
type orderBody struct {
	Title      string          `json:"title"`
	Content    string          `json:"content"`
	Priority   string          `json:"priority"`
	TargetType string          `json:"target_type"`
	TargetName string          `json:"target_name"`
	Status     string          `json:"status"`
	Feedback   string          `json:"feedback"`
	Deadline   json.RawMessage `json:"deadline"`
	CreatedBy  string          `json:"created_by"`
}

// orderUpdateBody mirrors the client-supplied fields of an order update
// (partial update). Pointer fields tell an omitted field from an
// explicitly provided one (including an empty string, which the service
// rejects for the required fields and accepts for feedback); deadline
// is kept as raw JSON so an omitted field keeps the current value while
// an explicit null clears it. run_id, issued_at and created_by are
// never updatable.
type orderUpdateBody struct {
	Title      *string         `json:"title"`
	Content    *string         `json:"content"`
	Priority   *string         `json:"priority"`
	TargetType *string         `json:"target_type"`
	TargetName *string         `json:"target_name"`
	Status     *string         `json:"status"`
	Feedback   *string         `json:"feedback"`
	Deadline   json.RawMessage `json:"deadline"`
}

// decodeOrderJSON reads a single non-null JSON object from the request
// body into the given target. An empty, malformed or non-object body
// (including a JSON null) yields a 400 { "error": ... } response,
// matching the decode convention of the other resources; an empty
// object {} is legal (an all-default create or a no-op update).
func decodeOrderJSON(w http.ResponseWriter, r *http.Request, target any) bool {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return false
	}
	if err := json.Unmarshal(raw, target); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return false
	}
	return true
}

// parseOrderDeadline converts the raw deadline field. An omitted field
// yields (nil, false): the service leaves the deadline null on create
// and keeps the current value on update. An explicit null yields
// (nil, true): the deadline is cleared. Any other value must be a JSON
// string in RFC3339 format, otherwise 400.
func parseOrderDeadline(w http.ResponseWriter, raw json.RawMessage) (*time.Time, bool, bool) {
	if raw == nil {
		return nil, false, true
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		return nil, true, true
	}
	var value string
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid deadline")
		return nil, false, false
	}
	parsed, err := time.Parse(time.RFC3339, value)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid deadline")
		return nil, false, false
	}
	return &parsed, true, true
}

func (h *ordersHandler) create(w http.ResponseWriter, r *http.Request) {
	var body orderBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	deadline, _, ok := parseOrderDeadline(w, body.Deadline)
	if !ok {
		return
	}
	order, err := h.service.CreateOrder(r.Context(), r.PathValue("rid"), dispatch.OrderInput{
		Title:      body.Title,
		Content:    body.Content,
		Priority:   dispatch.Priority(body.Priority),
		TargetType: dispatch.TargetType(body.TargetType),
		TargetName: body.TargetName,
		Status:     dispatch.OrderStatus(body.Status),
		Feedback:   body.Feedback,
		Deadline:   deadline,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeOrderError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, order)
}

// orderListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type orderListResponse struct {
	Records []dispatch.Order `json:"records"`
	Meta    metaResponse     `json:"meta"`
}

func (h *ordersHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseOrderListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListOrders(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeOrderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, orderListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseOrderListFilter reads the status/priority/target_type/limit/
// offset query parameters. A non-empty enum filter must be one of the
// allowed values, otherwise 400; limit/offset must be non-negative
// integers (default limit 50), otherwise 400.
func parseOrderListFilter(w http.ResponseWriter, r *http.Request) (dispatch.OrderFilter, bool) {
	query := r.URL.Query()
	filter := dispatch.OrderFilter{Limit: defaultPageSize}

	if raw := query.Get("status"); raw != "" {
		status := dispatch.OrderStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return dispatch.OrderFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("priority"); raw != "" {
		priority := dispatch.Priority(raw)
		if !priority.Valid() {
			writeError(w, http.StatusBadRequest, "invalid priority")
			return dispatch.OrderFilter{}, false
		}
		filter.Priority = priority
	}
	if raw := query.Get("target_type"); raw != "" {
		targetType := dispatch.TargetType(raw)
		if !targetType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid target_type")
			return dispatch.OrderFilter{}, false
		}
		filter.TargetType = targetType
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return dispatch.OrderFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return dispatch.OrderFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *ordersHandler) get(w http.ResponseWriter, r *http.Request) {
	order, err := h.service.GetOrder(r.Context(), r.PathValue("rid"), r.PathValue("oid"))
	if err != nil {
		writeOrderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, order)
}

func (h *ordersHandler) update(w http.ResponseWriter, r *http.Request) {
	var body orderUpdateBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	deadline, hasDeadline, ok := parseOrderDeadline(w, body.Deadline)
	if !ok {
		return
	}
	update := dispatch.OrderUpdate{
		Title:       body.Title,
		Content:     body.Content,
		TargetName:  body.TargetName,
		Feedback:    body.Feedback,
		HasDeadline: hasDeadline,
		Deadline:    deadline,
	}
	if body.Priority != nil {
		priority := dispatch.Priority(*body.Priority)
		update.Priority = &priority
	}
	if body.TargetType != nil {
		targetType := dispatch.TargetType(*body.TargetType)
		update.TargetType = &targetType
	}
	if body.Status != nil {
		status := dispatch.OrderStatus(*body.Status)
		update.Status = &status
	}
	order, err := h.service.UpdateOrder(r.Context(), r.PathValue("rid"), r.PathValue("oid"), update)
	if err != nil {
		writeOrderError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, order)
}

func (h *ordersHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteOrder(r.Context(), r.PathValue("rid"), r.PathValue("oid")); err != nil {
		writeOrderError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOrderError maps the dispatch service errors of the order
// resource to JSON error responses: validation errors become 400,
// unknown runs or orders 404, everything else 500.
func writeOrderError(w http.ResponseWriter, err error) {
	var validationError *dispatch.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, dispatch.ErrRunNotFound),
		errors.Is(err, dispatch.ErrOrderNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
