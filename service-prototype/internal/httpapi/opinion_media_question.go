package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
)

// opinionMediaQuestionsBase is the unified resource path of the opinion
// media questions (媒体问答记录, the simulated press-conference Q&A
// records of the 「媒体沟通」 training phase). The collection and the
// item routes live under the owning run
// (/drills/{rid}/media-questions and /drills/{rid}/media-questions/
// {mqid}); the literal media-questions segment is more specific than
// the /drills/{id} item route and never collides with it (same pattern
// as the sim-event, posts and releases routes). Questions are created
// with POST and listed with GET at the collection; the item route
// serves GET / PUT / DELETE by question id.
const opinionMediaQuestionsBase = prototypePrefix + "/drills/{rid}/media-questions"

// opinionMediaQuestionHandler adapts the opinion service to the HTTP
// routing layer. It serves the per-run opinion media question
// collection (GET list / POST create) and the item routes (GET / PUT /
// DELETE by question id); other methods yield a JSON 405 with Allow.
// The owning run comes from the route path: writes require the run to
// be 进行中 (400 otherwise) and a missing run is a 404 on every route.
type opinionMediaQuestionHandler struct {
	service *opinion.Service
}

// newOpinionMediaQuestionHandler builds the handler over the opinion
// store and the drill store; the drill store backs the run source of
// the service (the run existence check and the write gate). The handler
// shares the injected opinion store with the other opinion handlers, so
// the runs service's run-opinion cleaner (wired in NewMux) cascades to
// every opinion object kind.
func newOpinionMediaQuestionHandler(drillStore drills.Store, opinionStore opinion.Store) *opinionMediaQuestionHandler {
	return &opinionMediaQuestionHandler{service: opinion.NewService(opinionStore, opinion.NewRunSource(drillStore))}
}

func (h *opinionMediaQuestionHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *opinionMediaQuestionHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// opinionMediaQuestionBody mirrors the client-supplied fields of the
// request body. run_id, id and the timestamps are never part of the
// body: run_id comes from the route path, id is server-generated and
// the timestamps are server-managed (a body that carries them has them
// ignored). media_name and question are required on create; reporter
// defaults to ''; question_type defaults to 事实类 and must be one of
// the allowed values; answer defaults to '' (editable at any time on
// update); status defaults to 未回答 and a new question only accepts
// 未回答; metadata is kept as raw JSON so the handler can tell an
// omitted field from an explicit null and reject every non-object
// value; created_by passes through (empty when omitted) because the
// prototype has no auth context.
type opinionMediaQuestionBody struct {
	MediaName    string               `json:"media_name"`
	Reporter     string               `json:"reporter"`
	Question     string               `json:"question"`
	QuestionType opinion.QuestionType `json:"question_type"`
	Answer       string               `json:"answer"`
	Status       opinion.AnswerStatus `json:"status"`
	Metadata     json.RawMessage      `json:"metadata"`
	CreatedBy    string               `json:"created_by"`
}

// decodeOpinionMediaQuestionBody reads a single JSON object from the
// request body. An empty, malformed or non-object body (including a
// JSON null) yields a 400 { "error": ... } response, matching the decode
// convention of the other resources; an empty object {} is legal (an
// all-default create or a no-op update).
func decodeOpinionMediaQuestionBody(w http.ResponseWriter, r *http.Request) (opinionMediaQuestionBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionMediaQuestionBody{}, false
	}
	var body opinionMediaQuestionBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionMediaQuestionBody{}, false
	}
	return body, true
}

// parseOpinionMediaQuestionMetadata converts the raw metadata field. An
// omitted field yields (nil, false, true): the create path applies the
// default {} and the update path keeps the current value. A provided
// metadata must be a JSON object (arrays, scalars and explicit null are
// rejected with 400); an empty object {} is legal.
func parseOpinionMediaQuestionMetadata(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool, bool) {
	if raw == nil {
		return nil, false, true
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "metadata must be a JSON object")
		return nil, false, false
	}
	var metadata map[string]any
	if err := json.Unmarshal(raw, &metadata); err != nil {
		writeError(w, http.StatusBadRequest, "metadata must be a JSON object")
		return nil, false, false
	}
	return metadata, true, true
}

