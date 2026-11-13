package shared

import (
	"context"
	"encoding/json"
	"net/http"
)

// registerInteractions 挂载 /interactions（interactions.go）。
//
// 尾斜杠规则与 settings/messages 一致：集合根两种写法都可用，
// 更深路径回 404 空体（axum 默认 fallback）。
func (handler *Handler) registerInteractions(mux *http.ServeMux) {
	base := Prefix + "/interactions"
	mux.HandleFunc("GET "+base, handler.interactionsList)
	mux.HandleFunc("GET "+base+"/", sharedModuleCollectionRoot(base, handler.interactionsList))
	mux.HandleFunc("POST "+base, handler.interactionsCreate)
	mux.HandleFunc("POST "+base+"/", sharedModuleCollectionRoot(base, handler.interactionsCreate))
	mux.HandleFunc("GET "+base+"/{id}", handler.interactionsGet)
	mux.HandleFunc("PUT "+base+"/{id}", handler.interactionsUpdate)
	mux.HandleFunc("DELETE "+base+"/{id}", handler.interactionsDelete)
}

// interaction 是 interactions 模块的 HTTP 输出形状（与 Nexus Interaction 同形）。
type interaction struct {
	ID              string          `json:"id"`
	ActorID         string          `json:"actor_id"`
	TargetType      string          `json:"target_type"`
	TargetID        string          `json:"target_id"`
	InteractionType string          `json:"interaction_type"`
	Value           *float64        `json:"value"`
	Payload         json.RawMessage `json:"payload"`
	CreatedAt       string          `json:"created_at"`
	UpdatedAt       string          `json:"updated_at"`
}

// interactionCreateInput 是创建交互的请求体；actor_id 由会话派生。
type interactionCreateInput struct {
	TargetType      string          `json:"target_type"`
	TargetID        string          `json:"target_id"`
	InteractionType string          `json:"interaction_type"`
	Value           *float64        `json:"value"`
	Payload         json.RawMessage `json:"payload"`
}

// interactionUpdateInput 是更新交互的请求体：value / payload 至少给一个。
type interactionUpdateInput struct {
	Value   *float64        `json:"value"`
	Payload json.RawMessage `json:"payload"`
}

// interactionRecord 是 interactions 表的一行。
type interactionRecord struct {
	ID              string
	ActorID         string
	TargetType      string
	TargetID        string
	InteractionType string
	Value           *float64
	Payload         string
	CreatedAt       string
	UpdatedAt       string
}

func readInteractionRecord(scanner sharedModuleRowScanner) (interactionRecord, error) {
	var record interactionRecord
	err := scanner.Scan(
		&record.ID,
		&record.ActorID,
		&record.TargetType,
		&record.TargetID,
		&record.InteractionType,
		&record.Value,
		&record.Payload,
		&record.CreatedAt,
		&record.UpdatedAt,
	)
	return record, err
}

func (record interactionRecord) interaction() (interaction, apiError) {
	payload, failure := sharedModuleStoredPayload(record.Payload)
	if failure != (apiError{}) {
		return interaction{}, failure
	}
	return interaction{
		ID:              record.ID,
		ActorID:         record.ActorID,
		TargetType:      record.TargetType,
		TargetID:        record.TargetID,
		InteractionType: record.InteractionType,
		Value:           record.Value,
		Payload:         payload,
		CreatedAt:       record.CreatedAt,
		UpdatedAt:       record.UpdatedAt,
	}, apiError{}
}

func (handler *Handler) interactionsList(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	limit, offset, failure := pageQuery(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}

	statement := "SELECT id, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at FROM interactions WHERE 1 = 1"
	arguments := make([]any, 0, 6)
	for _, filter := range []struct {
		name   string
		column string
	}{
		{"actor_id", "actor_id"},
		{"target_type", "target_type"},
		{"target_id", "target_id"},
		{"interaction_type", "interaction_type"},
	} {
		if value := request.URL.Query().Get(filter.name); value != "" {
			statement += " AND " + filter.column + " = ?"
			arguments = append(arguments, value)
		}
	}
	statement += " ORDER BY created_at DESC LIMIT ? OFFSET ?"
	arguments = append(arguments, limit, offset)

	rows, err := handler.store.DB.QueryContext(request.Context(), statement, arguments...)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	interactions := make([]interaction, 0)
	for rows.Next() {
		record, err := readInteractionRecord(rows)
		if err != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		item, failure := record.interaction()
		if failure != (apiError{}) {
			handler.fail(responseWriter, failure)
			return
		}
		interactions = append(interactions, item)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, interactions)
}

