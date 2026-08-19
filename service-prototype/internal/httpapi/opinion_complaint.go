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

// opinionComplaintsBase is the unified resource path of the opinion
// complaints (投诉处理记录, the visitor complaint tickets of the
// 「投诉处理」 training phase). The collection and the item routes live
// under the owning run (/drills/{rid}/complaints and
// /drills/{rid}/complaints/{cid}); the literal complaints segment is
// more specific than the /drills/{id} item route and never collides
// with it (same pattern as the sim-event, posts, releases and
// media-question routes). Complaints are created with POST and listed
// with GET at the collection; the item route serves GET / PUT / DELETE
// by complaint id.
const opinionComplaintsBase = prototypePrefix + "/drills/{rid}/complaints"

// opinionComplaintHandler adapts the opinion service to the HTTP
// routing layer. It serves the per-run opinion complaint collection
// (GET list / POST create) and the item routes (GET / PUT / DELETE by
// complaint id); other methods yield a JSON 405 with Allow. The owning
// run comes from the route path: writes require the run to be 进行中
// (400 otherwise) and a missing run is a 404 on every route.
type opinionComplaintHandler struct {
	service *opinion.Service
}

// newOpinionComplaintHandler builds the handler over the opinion store
// and the drill store; the drill store backs the run source of the
// service (the run existence check and the write gate). The handler
// shares the injected opinion store with the other opinion handlers, so
// the runs service's run-opinion cleaner (wired in NewMux) cascades to
// every opinion object kind.
func newOpinionComplaintHandler(drillStore drills.Store, opinionStore opinion.Store) *opinionComplaintHandler {
	return &opinionComplaintHandler{service: opinion.NewService(opinionStore, opinion.NewRunSource(drillStore))}
}

func (h *opinionComplaintHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *opinionComplaintHandler) handleItem(w http.ResponseWriter, r *http.Request) {
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

// opinionComplaintBody mirrors the client-supplied fields of the
// request body. run_id, id and the timestamps are never part of the
// body: run_id comes from the route path, id is server-generated and
// the timestamps are server-managed (a body that carries them has them
// ignored). complainant and content are required on both create and
// update; channel defaults to 现场 (create) or keeps its current value
// (update) and must be one of the allowed values; complaint_type
// defaults to 入馆受阻 (create) or keeps its current value (update) and
// must be one of the allowed values; status defaults to 待受理 on
// create (a new complaint only accepts 待受理) or keeps its current
// value on update; handling / handler pass through (” when omitted);
// metadata is kept as raw JSON so the handler can tell an omitted field
// from an explicit null and reject every non-object value; created_by
// passes through (empty when omitted) because the prototype has no auth
// context. closed_at is never part of the body: it is managed by the
// service.
type opinionComplaintBody struct {
	Complainant   string                   `json:"complainant"`
	Channel       opinion.ComplaintChannel `json:"channel"`
	ComplaintType opinion.ComplaintType    `json:"complaint_type"`
	Content       string                   `json:"content"`
	Status        opinion.ComplaintStatus  `json:"status"`
	Handling      string                   `json:"handling"`
	Handler       string                   `json:"handler"`
	Metadata      json.RawMessage          `json:"metadata"`
	CreatedBy     string                   `json:"created_by"`
}

// decodeOpinionComplaintBody reads a single JSON object from the
// request body. An empty, malformed or non-object body (including a
// JSON null) yields a 400 { "error": ... } response, matching the decode
// convention of the other resources; an empty object {} is legal (an
// all-default create is rejected later by the required
// complainant/content checks, a no-op update is rejected the same way).
func decodeOpinionComplaintBody(w http.ResponseWriter, r *http.Request) (opinionComplaintBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionComplaintBody{}, false
	}
	var body opinionComplaintBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return opinionComplaintBody{}, false
	}
	return body, true
}

