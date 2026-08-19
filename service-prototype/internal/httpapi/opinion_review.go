package httpapi

import (
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
)

// opinionReviewBase is the unified resource path of the opinion review
// (舆情复盘记录). The item route lives under the owning run
// (/drills/{rid}/review); the literal review segment is more specific
// than the /drills/{id} item route and never collides with it (same
// pattern as the step-record, sim-event, assessment, command-session
// and opinion-event routes). The resource is a single-object PUT-upsert
// family (no POST and no collection/list endpoint): the first PUT of a
// run creates the review, later PUTs update it in place.
const opinionReviewBase = prototypePrefix + "/drills/{rid}/review"

// opinionReviewHandler adapts the opinion service to the HTTP routing
// layer. It serves the item route (GET / PUT upsert / DELETE by run);
// other methods yield a JSON 405 with Allow. The owning run comes from
// the route path: a missing run is a 404 on every route.
type opinionReviewHandler struct {
	service *opinion.Service
}

// newOpinionReviewHandler builds the handler over the opinion store and
// the drill store; the drill store backs the run source of the service
// (the run existence check and the write gate).
func newOpinionReviewHandler(drillStore drills.Store, opinionStore opinion.Store) *opinionReviewHandler {
	return &opinionReviewHandler{service: opinion.NewService(opinionStore, opinion.NewRunSource(drillStore))}
}

func (h *opinionReviewHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r)
	case http.MethodPut:
		h.upsert(w, r)
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "GET, PUT, DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// opinionReviewBody mirrors the client-supplied fields of the request
// body. run_id and id are never part of the body: they are decided by
// the route path and the service (a body that carries them has them
// ignored). The five text sections (case_summary / highlights /
// problems / lessons / suggestions) pass through — empty and explicit
// null are both legal (empty stays empty); any non-string value (a
// number, a boolean, an object or an array) fails the JSON decode with
// 400; metadata must be a JSON object (any other shape fails the JSON
// decode with 400; omitted or null means {}); created_by defaults to an
// empty string. There is no required field: an empty object {} is a
// legal all-default create or update.
type opinionReviewBody struct {
	CaseSummary string         `json:"case_summary"`
	Highlights  string         `json:"highlights"`
	Problems    string         `json:"problems"`
	Lessons     string         `json:"lessons"`
	Suggestions string         `json:"suggestions"`
	Metadata    map[string]any `json:"metadata"`
	CreatedBy   string         `json:"created_by"`
}

// decodeOpinionReviewBody reads a single JSON object from the request
// body. An empty, malformed or non-object body (including a JSON null)
// yields a 400 { "error": ... } response, matching the decode
// convention of the other PUT resources; an empty object {} is legal
// (an all-default create or update).
func decodeOpinionReviewBody(w http.ResponseWriter, r *http.Request) (opinionReviewBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionReviewBody{}, false
	}
	var body opinionReviewBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionReviewBody{}, false
	}
	return body, true
}

func (h *opinionReviewHandler) upsert(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionReviewBody(w, r)
	if !ok {
		return
	}
	review, err := h.service.UpsertReview(r.Context(), r.PathValue("rid"), opinion.ReviewInput{
		CaseSummary: body.CaseSummary,
		Highlights:  body.Highlights,
		Problems:    body.Problems,
		Lessons:     body.Lessons,
		Suggestions: body.Suggestions,
		Metadata:    body.Metadata,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeOpinionReviewError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, review)
}

func (h *opinionReviewHandler) get(w http.ResponseWriter, r *http.Request) {
	review, err := h.service.GetReview(r.Context(), r.PathValue("rid"))
	if err != nil {
		writeOpinionReviewError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, review)
}

func (h *opinionReviewHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteReview(r.Context(), r.PathValue("rid")); err != nil {
		writeOpinionReviewError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOpinionReviewError maps the opinion service errors to JSON error
// responses: validation errors become 400, unknown runs or unwritten
// reviews 404, everything else 500.
func writeOpinionReviewError(w http.ResponseWriter, err error) {
	var validationError *opinion.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, opinion.ErrRunNotFound),
		errors.Is(err, opinion.ErrReviewNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
