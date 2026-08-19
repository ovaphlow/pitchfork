package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/examrecords"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
)

// examRecordsBase is the unified resource path of the online exam
// records.
const examRecordsBase = prototypePrefix + "/exam-records"

// examRecordsHandler adapts the exam-records service to the HTTP routing
// layer. It serves the collection (GET list / POST open an exam), the
// item route (GET by id) and the submission endpoint (POST
// /exam-records/{id}/submit); other methods yield a JSON 405 with
// Allow. The paper store is injected for the paper existence check
// (404) on exam start.
type examRecordsHandler struct {
	service *examrecords.Service
}

// newExamRecordsHandler builds the handler over the exam-record store
// and the paper store that backs the exam-start existence check and
// snapshot.
func newExamRecordsHandler(store examrecords.Store, paperStore papers.Store) *examRecordsHandler {
	return &examRecordsHandler{service: examrecords.NewService(store, paperStore)}
}

func (h *examRecordsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *examRecordsHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r)
	default:
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// handleSubmit serves POST /exam-records/{id}/submit only. The route is
// registered as a method-limited pattern, so any other method on the
// literal path falls through to the {id} item route (id = "submit") and
// answers 404.
func (h *examRecordsHandler) handleSubmit(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeSubmitBody(w, r)
	if !ok {
		return
	}
	answers, ok := parseAnswers(w, body.Answers)
	if !ok {
		return
	}
	record, err := h.service.Submit(r.Context(), r.PathValue("id"), answers)
	if err != nil {
		writeExamRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, record)
}

// examRecordBody mirrors the client-supplied fields of the exam-start
// request body. metadata is captured raw so an omitted field (default
// {}) can be told apart from an explicit JSON null (rejected);
// created_by is optional (empty when omitted) because the prototype has
// no auth context. id, the timestamps and answers_snapshot are never
// accepted from the client (a client-supplied field is ignored).
type examRecordBody struct {
	EmployeeID string          `json:"employee_id"`
	PaperID    string          `json:"paper_id"`
	Metadata   json.RawMessage `json:"metadata"`
	CreatedBy  string          `json:"created_by"`
}

// decodeExamRecordBody reads a single JSON object from the request
// body; a malformed, empty or non-object body yields a 400
// { "error": ... } response.
func decodeExamRecordBody(w http.ResponseWriter, r *http.Request) (examRecordBody, bool) {
	var body examRecordBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return examRecordBody{}, false
	}
	return body, true
}

// parseMetadata converts the raw metadata field into a JSON object. An
// omitted field defaults to an empty object; a provided value must be a
// JSON object — an array, string, number or null is rejected with 400.
func parseMetadata(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool) {
	if len(raw) == 0 {
		return map[string]any{}, true
	}
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid metadata")
		return nil, false
	}
	object, ok := value.(map[string]any)
	if !ok {
		writeError(w, http.StatusBadRequest, "metadata must be a JSON object")
		return nil, false
	}
	return object, true
}

func (h *examRecordsHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeExamRecordBody(w, r)
	if !ok {
		return
	}
	metadata, ok := parseMetadata(w, body.Metadata)
	if !ok {
		return
	}
	record, err := h.service.Create(r.Context(), examrecords.Input{
		EmployeeID: body.EmployeeID,
		PaperID:    body.PaperID,
		Metadata:   metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeExamRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, record)
}

// submitBody mirrors the client-supplied fields of the submission
// request body. answers is captured raw so a missing field (400) can be
// told apart from an empty object (a legal all-unanswered submission).
type submitBody struct {
	Answers json.RawMessage `json:"answers"`
}

// decodeSubmitBody reads a single JSON object from the request body; a
// malformed, empty or non-object body yields a 400
// { "error": ... } response.
func decodeSubmitBody(w http.ResponseWriter, r *http.Request) (submitBody, bool) {
	var body submitBody
	decoder := json.NewDecoder(io.LimitReader(r.Body, 1<<20))
	if err := decoder.Decode(&body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return submitBody{}, false
	}
	return body, true
}

// parseAnswers converts the raw answers field into a map of question id
// to answer value. A missing field (400) is told apart from an empty
// object; a provided value must be a JSON object — an array, string,
// number or null is rejected with 400. The per-question answer shapes
// are validated by the service against the snapshot.
func parseAnswers(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool) {
	if len(raw) == 0 {
		writeError(w, http.StatusBadRequest, "answers required")
		return nil, false
	}
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		writeError(w, http.StatusBadRequest, "invalid answers")
		return nil, false
	}
	answers, ok := value.(map[string]any)
	if !ok {
		writeError(w, http.StatusBadRequest, "answers must be a JSON object")
		return nil, false
	}
	return answers, true
}

func (h *examRecordsHandler) get(w http.ResponseWriter, r *http.Request) {
	record, err := h.service.Get(r.Context(), r.PathValue("id"))
	if err != nil {
		writeExamRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, record)
}

// examRecordListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type examRecordListResponse struct {
	Records []examrecords.Record `json:"records"`
	Meta    metaResponse         `json:"meta"`
}

func (h *examRecordsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseExamRecordListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.List(r.Context(), filter)
	if err != nil {
		writeExamRecordError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, examRecordListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseExamRecordListFilter reads the employee_id/paper_id/limit/offset
// query parameters. An empty employee_id or paper_id means unset (no
// filter); a non-empty value must be a 26-character Crockford Base32
// ULID, otherwise 400. limit/offset must be non-negative integers,
// otherwise 400. The default page size follows the repository
// convention (limit ?: 50).
func parseExamRecordListFilter(w http.ResponseWriter, r *http.Request) (examrecords.Filter, bool) {
	query := r.URL.Query()
	filter := examrecords.Filter{Limit: defaultPageSize}
	if raw := query.Get("employee_id"); raw != "" {
		if !examrecords.ValidULID(raw) {
			writeError(w, http.StatusBadRequest, "invalid employee_id")
			return examrecords.Filter{}, false
		}
		filter.EmployeeID = raw
	}
	if raw := query.Get("paper_id"); raw != "" {
		if !examrecords.ValidULID(raw) {
			writeError(w, http.StatusBadRequest, "invalid paper_id")
			return examrecords.Filter{}, false
		}
		filter.PaperID = raw
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return examrecords.Filter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return examrecords.Filter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

// writeExamRecordError maps store/service errors to JSON error
// responses: validation errors and double submission become 400, unknown
// exam records and unknown papers 404, everything else 500.
func writeExamRecordError(w http.ResponseWriter, err error) {
	var validationError *examrecords.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, examrecords.ErrNotFound), errors.Is(err, examrecords.ErrPaperNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, examrecords.ErrAlreadySubmitted):
		writeError(w, http.StatusBadRequest, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
