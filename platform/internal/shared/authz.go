package shared

import (
	"encoding/json"
	"net/http"
)

// 043 口径的写门禁权限码。
const (
	AccessManagePermission   = "access:manage"
	BaseDataManagePermission = "base_data:manage"
)

// requireSession 取当前完整会话；失败时写 401 并返回 false。
func (handler *Handler) requireSession(responseWriter http.ResponseWriter, request *http.Request) (Session, bool) {
	if handler.store.RequireSession == nil {
		handler.fail(responseWriter, unauthenticated())
		return Session{}, false
	}
	session, ok := handler.store.RequireSession(request)
	if !ok || session.SubjectID == "" {
		handler.fail(responseWriter, unauthenticated())
		return Session{}, false
	}
	return session, true
}

// requirePermission 在 requireSession 之上做 043 门禁：管理员或拥有权限码才放行。
func (handler *Handler) requirePermission(responseWriter http.ResponseWriter, request *http.Request, required string) (Session, bool) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return Session{}, false
	}
	allowed, err := handler.store.Authorized(request, session, required)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return Session{}, false
	}
	if !allowed {
		handler.fail(responseWriter, permissionRequired(required))
		return Session{}, false
	}
	return session, true
}

// Authorized 判定 platform_admin 或主体权限码包含 required。
func (store Store) Authorized(request *http.Request, session Session, required string) (bool, error) {
	if session.PlatformAdmin {
		return true, nil
	}
	codes, err := store.PermissionCodes(request, session.SubjectID)
	if err != nil {
		return false, err
	}
	for _, code := range codes {
		if code == required {
			return true, nil
		}
	}
	return false, nil
}

// PermissionCodes 返回主体权限码并集（roles.permission_codes 去重，按 role_code 升序）。
func (store Store) PermissionCodes(request *http.Request, subjectID string) ([]string, error) {
	rows, err := store.DB.QueryContext(request.Context(), `
		SELECT role.permission_codes
		FROM subject_roles AS assignment
		JOIN roles AS role ON role.id = assignment.role_id
		WHERE assignment.subject_id = ?
		ORDER BY role.role_code`, subjectID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()

	codes := make([]string, 0, 4)
	for rows.Next() {
		var raw string
		if err := rows.Scan(&raw); err != nil {
			return nil, err
		}
		var parsed []string
		if err := json.Unmarshal([]byte(raw), &parsed); err != nil {
			return nil, err
		}
		for _, code := range parsed {
			if !containsString(codes, code) {
				codes = append(codes, code)
			}
		}
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return codes, nil
}

func containsString(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}
