package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
)

// chaptersBase is the unified resource path of the training course
// chapters. The collection lives under the owning course
// (/courses/{courseId}/chapters); the item routes live at /chapters/{id}.
const chaptersBase = prototypePrefix + "/chapters"

// chaptersHandler adapts the chapters service to the HTTP routing layer.
// It serves the per-course collection (GET list / POST create) and the
// item routes (GET / PUT / DELETE by id); other methods yield a JSON 405
// with Allow. The course store is injected for course existence checks
// (404) on the collection routes.
type chaptersHandler struct {
	service *chapters.Service
}

func newChaptersHandler(store chapters.Store, courseStore courses.Store) *chaptersHandler {
	return &chaptersHandler{service: chapters.NewService(store, courseStore)}
}

func (h *chaptersHandler) handleCourseChapters(w http.ResponseWriter, r *http.Request) {
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

func (h *chaptersHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// chapterBody mirrors the client-supplied fields of the request body.
// course_id is never part of the body: it is decided by the route path.
// sort_order and blocks are optional (defaults 0 and []); quiz_config is
// an optional JSONB extension passed through verbatim.
type chapterBody struct {
	SortOrder  int              `json:"sort_order"`
	Title      string           `json:"title"`
	Blocks     []map[string]any `json:"blocks"`
	QuizConfig any              `json:"quiz_config"`
}

// decodeChapterBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeChapterBody(w http.ResponseWriter, r *http.Request) (chapterBody, bool) {
	var body chapterBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return chapterBody{}, false
	}
	return body, true
}

func (h *chaptersHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeChapterBody(w, r)
	if !ok {
		return
	}
	chapter, err := h.service.Create(r.Context(), r.PathValue("courseId"), chapters.Input{
		SortOrder:  body.SortOrder,
		Title:      body.Title,
		Blocks:     body.Blocks,
		QuizConfig: body.QuizConfig,
	})
	if err != nil {
		writeChapterError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, chapter)
}

// chapterListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type chapterListResponse struct {
	Records []chapters.Chapter `json:"records"`
	Meta    metaResponse       `json:"meta"`
}

func (h *chaptersHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseChapterListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.List(r.Context(), r.PathValue("courseId"), filter)
	if err != nil {
		writeChapterError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, chapterListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseChapterListFilter reads the limit/offset query parameters. They
// must be non-negative integers (default limit 50), otherwise 400.
// Chapters have no other filter dimensions: the owning course is fixed
// by the route path.
func parseChapterListFilter(w http.ResponseWriter, r *http.Request) (chapters.Filter, bool) {
	query := r.URL.Query()
	filter := chapters.Filter{Limit: defaultPageSize}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return chapters.Filter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return chapters.Filter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *chaptersHandler) get(w http.ResponseWriter, r *http.Request) {
	chapter, err := h.service.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeChapterError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, chapter)
}

func (h *chaptersHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeChapterBody(w, r)
	if !ok {
		return
	}
	chapter, err := h.service.Update(r.Context(), r.PathValue("id"), chapters.Input{
		SortOrder:  body.SortOrder,
		Title:      body.Title,
		Blocks:     body.Blocks,
		QuizConfig: body.QuizConfig,
	})
	if err != nil {
		writeChapterError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, chapter)
}

func (h *chaptersHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.Delete(r.Context(), r.PathValue("id")); err != nil {
		writeChapterError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeChapterError maps store/service errors to JSON error responses:
// validation errors become 400, unknown chapters and unknown courses 404,
// everything else 500.
func writeChapterError(w http.ResponseWriter, err error) {
	var validationError *chapters.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, chapters.ErrNotFound), errors.Is(err, chapters.ErrCourseNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
