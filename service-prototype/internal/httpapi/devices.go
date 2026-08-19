package httpapi

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// devicesBase is the unified resource path of the dispatch device
// running-status reports (设备运行状态上报). The collection and the item
// routes live under the owning run (/drills/{rid}/devices and
// /drills/{rid}/devices/{did}); the literal devices segment is more
// specific than the /drills/{id} item route and never collides with it
// (same pattern as the step-record, sim-event, assessment,
// command-session, orders, departments, messages and zone-density
// routes). Reports are recorded with POST, updated in place with PUT
// and removed with DELETE.
const devicesBase = prototypePrefix + "/drills/{rid}/devices"

// devicesHandler adapts the dispatch service to the HTTP routing layer.
// It serves the per-run device collection (GET list / POST create) and
// the item routes (GET / PUT / DELETE by report id); other methods
// yield a JSON 405 with Allow. The owning run comes from the route
// path: writes require the run to be 进行中 (400 otherwise) and a
// missing run is a 404 on every route (checked before the write gate).
type devicesHandler struct {
	service *dispatch.Service
}

// newDevicesHandler builds the handler over the dispatch store and the
// drill store; the drill store backs the run source of the service (the
// run existence check and the write gate).
func newDevicesHandler(drillStore drills.Store, dispatchStore dispatch.Store) *devicesHandler {
	return &devicesHandler{service: dispatch.NewService(dispatchStore, dispatch.NewRunSource(drillStore))}
}

func (h *devicesHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *devicesHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// deviceBody mirrors the client-supplied fields of a device status
// report. id, run_id and the timestamps are never part of the body: id
// is server-generated, run_id comes from the route path (a body that
// carries run_id has it ignored). device_name and device_type are
// required; status defaults to 正常 (an explicit value must be one of
// 正常/告警/离线); note defaults to an empty string; created_by passes
// through at creation (empty when omitted, the prototype has no auth
// context) and is preserved on update (a body that carries created_by
// on PUT has it ignored).
type deviceBody struct {
	DeviceName string `json:"device_name"`
	DeviceType string `json:"device_type"`
	Status     string `json:"status"`
	Note       string `json:"note"`
	CreatedBy  string `json:"created_by"`
}

func (h *devicesHandler) create(w http.ResponseWriter, r *http.Request) {
	var body deviceBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	device, err := h.service.CreateDevice(r.Context(), r.PathValue("rid"), dispatch.DeviceInput{
		DeviceName: body.DeviceName,
		DeviceType: dispatch.DeviceType(body.DeviceType),
		Status:     dispatch.DeviceStatus(body.Status),
		Note:       body.Note,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeDeviceError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, device)
}

// deviceListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type deviceListResponse struct {
	Records []dispatch.Device `json:"records"`
	Meta    metaResponse      `json:"meta"`
}

func (h *devicesHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseDeviceListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListDevices(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeDeviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, deviceListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseDeviceListFilter reads the device_type/status/limit/offset query
// parameters. A non-empty enum filter must be one of the allowed
// values, otherwise 400; limit/offset must be non-negative integers
// (default limit 50), otherwise 400.
func parseDeviceListFilter(w http.ResponseWriter, r *http.Request) (dispatch.DeviceFilter, bool) {
	query := r.URL.Query()
	filter := dispatch.DeviceFilter{Limit: defaultPageSize}

	if raw := query.Get("device_type"); raw != "" {
		deviceType := dispatch.DeviceType(raw)
		if !deviceType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid device_type")
			return dispatch.DeviceFilter{}, false
		}
		filter.DeviceType = deviceType
	}
	if raw := query.Get("status"); raw != "" {
		status := dispatch.DeviceStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return dispatch.DeviceFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return dispatch.DeviceFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return dispatch.DeviceFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *devicesHandler) get(w http.ResponseWriter, r *http.Request) {
	device, err := h.service.GetDevice(r.Context(), r.PathValue("rid"), r.PathValue("did"))
	if err != nil {
		writeDeviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, device)
}

func (h *devicesHandler) update(w http.ResponseWriter, r *http.Request) {
	var body deviceBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	// created_by is never updatable: the service preserves the value
	// recorded at creation.
	device, err := h.service.UpdateDevice(r.Context(), r.PathValue("rid"), r.PathValue("did"), dispatch.DeviceInput{
		DeviceName: body.DeviceName,
		DeviceType: dispatch.DeviceType(body.DeviceType),
		Status:     dispatch.DeviceStatus(body.Status),
		Note:       body.Note,
	})
	if err != nil {
		writeDeviceError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, device)
}

func (h *devicesHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteDevice(r.Context(), r.PathValue("rid"), r.PathValue("did")); err != nil {
		writeDeviceError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeDeviceError maps the dispatch service errors of the device
// resource to JSON error responses: validation errors become 400,
// unknown runs or reports 404, everything else 500.
func writeDeviceError(w http.ResponseWriter, err error) {
	var validationError *dispatch.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, dispatch.ErrRunNotFound),
		errors.Is(err, dispatch.ErrDeviceNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
