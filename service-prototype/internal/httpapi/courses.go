package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
)

// coursesBase is the unified resource path of the training courses.
const coursesBase = prototypePrefix + "/courses"

// defaultPageSize follows the repository list convention (limit ?: 50).
const defaultPageSize = 50

// coursesHandler adapts the courses service to the HTTP routing layer.
// It serves the collection (GET list / POST create) and the item routes
// (GET / PUT / DELETE by id); other methods yield a JSON 405 with Allow.
type coursesHandler struct {
	service *courses.Service
}

func newCoursesHandler(store courses.Store) *coursesHandler {
	return &coursesHandler{service: courses.NewService(store)}
}

func (h *coursesHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *coursesHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// courseBody mirrors the client-supplied fields of the request body.
// created_by is optional (empty when omitted) because the prototype has
// no auth context; metadata is an optional JSON object.
type courseBody struct {
	Title     string         `json:"title"`
	Topic     string         `json:"topic"`
	Type      string         `json:"type"`
	Status    string         `json:"status"`
	Metadata  map[string]any `json:"metadata"`
	CreatedBy string         `json:"created_by"`
}

// decodeCourseBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeCourseBody(w http.ResponseWriter, r *http.Request) (courseBody, bool) {
	var body courseBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return courseBody{}, false
	}
	return body, true
}

func (h *coursesHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeCourseBody(w, r)
	if !ok {
		return
	}
	course, err := h.service.Create(r.Context(), courses.Input{
		Title:     body.Title,
		Topic:     courses.Topic(body.Topic),
		Type:      courses.DeliveryType(body.Type),
		Status:    courses.Status(body.Status),
		Metadata:  body.Metadata,
		CreatedBy: body.CreatedBy,
	})
	if err != nil {
		writeCourseError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, course)
}

// listResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type listResponse struct {
	Records []courses.Course `json:"records"`
	Meta    metaResponse     `json:"meta"`
}

type metaResponse struct {
	Total int `json:"total"`
}

func (h *coursesHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.List(r.Context(), filter)
	if err != nil {
		writeCourseError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, listResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseListFilter reads the topic/type/status/limit/offset query
// parameters. A non-empty enum filter must be one of the allowed values
// and limit/offset must be non-negative integers, otherwise 400.
func parseListFilter(w http.ResponseWriter, r *http.Request) (courses.Filter, bool) {
	query := r.URL.Query()
	filter := courses.Filter{Limit: defaultPageSize}

	if raw := query.Get("topic"); raw != "" {
		topic := courses.Topic(raw)
		if !topic.Valid() {
			writeError(w, http.StatusBadRequest, "invalid topic")
			return courses.Filter{}, false
		}
		filter.Topic = topic
	}
	if raw := query.Get("type"); raw != "" {
		deliveryType := courses.DeliveryType(raw)
		if !deliveryType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid type")
			return courses.Filter{}, false
		}
		filter.Type = deliveryType
	}
	if raw := query.Get("status"); raw != "" {
		status := courses.Status(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return courses.Filter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return courses.Filter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return courses.Filter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *coursesHandler) get(w http.ResponseWriter, r *http.Request) {
	course, err := h.service.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeCourseError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, course)
}

func (h *coursesHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeCourseBody(w, r)
	if !ok {
		return
	}
	course, err := h.service.Update(r.Context(), r.PathValue("id"), courses.Input{
		Title:     body.Title,
		Topic:     courses.Topic(body.Topic),
		Type:      courses.DeliveryType(body.Type),
		Status:    courses.Status(body.Status),
		Metadata:  body.Metadata,
		CreatedBy: body.CreatedBy,
	})
	if err != nil {
		writeCourseError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, course)
}

func (h *coursesHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.Delete(r.Context(), r.PathValue("id")); err != nil {
		writeCourseError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeCourseError maps store/service errors to JSON error responses:
// validation errors become 400, unknown courses 404, everything else 500.
func writeCourseError(w http.ResponseWriter, err error) {
	var validationError *courses.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, courses.ErrNotFound):
		writeError(w, http.StatusNotFound, courses.ErrNotFound.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
