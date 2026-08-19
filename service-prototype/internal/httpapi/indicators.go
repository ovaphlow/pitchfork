package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
)

// indicatorsBase is the unified resource path of the evaluation
// indicator dictionary. The collection lives at
// /evaluation/indicators; the item routes at /evaluation/indicators/{id}.
const indicatorsBase = prototypePrefix + "/evaluation/indicators"

// indicatorsHandler adapts the evaluation service to the HTTP routing
// layer. It serves the dictionary collection (GET list with dimension
// filter and limit/offset pagination / POST create) and the item routes
// (GET / PUT / DELETE by id); other methods yield a JSON 405 with
// Allow. The score-ref checker (rejecting the deletion of indicators
// referenced by evaluation scores) is wired at the composition root by
// the evaluation_scores card (000024); without it deletions behave as
// before.
type indicatorsHandler struct {
	service *evaluation.Service
}

func newIndicatorsHandler(store evaluation.Store) *indicatorsHandler {
	return &indicatorsHandler{service: evaluation.NewService(store)}
}

func (h *indicatorsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *indicatorsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// indicatorBody mirrors the client-supplied fields of the request body.
// id and the timestamps are never accepted from the client (the id is a
// server-generated ULID, the timestamps are server-maintained). weight,
// demo and sort_order are pointers so a missing field can be told apart
// from an explicit zero: an omitted weight is replaced by the default 1,
// while an explicit 0 (or any value below 1) is a 400. description and
// created_by are optional (empty when omitted) because the prototype has
// no auth context.
type indicatorBody struct {
	Dimension   evaluation.Dimension `json:"dimension"`
	Title       string               `json:"title"`
	Weight      *int                 `json:"weight"`
	Demo        *bool                `json:"demo"`
	SortOrder   *int                 `json:"sort_order"`
	Description string               `json:"description"`
	CreatedBy   string               `json:"created_by"`
}

func (body indicatorBody) input() evaluation.IndicatorInput {
	return evaluation.IndicatorInput{
		Dimension:   body.Dimension,
		Title:       body.Title,
		Weight:      body.Weight,
		Demo:        body.Demo,
		SortOrder:   body.SortOrder,
		Description: body.Description,
		CreatedBy:   body.CreatedBy,
	}
}

// decodeIndicatorBody reads a single JSON object from the request body;
// a malformed or empty body yields a 400 { "error": ... } response.
func decodeIndicatorBody(w http.ResponseWriter, r *http.Request) (indicatorBody, bool) {
	var body indicatorBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return indicatorBody{}, false
	}
	return body, true
}

func (h *indicatorsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeIndicatorBody(w, r)
	if !ok {
		return
	}
	indicator, err := h.service.CreateIndicator(r.Context(), body.input())
	if err != nil {
		writeIndicatorError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, indicator)
}

// indicatorListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type indicatorListResponse struct {
	Records []evaluation.Indicator `json:"records"`
	Meta    metaResponse           `json:"meta"`
}

func (h *indicatorsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseIndicatorListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListIndicators(r.Context(), filter)
	if err != nil {
		writeIndicatorError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, indicatorListResponse{Records: records, Meta: metaResponse{Total: total}})
}

func (h *indicatorsHandler) get(w http.ResponseWriter, r *http.Request) {
	indicator, err := h.service.GetIndicator(r.Context(), r.PathValue("id"))
	if err != nil {
		writeIndicatorError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, indicator)
}

func (h *indicatorsHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeIndicatorBody(w, r)
	if !ok {
		return
	}
	indicator, err := h.service.UpdateIndicator(r.Context(), r.PathValue("id"), body.input())
	if err != nil {
		writeIndicatorError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, indicator)
}

func (h *indicatorsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteIndicator(r.Context(), r.PathValue("id")); err != nil {
		writeIndicatorError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// parseIndicatorListFilter reads the dimension/limit/offset query
// parameters. A non-empty dimension must be one of the six allowed
// values, otherwise 400; limit/offset must be non-negative integers
// (default limit 50), otherwise 400.
func parseIndicatorListFilter(w http.ResponseWriter, r *http.Request) (evaluation.IndicatorFilter, bool) {
	query := r.URL.Query()
	filter := evaluation.IndicatorFilter{Limit: defaultPageSize}

	if raw := query.Get("dimension"); raw != "" {
		dimension := evaluation.Dimension(raw)
		if !dimension.Valid() {
			writeError(w, http.StatusBadRequest, "invalid dimension")
			return evaluation.IndicatorFilter{}, false
		}
		filter.Dimension = dimension
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return evaluation.IndicatorFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return evaluation.IndicatorFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

// writeIndicatorError maps the evaluation service errors to JSON error
// responses: validation errors and the delete reference rejection
// become 400, unknown indicators 404, everything else 500.
func writeIndicatorError(w http.ResponseWriter, err error) {
	var validationError *evaluation.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, evaluation.ErrIndicatorNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, evaluation.ErrIndicatorReferenced):
		writeError(w, http.StatusBadRequest, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
