package shared

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"

	sqlite "modernc.org/sqlite"
)

// registerSettings 挂载 /settings（settings.go）。
//
// 尾斜杠：Nexus 的 axum 路由让 /settings 与 /settings/ 都能到达集合根，
// Go 的 ServeMux 需要分别注册；子树 pattern 命中更深路径时按 axum 默认回 404 空体。
func (handler *Handler) registerSettings(mux *http.ServeMux) {
	base := Prefix + "/settings"
	mux.HandleFunc("GET "+base, handler.settingsList)
	mux.HandleFunc("GET "+base+"/", sharedModuleCollectionRoot(base, handler.settingsList))
	mux.HandleFunc("POST "+base, handler.settingsCreate)
	mux.HandleFunc("POST "+base+"/", sharedModuleCollectionRoot(base, handler.settingsCreate))
	mux.HandleFunc("GET "+base+"/{id}", handler.settingsGet)
	mux.HandleFunc("PUT "+base+"/{id}", handler.settingsReplace)
	mux.HandleFunc("DELETE "+base+"/{id}", handler.settingsDelete)
}

// setting 是 settings 模块的 HTTP 输出形状（与 Nexus Setting 同形）。
type setting struct {
	ID         string          `json:"id"`
	Category   string          `json:"category"`
	Code       string          `json:"code"`
	RootCode   string          `json:"root_code"`
	ParentCode string          `json:"parent_code"`
	Payload    json.RawMessage `json:"payload"`
	CreatedAt  string          `json:"created_at"`
	UpdatedAt  string          `json:"updated_at"`
}

// settingInput 是创建与完整替换的请求体。
type settingInput struct {
	Category   string          `json:"category"`
	Code       string          `json:"code"`
	RootCode   string          `json:"root_code"`
	ParentCode string          `json:"parent_code"`
	Payload    json.RawMessage `json:"payload"`
}

// settingRecord 是 settings 表的一行。
type settingRecord struct {
	ID         string
	Category   string
	Code       string
	RootCode   string
	ParentCode string
	Payload    string
	CreatedAt  string
	UpdatedAt  string
}

func readSettingRecord(scanner sharedModuleRowScanner) (settingRecord, error) {
	var record settingRecord
	err := scanner.Scan(
		&record.ID,
		&record.Category,
		&record.Code,
		&record.RootCode,
		&record.ParentCode,
		&record.Payload,
		&record.CreatedAt,
		&record.UpdatedAt,
	)
	return record, err
}

// setting 把数据库行转成 HTTP 形状；payload 文本必须是合法 JSON。
func (record settingRecord) setting() (setting, apiError) {
	payload, failure := sharedModuleStoredPayload(record.Payload)
	if failure != (apiError{}) {
		return setting{}, failure
	}
	return setting{
		ID:         record.ID,
		Category:   record.Category,
		Code:       record.Code,
		RootCode:   record.RootCode,
		ParentCode: record.ParentCode,
		Payload:    payload,
		CreatedAt:  record.CreatedAt,
		UpdatedAt:  record.UpdatedAt,
	}, apiError{}
}

func (handler *Handler) settingsList(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	limit, offset, failure := pageQuery(request)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}

	statement := "SELECT id, category, code, root_code, parent_code, payload, created_at, updated_at FROM settings"
	arguments := make([]any, 0, 3)
	if category := request.URL.Query().Get("category"); category != "" {
		statement += " WHERE category = ?"
		arguments = append(arguments, category)
	}
	statement += " ORDER BY category, code LIMIT ? OFFSET ?"
	arguments = append(arguments, limit, offset)

	rows, err := handler.store.DB.QueryContext(request.Context(), statement, arguments...)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	settings := make([]setting, 0)
	for rows.Next() {
		record, err := readSettingRecord(rows)
		if err != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		item, failure := record.setting()
		if failure != (apiError{}) {
			handler.fail(responseWriter, failure)
			return
		}
		settings = append(settings, item)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, settings)
}

