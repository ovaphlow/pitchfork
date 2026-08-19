package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
)

// papersBase is the unified resource path of the exam papers.
const papersBase = prototypePrefix + "/papers"

// papersHandler adapts the papers service to the HTTP routing layer.
// It serves the collection (GET list / POST create), the item routes
// (GET / PUT / DELETE by id) and the automatic generation endpoint
// (POST /papers/{id}/generate); other methods yield a JSON 405 with
// Allow.
type papersHandler struct {
	service *papers.Service
}

// newPapersHandler builds the handler over the paper store and the
// question source that backs automatic generation (the question-bank
// store of the questions package).
func newPapersHandler(store papers.Store, source papers.QuestionSource) *papersHandler {
	return &papersHandler{service: papers.NewService(store, source)}
}

func (h *papersHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *papersHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// handleGenerate serves POST /papers/{id}/generate only. The route is
// registered as a method-limited pattern, so any other method on the
// literal path falls through to the {id} item route (id = "generate")
// and answers 404.
func (h *papersHandler) handleGenerate(w http.ResponseWriter, r *http.Request) {
	paper, err := h.service.Generate(r.Context(), r.PathValue("id"))
	if err != nil {
		writePaperError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, paper)
}

// paperBody mirrors the client-supplied fields of the request body.
// id, questions and the timestamps are never accepted from the client:
// id and the timestamps are server-generated and questions is written
// only by generation (a client-supplied questions field is ignored).
// duration_minutes and pass_score are pointers so a missing field can be
// told apart from an explicit zero (pass_score 0 is legal). created_by
// is optional (empty when omitted) because the prototype has no auth
// context.
type paperBody struct {
	Title              string         `json:"title"`
	DurationMinutes    *int           `json:"duration_minutes"`
	PassScore          *int           `json:"pass_score"`
	GenerationStrategy map[string]any `json:"generation_strategy"`
	CreatedBy          string         `json:"created_by"`
}

func (body paperBody) input() papers.Input {
	return papers.Input{
		Title:              body.Title,
		DurationMinutes:    body.DurationMinutes,
		PassScore:          body.PassScore,
		GenerationStrategy: body.GenerationStrategy,
		CreatedBy:          body.CreatedBy,
	}
}

// decodePaperBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodePaperBody(w http.ResponseWriter, r *http.Request) (paperBody, bool) {
	var body paperBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return paperBody{}, false
	}
	return body, true
}

func (h *papersHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodePaperBody(w, r)
	if !ok {
		return
	}
	paper, err := h.service.Create(r.Context(), body.input())
	if err != nil {
		writePaperError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, paper)
}

// paperListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type paperListResponse struct {
	Records []papers.Paper `json:"records"`
	Meta    metaResponse   `json:"meta"`
}

func (h *papersHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parsePaperListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.List(r.Context(), filter)
	if err != nil {
		writePaperError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, paperListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parsePaperListFilter reads the limit/offset query parameters; both
// must be non-negative integers, otherwise 400. The default page size
// follows the repository convention (limit ?: 50).
func parsePaperListFilter(w http.ResponseWriter, r *http.Request) (papers.Filter, bool) {
	query := r.URL.Query()
	filter := papers.Filter{Limit: defaultPageSize}

	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return papers.Filter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return papers.Filter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *papersHandler) get(w http.ResponseWriter, r *http.Request) {
	paper, err := h.service.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writePaperError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, paper)
}

func (h *papersHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodePaperBody(w, r)
	if !ok {
		return
	}
	paper, err := h.service.Update(r.Context(), r.PathValue("id"), body.input())
	if err != nil {
		writePaperError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, paper)
}

func (h *papersHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.Delete(r.Context(), r.PathValue("id")); err != nil {
		writePaperError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writePaperError maps store/service errors to JSON error responses:
// validation and generation errors become 400, unknown papers 404,
// everything else 500.
func writePaperError(w http.ResponseWriter, err error) {
	var validationError *papers.ValidationError
	var generationError *papers.GenerationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.As(err, &generationError):
		writeError(w, http.StatusBadRequest, generationError.Message)
	case errors.Is(err, papers.ErrNotFound):
		writeError(w, http.StatusNotFound, papers.ErrNotFound.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
