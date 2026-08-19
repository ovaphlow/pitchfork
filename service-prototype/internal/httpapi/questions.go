package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
)

// questionsBase is the unified resource path of the question bank.
const questionsBase = prototypePrefix + "/questions"

// questionsHandler adapts the questions service to the HTTP routing
// layer. It serves the collection (GET list / POST create), the item
// routes (GET / PUT / DELETE by id) and the batch import endpoint
// (POST /questions/import); other methods yield a JSON 405 with Allow.
type questionsHandler struct {
	service *questions.Service
}

func newQuestionsHandler(store questions.Store) *questionsHandler {
	return &questionsHandler{service: questions.NewService(store)}
}

func (h *questionsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *questionsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// handleImport serves POST /questions/import only. The route is
// registered as a method-limited pattern, so any other method on the
// literal /questions/import path falls through to the {id} item route
// (id = "import") and answers 404.
func (h *questionsHandler) handleImport(w http.ResponseWriter, r *http.Request) {
	var bodies []questionBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&bodies); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return
	}
	inputs := make([]questions.Input, len(bodies))
	for i, body := range bodies {
		inputs[i] = body.input()
	}
	imported, err := h.service.Import(r.Context(), inputs)
	if err != nil {
		writeQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, importResponse{Imported: len(imported), Records: imported})
}

// questionBody mirrors the client-supplied fields of the request body.
// id and the timestamps are never accepted from the client: they are
// server-generated. created_by is optional (empty when omitted) because
// the prototype has no auth context; tags/explanation/metadata are
// optional with defaults []/""/{}.
type questionBody struct {
	Type        string         `json:"type"`
	Difficulty  int            `json:"difficulty"`
	Tags        []string       `json:"tags"`
	Content     string         `json:"content"`
	Options     []string       `json:"options"`
	Answer      any            `json:"answer"`
	Explanation string         `json:"explanation"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
}

func (body questionBody) input() questions.Input {
	return questions.Input{
		Type:        questions.QuestionType(body.Type),
		Difficulty:  body.Difficulty,
		Tags:        body.Tags,
		Content:     body.Content,
		Options:     body.Options,
		Answer:      body.Answer,
		Explanation: body.Explanation,
		Metadata:    body.Metadata,
		CreatedBy:   body.CreatedBy,
	}
}

// decodeQuestionBody reads a single JSON object from the request body; a
// malformed or empty body yields a 400 { "error": ... } response.
func decodeQuestionBody(w http.ResponseWriter, r *http.Request) (questionBody, bool) {
	var body questionBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return questionBody{}, false
	}
	return body, true
}

func (h *questionsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeQuestionBody(w, r)
	if !ok {
		return
	}
	question, err := h.service.Create(r.Context(), body.input())
	if err != nil {
		writeQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, question)
}

// questionListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type questionListResponse struct {
	Records []questions.Question `json:"records"`
	Meta    metaResponse         `json:"meta"`
}

func (h *questionsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseQuestionListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.List(r.Context(), filter)
	if err != nil {
		writeQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, questionListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseQuestionListFilter reads the type/difficulty/tags/limit/offset
// query parameters. A non-empty type must be one of the four question
// types, difficulty must be within 1-5 and limit/offset non-negative
// integers, otherwise 400. tags is comma-separated and matches with AND
// semantics (a question must carry every given tag).
func parseQuestionListFilter(w http.ResponseWriter, r *http.Request) (questions.Filter, bool) {
	query := r.URL.Query()
	filter := questions.Filter{Limit: defaultPageSize}

	if raw := query.Get("type"); raw != "" {
		questionType := questions.QuestionType(raw)
		if !questionType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid type")
			return questions.Filter{}, false
		}
		filter.Type = questionType
	}
	if raw := query.Get("difficulty"); raw != "" {
		difficulty, err := strconv.Atoi(raw)
		if err != nil || difficulty < questions.DifficultyMin || difficulty > questions.DifficultyMax {
			writeError(w, http.StatusBadRequest, "invalid difficulty")
			return questions.Filter{}, false
		}
		filter.Difficulty = difficulty
	}
	if raw := query.Get("tags"); raw != "" {
		for _, tag := range strings.Split(raw, ",") {
			if trimmed := strings.TrimSpace(tag); trimmed != "" {
				filter.Tags = append(filter.Tags, trimmed)
			}
		}
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return questions.Filter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return questions.Filter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *questionsHandler) get(w http.ResponseWriter, r *http.Request) {
	question, err := h.service.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, question)
}

func (h *questionsHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeQuestionBody(w, r)
	if !ok {
		return
	}
	question, err := h.service.Update(r.Context(), r.PathValue("id"), body.input())
	if err != nil {
		writeQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, question)
}

func (h *questionsHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.Delete(r.Context(), r.PathValue("id")); err != nil {
		writeQuestionError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// importResponse is the success body of POST /questions/import.
type importResponse struct {
	Imported int                  `json:"imported"`
	Records  []questions.Question `json:"records"`
}

// importErrorBody is the failure body of POST /questions/import: one
// detail per failing item with its array index and message.
type importErrorBody struct {
	Error   string                   `json:"error"`
	Details []questions.ImportDetail `json:"details"`
}

// writeQuestionError maps store/service errors to JSON error responses:
// validation errors become 400, unknown questions 404, a failed batch
// import 400 with per-item details, everything else 500.
func writeQuestionError(w http.ResponseWriter, err error) {
	var validationError *questions.ValidationError
	var importError *questions.ImportError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.As(err, &importError):
		details := make([]questions.ImportDetail, 0, len(importError.Details))
		details = append(details, importError.Details...)
		writeJSON(w, http.StatusBadRequest, importErrorBody{Error: importError.Error(), Details: details})
	case errors.Is(err, questions.ErrNotFound):
		writeError(w, http.StatusNotFound, questions.ErrNotFound.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
