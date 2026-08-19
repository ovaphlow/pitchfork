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

// opinionPostsBase is the unified resource path of the opinion posts
// (舆情信息, the simulated public-opinion feed of the 「舆情监测与预警」
// training phase). The collection and the item routes live under the
// owning run (/drills/{rid}/posts and /drills/{rid}/posts/{pid}); the
// literal posts segment is more specific than the /drills/{id} item
// route and never collides with it (same pattern as the sim-event
// routes). Posts are created with POST and listed with GET at the
// collection; the item route serves GET / PUT / DELETE by post id.
const opinionPostsBase = prototypePrefix + "/drills/{rid}/posts"

// opinionPostHandler adapts the opinion service to the HTTP routing
// layer. It serves the per-run opinion post collection (GET list / POST
// create) and the item routes (GET / PUT / DELETE by post id); other
// methods yield a JSON 405 with Allow. The owning run comes from the
// route path: writes require the run to be 进行中 (400 otherwise) and a
// missing run is a 404 on every route.
type opinionPostHandler struct {
	service *opinion.Service
}

// newOpinionPostHandler builds the handler over the opinion store and
// the drill store; the drill store backs the run source of the service
// (the run existence check and the write gate). The handler shares the
// injected opinion store with the opinion-event handler, so the runs
// service's run-opinion cleaner (wired in NewMux) cascades to both
// object kinds.
func newOpinionPostHandler(drillStore drills.Store, opinionStore opinion.Store) *opinionPostHandler {
	return &opinionPostHandler{service: opinion.NewService(opinionStore, opinion.NewRunSource(drillStore))}
}

func (h *opinionPostHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *opinionPostHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// opinionPostBody mirrors the client-supplied fields of the request
// body. run_id, id and the timestamps are never part of the body:
// run_id comes from the route path, id is server-generated and the
// timestamps are server-managed (a body that carries them has them
// ignored). content is required on create; source defaults to 微博,
// sentiment to 负面, warn_status to 未预警 (a new post only accepts
// 未预警); heat is kept as raw JSON so the handler can tell an omitted
// field from an explicit value and reject every non-integer; metadata
// is kept as raw JSON so the handler can tell an omitted field from an
// explicit null and reject every non-object value; created_by passes
// through (empty when omitted) because the prototype has no auth
// context.
type opinionPostBody struct {
	Content    string             `json:"content"`
	Source     opinion.Source     `json:"source"`
	Sentiment  opinion.Sentiment  `json:"sentiment"`
	Heat       json.RawMessage    `json:"heat"`
	WarnStatus opinion.WarnStatus `json:"warn_status"`
	Metadata   json.RawMessage    `json:"metadata"`
	CreatedBy  string             `json:"created_by"`
}

// decodeOpinionPostBody reads a single JSON object from the request
// body. An empty, malformed or non-object body (including a JSON null)
// yields a 400 { "error": ... } response, matching the decode convention
// of the other resources; an empty object {} is legal (an all-default
// create or a no-op update).
func decodeOpinionPostBody(w http.ResponseWriter, r *http.Request) (opinionPostBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionPostBody{}, false
	}
	var body opinionPostBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionPostBody{}, false
	}
	return body, true
}

// parseOpinionPostHeat converts the raw heat field. An omitted field
// yields (0, false, true): the create path applies the default 0 and
// the update path keeps the current value. A provided heat must be a
// JSON number literal with an integer value (1.5, 1e2, "10", true and
// null are rejected with 400; the leading-character guard rejects the
// quoted-string form that json.Number would otherwise accept); the
// 0–100 range is validated by the service on both entries.
func parseOpinionPostHeat(w http.ResponseWriter, raw json.RawMessage) (int, bool, bool) {
	if raw == nil {
		return 0, false, true
	}
	trimmed := strings.TrimSpace(string(raw))
	if trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid heat")
		return 0, false, false
	}
	if first := trimmed[0]; first != '-' && (first < '0' || first > '9') {
		writeError(w, http.StatusBadRequest, "invalid heat")
		return 0, false, false
	}
	var number json.Number
	if err := json.Unmarshal(raw, &number); err != nil {
		writeError(w, http.StatusBadRequest, "invalid heat")
		return 0, false, false
	}
	value, err := strconv.ParseInt(string(number), 10, 64)
	if err != nil {
		writeError(w, http.StatusBadRequest, "invalid heat")
		return 0, false, false
	}
	return int(value), true, true
}