// parseOpinionComplaintMetadata converts the raw metadata field. An
// omitted field yields (nil, false, true): the create path applies the
// default {} and the update path keeps the current value. A provided
// metadata must be a JSON object (arrays, scalars and explicit null are
// rejected with 400); an empty object {} is legal.
func parseOpinionComplaintMetadata(w http.ResponseWriter, raw json.RawMessage) (map[string]any, bool, bool) {
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

func (h *opinionComplaintHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionComplaintBody(w, r)
	if !ok {
		return
	}
	metadata, _, ok := parseOpinionComplaintMetadata(w, body.Metadata)
	if !ok {
		return
	}
	complaint, err := h.service.CreateComplaint(r.Context(), r.PathValue("rid"), opinion.ComplaintInput{
		Complainant:   body.Complainant,
		Channel:       body.Channel,
		ComplaintType: body.ComplaintType,
		Content:       body.Content,
		Status:        body.Status,
		Handling:      body.Handling,
		Handler:       body.Handler,
		Metadata:      metadata,
		CreatedBy:     body.CreatedBy,
	})
	if err != nil {
		writeOpinionComplaintError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, complaint)
}

// opinionComplaintListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type opinionComplaintListResponse struct {
	Records []opinion.Complaint `json:"records"`
	Meta    metaResponse        `json:"meta"`
}

func (h *opinionComplaintHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseOpinionComplaintListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListComplaints(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeOpinionComplaintError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, opinionComplaintListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseOpinionComplaintListFilter reads the channel/complaint_type/
// status/limit/offset query parameters. A non-empty enum filter must be
// one of the allowed opinion complaint values, otherwise 400; limit/
// offset must be non-negative integers (default limit 50), otherwise
// 400.
func parseOpinionComplaintListFilter(w http.ResponseWriter, r *http.Request) (opinion.ComplaintFilter, bool) {
	query := r.URL.Query()
	filter := opinion.ComplaintFilter{Limit: defaultPageSize}

	if raw := query.Get("channel"); raw != "" {
		channel := opinion.ComplaintChannel(raw)
		if !channel.Valid() {
			writeError(w, http.StatusBadRequest, "invalid channel")
			return opinion.ComplaintFilter{}, false
		}
		filter.Channel = channel
	}
	if raw := query.Get("complaint_type"); raw != "" {
		complaintType := opinion.ComplaintType(raw)
		if !complaintType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid complaint_type")
			return opinion.ComplaintFilter{}, false
		}
		filter.ComplaintType = complaintType
	}
	if raw := query.Get("status"); raw != "" {
		status := opinion.ComplaintStatus(raw)
		if !status.Valid() {
			writeError(w, http.StatusBadRequest, "invalid status")
			return opinion.ComplaintFilter{}, false
		}
		filter.Status = status
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return opinion.ComplaintFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return opinion.ComplaintFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *opinionComplaintHandler) get(w http.ResponseWriter, r *http.Request) {
	complaint, err := h.service.GetComplaint(r.Context(), r.PathValue("rid"), r.PathValue("cid"))
	if err != nil {
		writeOpinionComplaintError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, complaint)
}

func (h *opinionComplaintHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeOpinionComplaintBody(w, r)
	if !ok {
		return
	}
	metadata, hasMetadata, ok := parseOpinionComplaintMetadata(w, body.Metadata)
	if !ok {
		return
	}
	complaint, err := h.service.UpdateComplaint(r.Context(), r.PathValue("rid"), r.PathValue("cid"), opinion.ComplaintUpdate{
		Complainant:   body.Complainant,
		Channel:       body.Channel,
		ComplaintType: body.ComplaintType,
		Content:       body.Content,
		Status:        body.Status,
		Handling:      body.Handling,
		Handler:       body.Handler,
		Metadata:      metadata,
		HasMetadata:   hasMetadata,
		CreatedBy:     body.CreatedBy,
	})
	if err != nil {
		writeOpinionComplaintError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, complaint)
}

func (h *opinionComplaintHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteComplaint(r.Context(), r.PathValue("rid"), r.PathValue("cid")); err != nil {
		writeOpinionComplaintError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeOpinionComplaintError maps the opinion service errors to JSON
// error responses: validation errors become 400, unknown runs or
// complaints 404, everything else 500.
func writeOpinionComplaintError(w http.ResponseWriter, err error) {
	var validationError *opinion.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, opinion.ErrRunNotFound),
		errors.Is(err, opinion.ErrComplaintNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
