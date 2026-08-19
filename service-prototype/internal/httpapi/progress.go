package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/progress"
)

// progressHandler adapts the progress service to the HTTP routing layer.
// It serves the per-assignment summary (GET progress), the per-chapter
// upsert (PUT progress/chapters/{cid}) and the assignment complete
// action (POST complete); other methods yield a JSON 405 with Allow. The
// assignment, chapter and course stores are injected for the existence
// and ownership checks (404) behind the routes.
type progressHandler struct {
	service *progress.Service
}

func newProgressHandler(store progress.Store, assignmentStore assignments.Store, chapterStore chapters.Store, courseStore courses.Store) *progressHandler {
	assignmentService := assignments.NewService(assignmentStore, courseStore)
	return &progressHandler{
		service: progress.NewService(store, assignmentService, chapterStore, courseStore),
	}
}

func (h *progressHandler) handleSummary(w http.ResponseWriter, r *http.Request) {
	summary, err := h.service.Summary(r.Context(), r.PathValue("aid"), r.PathValue("eid"))
	if err != nil {
		writeProgressError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, summary)
}

func (h *progressHandler) handleComplete(w http.ResponseWriter, r *http.Request) {
	summary, err := h.service.Complete(r.Context(), r.PathValue("aid"), r.PathValue("eid"))
	if err != nil {
		writeProgressError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, summary)
}

// progressBody mirrors the client-supplied fields of a progress report.
// progress_percent is required and must be a JSON integer: the pointer
// lets an omitted field (400) be told apart from a value, and decoding a
// string, boolean, float or null into *int fails with 400. detail is
// captured raw so an omitted field (default {}) can be told apart from
// an explicit JSON null (rejected).
type progressBody struct {
	ProgressPercent *int            `json:"progress_percent"`
	Detail          json.RawMessage `json:"detail"`
}

// decodeProgressBody reads a single JSON object from the request body; a
// malformed, empty or non-object body, or a progress_percent that is not
// a JSON integer, yields a 400 { "error": ... } response.
func decodeProgressBody(w http.ResponseWriter, r *http.Request) (progressBody, bool) {
	var body progressBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return progressBody{}, false
	}
	return body, true
}

// parseDetail converts the raw detail field into a JSON object. An
// omitted field defaults to an empty object; a provided value must be a
// JSON object — an array, string, number or null is rejected with 400.
func parseDetail(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool) {
	if len(raw) == 0 {
		return map[string]any{}, true
	}
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid detail")
		return nil, false
	}
	object, ok := value.(map[string]any)
	if !ok {
		writeError(w, http.StatusBadRequest, "detail must be a JSON object")
		return nil, false
	}
	return object, true
}

func (h *progressHandler) handleUpsert(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeProgressBody(w, r)
	if !ok {
		return
	}
	if body.ProgressPercent == nil {
		writeError(w, http.StatusBadRequest, "progress_percent required")
		return
	}
	detail, ok := parseDetail(w, body.Detail)
	if !ok {
		return
	}
	row, err := h.service.Upsert(r.Context(), r.PathValue("aid"), r.PathValue("eid"), r.PathValue("cid"), progress.Input{
		ProgressPercent: *body.ProgressPercent,
		Detail:          detail,
	})
	if err != nil {
		writeProgressError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, row)
}

// writeProgressError maps store/service errors to JSON error responses:
// validation errors become 400, unknown assignments, chapters and
// courses 404, everything else 500.
func writeProgressError(w http.ResponseWriter, err error) {
	var validationError *progress.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, progress.ErrAssignmentNotFound),
		errors.Is(err, progress.ErrChapterNotFound),
		errors.Is(err, progress.ErrCourseNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