// parseOpinionPostMetadata converts the raw metadata field. An omitted
// field yields (nil, false, true): the create path applies the default
// {} and the update path keeps the current value. A provided metadata
// must be a JSON object (arrays, scalars and explicit null are rejected
// with 400); an empty object {} is legal.
func parseOpinionPostMetadata(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool, bool) {
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

func (h *opinionPostHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionPostBody(w, r)
	if !ok {
		return
	}
	heat, _, ok := parseOpinionPostHeat(w, body.Heat)
	if !ok {
		return
	}
	metadata, _, ok := parseOpinionPostMetadata(w, body.Metadata)
	if !ok {
		return
	}
	post, err := h.service.CreatePost(r.Context(), r.PathValue("rid"), opinion.PostInput{
		Content:    body.Content,
		Source:     body.Source,
		Sentiment:  body.Sentiment,
		Heat:       heat,
		WarnStatus: body.WarnStatus,
		Metadata:   metadata,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeOpinionPostError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, post)
}

// opinionPostListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type opinionPostListResponse struct {
	Records []opinion.Post `json:"records"`
	Meta    metaResponse   `json:"meta"`
}

func (h *opinionPostHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseOpinionPostListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListPosts(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeOpinionPostError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, opinionPostListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseOpinionPostListFilter reads the source/sentiment/warn_status/
// limit/offset query parameters. A non-empty enum filter must be one of
// the allowed opinion post values, otherwise 400; limit/offset must be
// non-negative integers (default limit 50), otherwise 400.
func parseOpinionPostListFilter(w http.ResponseWriter, r *http.Request) (opinion.PostFilter, bool) {
	query := r.URL.Query()
	filter := opinion.PostFilter{Limit: defaultPageSize}

	if raw := query.Get("source"); raw != "" {
		source := opinion.Source(raw)
		if !source.Valid() {
			writeError(w, http.StatusBadRequest, "invalid source")
			return opinion.PostFilter{}, false
		}
		filter.Source = source
	}
	if raw := query.Get("sentiment"); raw != "" {
		sentiment := opinion.Sentiment(raw)
		if !sentiment.Valid() {
			writeError(w, http.StatusBadRequest, "invalid sentiment")
			return opinion.PostFilter{}, false
		}
		filter.Sentiment = sentiment
	}
	if raw := query.Get("warn_status"); raw != "" {
		status := opinion.WarnStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid warn_status")
			return opinion.PostFilter{}, false
		}
		filter.WarnStatus = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return opinion.PostFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return opinion.PostFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *opinionPostHandler) get(w http.ResponseWriter, r *http.Request) {
	post, err := h.service.GetPost(r.Context(), r.PathValue("rid"), r.PathValue("pid"))
	if err != nil {
		writeOpinionPostError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, post)
}

func (h *opinionPostHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionPostBody(w, r)
	if !ok {
		return
	}
	heat, hasHeat, ok := parseOpinionPostHeat(w, body.Heat)
	if !ok {
		return
	}
	metadata, hasMetadata, ok := parseOpinionPostMetadata(w, body.Metadata)
	if !ok {
		return
	}
	post, err := h.service.UpdatePost(r.Context(), r.PathValue("rid"), r.PathValue("pid"), opinion.PostUpdate{
		Content:     body.Content,
		Source:      body.Source,
		Sentiment:   body.Sentiment,
		Heat:        heat,
		HasHeat:     hasHeat,
		WarnStatus:  body.WarnStatus,
		Metadata:    metadata,
		HasMetadata: hasMetadata,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeOpinionPostError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, post)
}

func (h *opinionPostHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeletePost(r.Context(), r.PathValue("rid"), r.PathValue("pid")); err != nil {
		writeOpinionPostError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOpinionPostError maps the opinion service errors to JSON error
// responses: validation errors become 400, unknown runs or posts 404,
// everything else 500.
func writeOpinionPostError(w http.ResponseWriter, err error) {
	var validationError *opinion.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, opinion.ErrRunNotFound),
		errors.Is(err, opinion.ErrPostNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