func (h *opinionMediaQuestionHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionMediaQuestionBody(w, r)
	if !ok {
		return
	}
	metadata, _, ok := parseOpinionMediaQuestionMetadata(w, body.Metadata)
	if !ok {
		return
	}
	question, err := h.service.CreateMediaQuestion(r.Context(), r.PathValue("rid"), opinion.MediaQuestionInput{
		MediaName:    body.MediaName,
		Reporter:     body.Reporter,
		Question:     body.Question,
		QuestionType: body.QuestionType,
		Answer:       body.Answer,
		Status:       body.Status,
		Metadata:     metadata,
		CreatedBy:    body.CreatedBy,
	})
	if err != nil {
		writeOpinionMediaQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, question)
}

// opinionMediaQuestionListResponse follows the repository list
// convention: { "records": [...], "meta": { "total": N } }.
type opinionMediaQuestionListResponse struct {
	Records []opinion.MediaQuestion `json:"records"`
	Meta    metaResponse            `json:"meta"`
}

func (h *opinionMediaQuestionHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseOpinionMediaQuestionListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListMediaQuestions(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeOpinionMediaQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, opinionMediaQuestionListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseOpinionMediaQuestionListFilter reads the question_type/status/
// limit/offset query parameters. A non-empty enum filter must be one of
// the allowed opinion media question values, otherwise 400; limit/
// offset must be non-negative integers (default limit 50), otherwise
// 400.
func parseOpinionMediaQuestionListFilter(w http.ResponseWriter, r *http.Request) (opinion.MediaQuestionFilter, bool) {
	query := r.URL.Query()
	filter := opinion.MediaQuestionFilter{Limit: defaultPageSize}

	if raw := query.Get("question_type"); raw != "" {
		questionType := opinion.QuestionType(raw)
		if !questionType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid question_type")
			return opinion.MediaQuestionFilter{}, false
		}
		filter.QuestionType = questionType
	}
	if raw := query.Get("status"); raw != "" {
		status := opinion.AnswerStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return opinion.MediaQuestionFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return opinion.MediaQuestionFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return opinion.MediaQuestionFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *opinionMediaQuestionHandler) get(w http.ResponseWriter, r *http.Request) {
	question, err := h.service.GetMediaQuestion(r.Context(), r.PathValue("rid"), r.PathValue("mqid"))
	if err != nil {
		writeOpinionMediaQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, question)
}

func (h *opinionMediaQuestionHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionMediaQuestionBody(w, r)
	if !ok {
		return
	}
	metadata, hasMetadata, ok := parseOpinionMediaQuestionMetadata(w, body.Metadata)
	if !ok {
		return
	}
	question, err := h.service.UpdateMediaQuestion(r.Context(), r.PathValue("rid"), r.PathValue("mqid"), opinion.MediaQuestionUpdate{
		MediaName:    body.MediaName,
		Reporter:     body.Reporter,
		Question:     body.Question,
		QuestionType: body.QuestionType,
		Answer:       body.Answer,
		Status:       body.Status,
		Metadata:     metadata,
		HasMetadata:  hasMetadata,
		CreatedBy:    body.CreatedBy,
	})
	if err != nil {
		writeOpinionMediaQuestionError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, question)
}

func (h *opinionMediaQuestionHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteMediaQuestion(r.Context(), r.PathValue("rid"), r.PathValue("mqid")); err != nil {
		writeOpinionMediaQuestionError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOpinionMediaQuestionError maps the opinion service errors to
// JSON error responses: validation errors become 400, unknown runs or
// questions 404, everything else 500.
func writeOpinionMediaQuestionError(w http.ResponseWriter, err error) {
	var validationError *opinion.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, opinion.ErrRunNotFound),
		errors.Is(err, opinion.ErrMediaQuestionNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
