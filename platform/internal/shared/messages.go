package shared

import (
	"context"
	"encoding/json"
	"net/http"
)

// registerMessages 挂载 /messages（messages.go）。
//
// 尾斜杠规则与 settings 一致：集合根 /messages 与 /messages/ 都可用，
// 更深路径回 404 空体（axum 默认 fallback）。
func (handler *Handler) registerMessages(mux *http.ServeMux) {
	base := Prefix + "/messages"
	mux.HandleFunc("GET "+base, handler.messagesList)
	mux.HandleFunc("GET "+base+"/", sharedModuleCollectionRoot(base, handler.messagesList))
	mux.HandleFunc("POST "+base, handler.messagesCreate)
	mux.HandleFunc("POST "+base+"/", sharedModuleCollectionRoot(base, handler.messagesCreate))
	mux.HandleFunc("GET "+base+"/{id}", handler.messagesGet)
	mux.HandleFunc("PUT "+base+"/{id}", handler.messagesUpdate)
	mux.HandleFunc("PATCH "+base+"/{id}/status", handler.messagesUpdateStatus)
	mux.HandleFunc("DELETE "+base+"/{id}", handler.messagesDelete)
}

// message 是 messages 模块的 HTTP 输出形状（与 Nexus Message 同形）。
type message struct {
	ID           string          `json:"id"`
	AppCode      string          `json:"app_code"`
	MessageType  string          `json:"message_type"`
	SenderID     string          `json:"sender_id"`
	SenderType   string          `json:"sender_type"`
	ReceiverID   string          `json:"receiver_id"`
	ReceiverType string          `json:"receiver_type"`
	Status       string          `json:"status"`
	Payload      json.RawMessage `json:"payload"`
	CreatedAt    string          `json:"created_at"`
	UpdatedAt    string          `json:"updated_at"`
}

// messageCreateInput 是创建消息的请求体；sender_id 由会话派生，不接受客户端输入。
type messageCreateInput struct {
	MessageType  string          `json:"message_type"`
	SenderType   string          `json:"sender_type"`
	ReceiverID   string          `json:"receiver_id"`
	ReceiverType string          `json:"receiver_type"`
	Payload      json.RawMessage `json:"payload"`
}

// messageUpdateInput 是完整更新的请求体：status / payload 至少给一个。
type messageUpdateInput struct {
	Status  *string         `json:"status"`
	Payload json.RawMessage `json:"payload"`
}

// messageStatusInput 是 PATCH /{id}/status 的请求体。
type messageStatusInput struct {
	Status string `json:"status"`
}

// messageRecord 是 messages 表的一行。
type messageRecord struct {
	ID           string
	AppCode      string
	MessageType  string
	SenderID     string
	SenderType   string
	ReceiverID   string
	ReceiverType string
	Status       string
	Payload      string
	CreatedAt    string
	UpdatedAt    string
}

func readMessageRecord(scanner sharedModuleRowScanner) (messageRecord, error) {
	var record messageRecord
	err := scanner.Scan(
		&record.ID,
		&record.AppCode,
		&record.MessageType,
		&record.SenderID,
		&record.SenderType,
		&record.ReceiverID,
		&record.ReceiverType,
		&record.Status,
		&record.Payload,
		&record.CreatedAt,
		&record.UpdatedAt,
	)
	return record, err
}

func (record messageRecord) message() (message, apiError) {
	payload, failure := sharedModuleStoredPayload(record.Payload)
	if failure != (apiError{}) {
		return message{}, failure
	}
	return message{
		ID:           record.ID,
		AppCode:      record.AppCode,
		MessageType:  record.MessageType,
		SenderID:     record.SenderID,
		SenderType:   record.SenderType,
		ReceiverID:   record.ReceiverID,
		ReceiverType: record.ReceiverType,
		Status:       record.Status,
		Payload:      payload,
		CreatedAt:    record.CreatedAt,
		UpdatedAt:    record.UpdatedAt,
	}, apiError{}
}

