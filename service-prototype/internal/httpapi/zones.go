package httpapi

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// zonesBase is the unified resource path of the dispatch zone-density
// reports (区域人流热力上报). The collection and the item routes live
// under the owning run (/drills/{rid}/zone-densities and
// /drills/{rid}/zone-densities/{zid}); the literal zone-densities
// segment is more specific than the /drills/{id} item route and never
// collides with it (same pattern as the step-record, sim-event,
// assessment, command-session, orders, departments and messages
// routes). Reports are recorded with POST, updated in place with PUT
// and removed with DELETE.
const zonesBase = prototypePrefix + "/drills/{rid}/zone-densities"

// zonesHandler adapts the dispatch service to the HTTP routing layer.
// It serves the per-run zone-density collection (GET list / POST
// create) and the item routes (GET / PUT / DELETE by report id); other
// methods yield a JSON 405 with Allow. The owning run comes from the
// route path: writes require the run to be 进行中 (400 otherwise) and a
// missing run is a 404 on every route (checked before the write gate).
type zonesHandler struct {
	service *dispatch.Service
}

// newZonesHandler builds the handler over the dispatch store and the
// drill store; the drill store backs the run source of the service (the
// run existence check and the write gate).
func newZonesHandler(drillStore drills.Store, dispatchStore dispatch.Store) *zonesHandler {
	return &zonesHandler{service: dispatch.NewService(dispatchStore, dispatch.NewRunSource(drillStore))}
}

func (h *zonesHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *zonesHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// zoneDensityBody mirrors the client-supplied fields of a zone-density
// report. id, run_id, reported_at and the timestamps are never part of
// the body: id is server-generated, run_id comes from the route path (a
// body that carries run_id has it ignored), reported_at is set by the
// service at creation and refreshed on update. zone_name and
// people_count are required (people_count must be a non-negative
// integer; a non-number, non-integer or negative value is a 400);
// created_by passes through at creation (empty when omitted, the
// prototype has no auth context) and is preserved on update (a body
// that carries created_by on PUT has it ignored).
type zoneDensityBody struct {
	ZoneName    string `json:"zone_name"`
	PeopleCount *int   `json:"people_count"`
	CreatedBy   string `json:"created_by"`
}

func (h *zonesHandler) create(w http.ResponseWriter, r *http.Request) {
	var body zoneDensityBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	density, err := h.service.CreateZoneDensity(r.Context(), r.PathValue("rid"), dispatch.ZoneDensityInput{
		ZoneName:    body.ZoneName,
		PeopleCount: body.PeopleCount,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeZoneDensityError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, density)
}

// zoneDensityListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type zoneDensityListResponse struct {
	Records []dispatch.ZoneDensity `json:"records"`
	Meta    metaResponse           `json:"meta"`
}

func (h *zonesHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseZoneDensityListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListZoneDensities(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeZoneDensityError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, zoneDensityListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseZoneDensityListFilter reads the zone_name/limit/offset query
// parameters. zone_name filters by exact match; limit/offset must be
// non-negative integers (default limit 50), otherwise 400.
func parseZoneDensityListFilter(w http.ResponseWriter, r *http.Request) (dispatch.ZoneDensityFilter, bool) {
	query := r.URL.Query()
	filter := dispatch.ZoneDensityFilter{Limit: defaultPageSize}

	if raw := query.Get("zone_name"); raw != "" {
		filter.ZoneName = raw
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return dispatch.ZoneDensityFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return dispatch.ZoneDensityFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *zonesHandler) get(w http.ResponseWriter, r *http.Request) {
	density, err := h.service.GetZoneDensity(r.Context(), r.PathValue("rid"), r.PathValue("zid"))
	if err != nil {
		writeZoneDensityError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, density)
}

func (h *zonesHandler) update(w http.ResponseWriter, r *http.Request) {
	var body zoneDensityBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	// created_by is never updatable: the service preserves the value
	// recorded at creation.
	density, err := h.service.UpdateZoneDensity(r.Context(), r.PathValue("rid"), r.PathValue("zid"), dispatch.ZoneDensityInput{
		ZoneName:    body.ZoneName,
		PeopleCount: body.PeopleCount,
	})
	if err != nil {
		writeZoneDensityError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, density)
}

func (h *zonesHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteZoneDensity(r.Context(), r.PathValue("rid"), r.PathValue("zid")); err != nil {
		writeZoneDensityError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeZoneDensityError maps the dispatch service errors of the
// zone-density resource to JSON error responses: validation errors
// become 400, unknown runs or reports 404, everything else 500.
func writeZoneDensityError(w http.ResponseWriter, err error) {
	var validationError *dispatch.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, dispatch.ErrRunNotFound),
		errors.Is(err, dispatch.ErrZoneDensityNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