func (handler *Handler) settingsCreate(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	var input settingInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	payload, failure := sharedModuleSettingPayload(input)
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
		INSERT INTO settings (id, category, code, root_code, parent_code, payload)
		VALUES (?, ?, ?, ?, ?, ?)`,
		id, input.Category, input.Code, input.RootCode, input.ParentCode, string(payload),
	)
	if err != nil {
		if sharedModuleIsUniqueViolation(err) {
			handler.fail(responseWriter, conflict("setting category and code already exist"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	item, failure := handler.fetchSetting(request.Context(), id)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, item)
}

func (handler *Handler) settingsGet(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	item, failure := handler.fetchSetting(request.Context(), request.PathValue("id"))
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) settingsReplace(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	var input settingInput
	if failure := decodeJSON(request, &input); failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	payload, failure := sharedModuleSettingPayload(input)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	id := request.PathValue("id")
	result, err := handler.store.DB.ExecContext(request.Context(), `
		UPDATE settings
		SET category = ?, code = ?, root_code = ?, parent_code = ?, payload = ?,
			updated_at = strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
		WHERE id = ?`,
		input.Category, input.Code, input.RootCode, input.ParentCode, string(payload), id,
	)
	if err != nil {
		if sharedModuleIsUniqueViolation(err) {
			handler.fail(responseWriter, conflict("setting category and code already exist"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	affected, err := result.RowsAffected()
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	if affected == 0 {
		handler.fail(responseWriter, notFound("setting not found"))
		return
	}
	item, failure := handler.fetchSetting(request.Context(), id)
	if failure != (apiError{}) {
		handler.fail(responseWriter, failure)
		return
	}
	writeJSON(responseWriter, http.StatusOK, item)
}

func (handler *Handler) settingsDelete(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(), "DELETE FROM settings WHERE id = ?", request.PathValue("id"))
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
		handler.fail(responseWriter, notFound("setting not found"))
		return
	}
	writeNoContent(responseWriter)
}

func (handler *Handler) fetchSetting(ctx context.Context, id string) (setting, apiError) {
	record, err := readSettingRecord(handler.store.DB.QueryRowContext(ctx, `
		SELECT id, category, code, root_code, parent_code, payload, created_at, updated_at
		FROM settings WHERE id = ?`, id))
	if isNoRows(err) {
		return setting{}, notFound("setting not found")
	}
	if err != nil {
		return setting{}, internalError()
	}
	return record.setting()
}

// sharedModuleSettingPayload 复刻 Nexus 的 validate：先校验 category/code，
// 再要求 payload 是 JSON 对象（缺失或 null 都拒绝）。
func sharedModuleSettingPayload(input settingInput) (json.RawMessage, apiError) {
	if trimSpace(input.Category) == "" || trimSpace(input.Code) == "" {
		return nil, badRequest("category and code are required")
	}
	return sharedModuleObjectPayload(input.Payload)
}

// ---- 以下为 settings/messages/interactions 三模块共用的小工具 ----
// 三个模块文件同属一个开发（plan 055 文件所有权），其他模块不依赖这里。

// sharedModuleRowScanner 抽象 sql.Rows 与 sql.Row 的 Scan。
type sharedModuleRowScanner interface {
	Scan(dest ...any) error
}

// sharedModuleObjectPayload 校验并原样返回对象型 JSON payload。
// Nexus 的 serde 用 Value 承载 payload，这里保留原始字节以维持字段顺序与数字精度。
func sharedModuleObjectPayload(raw json.RawMessage) (json.RawMessage, apiError) {
	var value any
	if len(raw) == 0 || json.Unmarshal(raw, &value) != nil {
		return nil, badRequest("payload must be a JSON object")
	}
	if _, ok := value.(map[string]any); !ok {
		return nil, badRequest("payload must be a JSON object")
	}
	return json.RawMessage(raw), apiError{}
}

// sharedModuleStoredPayload 校验数据库里的 payload 文本；坏数据按 Nexus 语义回 500。
func sharedModuleStoredPayload(raw string) (json.RawMessage, apiError) {
	if !json.Valid([]byte(raw)) {
		return nil, internalError()
	}
	return json.RawMessage(raw), apiError{}
}

// sharedModuleIsUniqueViolation 识别 SQLite 的 UNIQUE / PRIMARY KEY 冲突
// （Nexus 同样只看 2067 / 1555）；modernc 驱动返回扩展错误码。
func sharedModuleIsUniqueViolation(err error) bool {
	var sqliteError *sqlite.Error
	if !errors.As(err, &sqliteError) {
		return false
	}
	code := sqliteError.Code()
	return code == 2067 || code == 1555
}

// sharedModuleCollectionRoot 只放行集合根（base + "/"），更深路径按 axum 默认回 404 空体。
func sharedModuleCollectionRoot(base string, next http.HandlerFunc) http.HandlerFunc {
	return func(responseWriter http.ResponseWriter, request *http.Request) {
		if request.URL.Path != base+"/" {
			sharedModuleRouteNotFound(responseWriter)
			return
		}
		next(responseWriter, request)
	}
}

// sharedModuleRouteNotFound 复刻 axum 对未匹配路由的 404（空体，不带 Problem Details）。
func sharedModuleRouteNotFound(responseWriter http.ResponseWriter) {
	responseWriter.WriteHeader(http.StatusNotFound)
}