func (handler *Handler) interactionsCreate(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return
	}
	var input interactionCreateInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	if trimSpace(input.TargetType) == "" ||
		trimSpace(input.TargetID) == "" ||
		trimSpace(input.InteractionType) == "" {
		handler.fail(responseWriter, badRequest("target_type, target_id, and interaction_type are required"))
		return
	}
	payload, failure := sharedModuleDefaultObjectPayload(input.Payload)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	id, err := newID()
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	var value any
	if input.Value != nil {
		value = *input.Value
	}
	_, err = handler.store.DB.ExecContext(request.Context(), `
		INSERT INTO interactions (id, actor_id, target_type, target_id, interaction_type, value, payload)
		VALUES (?, ?, ?, ?, ?, ?, ?)`,
		id, session.SubjectID, input.TargetType, input.TargetID, input.InteractionType, value, string(payload),
	)
	if err != nil {
		if sharedModuleIsUniqueViolation(err) {
			handler.fail(responseWriter, conflict("this interaction already exists for the current actor and target"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	item, failure := handler.fetchInteraction(request.Context(), id)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, item)
}

func (handler *Handler) interactionsGet(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	item, failure := handler.fetchInteraction(request.Context(), request.PathValue("id"))
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) interactionsUpdate(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return
	}
	var input interactionUpdateInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	payload, payloadProvided, failure := sharedModuleOptionalObjectPayload(input.Payload)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	if input.Value == nil && !payloadProvided {
		handler.fail(responseWriter, badRequest("value or payload is required"))
		return
	}
	id := request.PathValue("id")
	existing, failure := handler.fetchOwnedInteraction(request.Context(), id, session.SubjectID)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	value := existing.Value
	if input.Value != nil {
		value = input.Value
	}
	if !payloadProvided {
		payload = existing.Payload
	}
	_, err := handler.store.DB.ExecContext(request.Context(), `
		UPDATE interactions
		SET value = ?, payload = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
		WHERE id = ? AND actor_id = ?`,
		value, string(payload), id, session.SubjectID,
	)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	item, failure := handler.fetchInteraction(request.Context(), id)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) interactionsDelete(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(),
		"DELETE FROM interactions WHERE id = ? AND actor_id = ?", request.PathValue("id"), session.SubjectID)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	affected, err := result.RowsAffected()
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	if affected == 0 {
		handler.fail(responseWriter, notFound("interaction not found"))
		return
	}
	writeNoContent(responseWriter)
}

func (handler *Handler) fetchInteraction(ctx context.Context, id string) (interaction, apiError) {
	record, err := readInteractionRecord(handler.store.DB.QueryRowContext(ctx, `
		SELECT id, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at
		FROM interactions WHERE id = ?`, id))
	if isNoRows(err) {
		return interaction{}, notFound("interaction not found")
	}
	if err != nil {
		return interaction{}, internalError()
	}
	return record.interaction()
}

func (handler *Handler) fetchOwnedInteraction(ctx context.Context, id string, actorID string) (interaction, apiError) {
	record, err := readInteractionRecord(handler.store.DB.QueryRowContext(ctx, `
		SELECT id, actor_id, target_type, target_id, interaction_type, value, payload, created_at, updated_at
		FROM interactions WHERE id = ? AND actor_id = ?`, id, actorID))
	if isNoRows(err) {
		return interaction{}, notFound("interaction not found")
	}
	if err != nil {
		return interaction{}, internalError()
	}
	return record.interaction()
}
