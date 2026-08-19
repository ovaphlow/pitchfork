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

// opinionReleasesBase is the unified resource path of the opinion
// releases (信息发布记录, the situation-statement publication records of
// the 「信息发布」 training phase). The collection and the item routes
// live under the owning run (/drills/{rid}/releases and
// /drills/{rid}/releases/{lid}); the literal releases segment is more
// specific than the /drills/{id} item route and never collides with it
// (same pattern as the sim-event and posts routes). Releases are
// created with POST and listed with GET at the collection; the item
// route serves GET / PUT / DELETE by release id.
const opinionReleasesBase = prototypePrefix + "/drills/{rid}/releases"

// opinionReleaseHandler adapts the opinion service to the HTTP routing
// layer. It serves the per-run opinion release collection (GET list /
// POST create) and the item routes (GET / PUT / DELETE by release id);
// other methods yield a JSON 405 with Allow. The owning run comes from
// the route path: writes require the run to be 进行中 (400 otherwise)
// and a missing run is a 404 on every route.
type opinionReleaseHandler struct {
	service *opinion.Service
}

// newOpinionReleaseHandler builds the handler over the opinion store
// and the drill store; the drill store backs the run source of the
// service (the run existence check and the write gate). The handler
// shares the injected opinion store with the opinion-event and
// opinion-post handlers, so the runs service's run-opinion cleaner
// (wired in NewMux) cascades to all three object kinds.
func newOpinionReleaseHandler(drillStore drills.Store, opinionStore opinion.Store) *opinionReleaseHandler {
	return &opinionReleaseHandler{service: opinion.NewService(opinionStore, opinion.NewRunSource(drillStore))}
}

func (h *opinionReleaseHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *opinionReleaseHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// opinionReleaseBody mirrors the client-supplied fields of the request
// body. run_id, id and the timestamps are never part of the body:
// run_id comes from the route path, id is server-generated and the
// timestamps are server-managed (a body that carries them has them
// ignored). title and content are required on both create and update;
// channel defaults to 官网公告 (create) or keeps its current value
// (update) and must be one of the allowed values; media_name passes
// through ('' when omitted, not coupled to the channel value);
// status defaults to 草稿 on create (a new release only accepts 草稿)
// or keeps its current value on update; metadata is kept as raw JSON so
// the handler can tell an omitted field from an explicit null and
// reject every non-object value; created_by passes through (empty when
// omitted) because the prototype has no auth context. published_at is
// never part of the body: it is managed by the service.
type opinionReleaseBody struct {
	Channel   opinion.Channel       `json:"channel"`
	Title     string                `json:"title"`
	Content   string                `json:"content"`
	MediaName string                `json:"media_name"`
	Status    opinion.ReleaseStatus `json:"status"`
	Metadata  json.RawMessage       `json:"metadata"`
	CreatedBy string                `json:"created_by"`
}

// decodeOpinionReleaseBody reads a single JSON object from the request
// body. An empty, malformed or non-object body (including a JSON null)
// yields a 400 { "error": ... } response, matching the decode convention
// of the other resources; an empty object {} is legal (an all-default
// create is rejected later by the required title/content checks, a
// no-op update is rejected the same way).
func decodeOpinionReleaseBody(w http.ResponseWriter, r *http.Request) (opinionReleaseBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionReleaseBody{}, false
	}
	var body opinionReleaseBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionReleaseBody{}, false
	}
	return body, true
}

// parseOpinionReleaseMetadata converts the raw metadata field. An
// omitted field yields (nil, false, true): the create path applies the
// default {} and the update path keeps the current value. A provided
// metadata must be a JSON object (arrays, scalars and explicit null are
// rejected with 400); an empty object {} is legal.
func parseOpinionReleaseMetadata(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool, bool) {
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

func (h *opinionReleaseHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionReleaseBody(w, r)
	if !ok {
		return
	}
	metadata, _, ok := parseOpinionReleaseMetadata(w, body.Metadata)
	if !ok {
		return
	}
	release, err := h.service.CreateRelease(r.Context(), r.PathValue("rid"), opinion.ReleaseInput{
		Channel:   body.Channel,
		Title:     body.Title,
		Content:   body.Content,
		MediaName: body.MediaName,
		Status:    body.Status,
		Metadata:  metadata,
		CreatedBy: body.CreatedBy,
	})
	if err != nil {
		writeOpinionReleaseError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, release)
}

// opinionReleaseListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type opinionReleaseListResponse struct {
	Records []opinion.Release `json:"records"`
	Meta    metaResponse      `json:"meta"`
}

func (h *opinionReleaseHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseOpinionReleaseListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListReleases(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeOpinionReleaseError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, opinionReleaseListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseOpinionReleaseListFilter reads the channel/status/limit/offset
// query parameters. A non-empty enum filter must be one of the allowed
// opinion release values, otherwise 400; limit/offset must be
// non-negative integers (default limit 50), otherwise 400.
func parseOpinionReleaseListFilter(w http.ResponseWriter, r *http.Request) (opinion.ReleaseFilter, bool) {
	query := r.URL.Query()
	filter := opinion.ReleaseFilter{Limit: defaultPageSize}

	if raw := query.Get("channel"); raw != "" {
		channel := opinion.Channel(raw)
		if !channel.Valid() {
			writeError(w, http.StatusBadRequest, "invalid channel")
			return opinion.ReleaseFilter{}, false
		}
		filter.Channel = channel
	}
	if raw := query.Get("status"); raw != "" {
		status := opinion.ReleaseStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return opinion.ReleaseFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return opinion.ReleaseFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return opinion.ReleaseFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *opinionReleaseHandler) get(w http.ResponseWriter, r *http.Request) {
	release, err := h.service.GetRelease(r.Context(), r.PathValue("rid"), r.PathValue("lid"))
	if err != nil {
		writeOpinionReleaseError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, release)
}

func (h *opinionReleaseHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionReleaseBody(w, r)
	if !ok {
		return
	}
	metadata, hasMetadata, ok := parseOpinionReleaseMetadata(w, body.Metadata)
	if !ok {
		return
	}
	release, err := h.service.UpdateRelease(r.Context(), r.PathValue("rid"), r.PathValue("lid"), opinion.ReleaseUpdate{
		Channel:     body.Channel,
		Title:       body.Title,
		Content:     body.Content,
		MediaName:   body.MediaName,
		Status:      body.Status,
		Metadata:    metadata,
		HasMetadata: hasMetadata,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeOpinionReleaseError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, release)
}

func (h *opinionReleaseHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteRelease(r.Context(), r.PathValue("rid"), r.PathValue("lid")); err != nil {
		writeOpinionReleaseError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOpinionReleaseError maps the opinion service errors to JSON
// error responses: validation errors become 400, unknown runs or
// releases 404, everything else 500.
func writeOpinionReleaseError(w http.ResponseWriter, err error) {
	var validationError *opinion.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, opinion.ErrRunNotFound),
		errors.Is(err, opinion.ErrReleaseNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
