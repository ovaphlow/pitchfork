package httpapi

import (
	"errors"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// messagesBase is the unified resource path of the dispatch messages
// (即时通讯消息). The collection and the item routes live under the owning
// run (/drills/{rid}/messages and /drills/{rid}/messages/{mid}); the
// literal messages segment is more specific than the /drills/{id} item
// route and never collides with it (same pattern as the step-record,
// sim-event, assessment, command-session, orders and departments
// routes). Messages are sent with POST and removed with DELETE; they
// are immutable, so there is no PUT/PATCH (PUT on an item answers 405
// with Allow: GET, POST, DELETE).
const messagesBase = prototypePrefix + "/drills/{rid}/messages"

// messagesHandler adapts the dispatch service to the HTTP routing
// layer. It serves the per-run message collection (GET list / POST
// create) and the item routes (GET / DELETE by message id); other
// methods yield a JSON 405 with Allow. The owning run comes from the
// route path: writes require the run to be 进行中 (400 otherwise) and a
// missing run is a 404 on every route (checked before the write gate).
type messagesHandler struct {
	service *dispatch.Service
}

// newMessagesHandler builds the handler over the dispatch store and the
// drill store; the drill store backs the run source of the service (the
// run existence check and the write gate).
func newMessagesHandler(drillStore drills.Store, dispatchStore dispatch.Store) *messagesHandler {
	return &messagesHandler{service: dispatch.NewService(dispatchStore, dispatch.NewRunSource(drillStore))}
}

func (h *messagesHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
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

func (h *messagesHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r)
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "GET, POST, DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// messageBody mirrors the client-supplied fields of a message creation.
// id, run_id, sent_at and the timestamps are never part of the body:
// id is server-generated, run_id comes from the route path (a body that
// carries run_id has it ignored), sent_at is set by the service at
// creation (a message is sent the moment it is created, so a body that
// carries sent_at has it ignored). sender_type and content are
// required; sender_name defaults to an empty string; created_by passes
// through (empty when omitted) because the prototype has no auth
// context.
type messageBody struct {
	SenderType string `json:"sender_type"`
	SenderName string `json:"sender_name"`
	Content    string `json:"content"`
	CreatedBy  string `json:"created_by"`
}

func (h *messagesHandler) create(w http.ResponseWriter, r *http.Request) {
	var body messageBody
	if !decodeOrderJSON(w, r, &body) {
		return
	}
	message, err := h.service.CreateMessage(r.Context(), r.PathValue("rid"), dispatch.MessageInput{
		SenderType: dispatch.SenderType(body.SenderType),
		SenderName: body.SenderName,
		Content:    body.Content,
		CreatedBy:  body.CreatedBy,
	})
	if err != nil {
		writeMessageError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, message)
}

// messageListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type messageListResponse struct {
	Records []dispatch.Message `json:"records"`
	Meta    metaResponse       `json:"meta"`
}

func (h *messagesHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseMessageListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListMessages(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeMessageError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, messageListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseMessageListFilter reads the sender_type/limit/offset query
// parameters. A non-empty sender_type must be one of the allowed
// values, otherwise 400; limit/offset must be non-negative integers
// (default limit 50), otherwise 400.
func parseMessageListFilter(w http.ResponseWriter, r *http.Request) (dispatch.MessageFilter, bool) {
	query := r.URL.Query()
	filter := dispatch.MessageFilter{Limit: defaultPageSize}

	if raw := query.Get("sender_type"); raw != "" {
		senderType := dispatch.SenderType(raw)
		if !senderType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid sender_type")
			return dispatch.MessageFilter{}, false
		}
		filter.SenderType = senderType
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return dispatch.MessageFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return dispatch.MessageFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

func (h *messagesHandler) get(w http.ResponseWriter, r *http.Request) {
	message, err := h.service.GetMessage(r.Context(), r.PathValue("rid"), r.PathValue("mid"))
	if err != nil {
		writeMessageError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, message)
}

func (h *messagesHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteMessage(r.Context(), r.PathValue("rid"), r.PathValue("mid")); err != nil {
		writeMessageError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// writeMessageError maps the dispatch service errors of the message
// resource to JSON error responses: validation errors become 400,
// unknown runs or messages 404, everything else 500.
func writeMessageError(w http.ResponseWriter, err error) {
	var validationError *dispatch.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, dispatch.ErrRunNotFound),
		errors.Is(err, dispatch.ErrMessageNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