func (handler *Handler) messagesList(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, failure := handler.requireAppCode(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	limit, offset, failure := pageQuery(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}

	statement := "SELECT id, app_code, message_type, sender_id, sender_type, receiver_id, receiver_type, status, payload, created_at, updated_at FROM messages WHERE app_code = ?"
	arguments := []any{appCode}
	for _, filter := range []struct {
		name   string
		column string
	}{
		{"message_type", "message_type"},
		{"sender_id", "sender_id"},
		{"receiver_id", "receiver_id"},
		{"status", "status"},
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

	messages := make([]message, 0)
	for rows.Next() {
		record, err := readMessageRecord(rows)
		if err != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		item, failure := record.message()
		if failure != (apiError{}) {
			handler.fail(responseWriter, failure)
			return
		}
		messages = append(messages, item)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, messages)
}

func (handler *Handler) messagesCreate(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return
	}
	appCode, failure := handler.requireAppCode(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	var input messageCreateInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	if trimSpace(input.MessageType) == "" ||
		trimSpace(input.SenderType) == "" ||
		trimSpace(input.ReceiverID) == "" ||
		trimSpace(input.ReceiverType) == "" {
		handler.fail(responseWriter, badRequest("message_type, sender_type, receiver_id, and receiver_type are required"))
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
	_, err = handler.store.DB.ExecContext(request.Context(), `
		INSERT INTO messages (id, app_code, message_type, sender_id, sender_type, receiver_id, receiver_type, payload)
		VALUES (?, ?, ?, ?, ?, ?, ?, ?)`,
		id, appCode, input.MessageType, session.SubjectID, input.SenderType, input.ReceiverID, input.ReceiverType, string(payload),
	)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	item, failure := handler.fetchMessage(request.Context(), id, appCode)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, item)
}

func (handler *Handler) messagesGet(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, failure := handler.requireAppCode(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	item, failure := handler.fetchMessage(request.Context(), request.PathValue("id"), appCode)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) messagesUpdate(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, failure := handler.requireAppCode(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	var input messageUpdateInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	payload, payloadProvided, failure := sharedModuleOptionalObjectPayload(input.Payload)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	if input.Status == nil && !payloadProvided {
		handler.fail(responseWriter, badRequest("status or payload is required"))
		return
	}
	id := request.PathValue("id")
	existing, failure := handler.fetchMessage(request.Context(), id, appCode)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	status := existing.Status
	if input.Status != nil {
		status = *input.Status
	}
	if trimSpace(status) == "" {
		handler.fail(responseWriter, badRequest("status cannot be empty"))
		return
	}
	if !payloadProvided {
		payload = existing.Payload
	}
	_, err := handler.store.DB.ExecContext(request.Context(), `
		UPDATE messages
		SET status = ?, payload = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
		WHERE id = ? AND app_code = ?`,
		status, string(payload), id, appCode,
	)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	item, failure := handler.fetchMessage(request.Context(), id, appCode)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) messagesUpdateStatus(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, failure := handler.requireAppCode(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	var input messageStatusInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	if trimSpace(input.Status) == "" {
		handler.fail(responseWriter, badRequest("status is required"))
		return
	}
	id := request.PathValue("id")
	result, err := handler.store.DB.ExecContext(request.Context(), `
		UPDATE messages
		SET status = ?, updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
		WHERE id = ? AND app_code = ?`,
		input.Status, id, appCode,
	)
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
		handler.fail(responseWriter, notFound("message not found"))
		return
	}
	item, failure := handler.fetchMessage(request.Context(), id, appCode)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) messagesDelete(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, failure := handler.requireAppCode(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(),
		"DELETE FROM messages WHERE id = ? AND app_code = ?", request.PathValue("id"), appCode)
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
		handler.fail(responseWriter, notFound("message not found"))
		return
	}
	writeNoContent(responseWriter)
}

func (handler *Handler) fetchMessage(ctx context.Context, id, appCode string) (message, apiError) {
	record, err := readMessageRecord(handler.store.DB.QueryRowContext(ctx, `
		SELECT id, app_code, message_type, sender_id, sender_type, receiver_id, receiver_type, status, payload, created_at, updated_at
		FROM messages WHERE id = ? AND app_code = ?`, id, appCode))
	if isNoRows(err) {
		return message{}, notFound("message not found")
	}
	if err != nil {
		return message{}, internalError()
	}
	return record.message()
}

// sharedModuleDefaultObjectPayload 对应 serde 的 #[serde(default = "empty_object")]：
// 字段缺失时补 {}，显式 null 或非对象仍按违反 payload 约束处理。
func sharedModuleDefaultObjectPayload(raw json.RawMessage) (json.RawMessage, apiError) {
	if len(raw) == 0 {
		return json.RawMessage(`{}`), apiError{}
	}
	return sharedModuleObjectPayload(raw)
}

// sharedModuleOptionalObjectPayload 对应 Option<Value>：缺失与显式 null 都视为未提供，
// 其余情况必须是 JSON 对象。provided 表示调用方是否提供了该字段。
func sharedModuleOptionalObjectPayload(raw json.RawMessage) (json.RawMessage, bool, apiError) {
	if len(raw) == 0 {
		return nil, false, apiError{}
	}
	var value any
	if err := json.Unmarshal(raw, &value); err != nil {
		return nil, true, badRequest("payload must be a JSON object")
	}
	if value == nil {
		return nil, false, apiError{}
	}
	if _, ok := value.(map[string]any); !ok {
		return nil, true, badRequest("payload must be a JSON object")
	}
	return json.RawMessage(raw), true, apiError{}
}
