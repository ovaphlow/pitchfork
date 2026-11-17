package shared

import (
	"database/sql"
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// registerSubjectRoles 挂载 /subject-roles 与 /subject-permissions（subject_roles.go）。
//
// 写路由（全量替换某主体角色）挂 access:manage 门禁；只读列表只要求会话。
// /subject-permissions 是产品后端做接口准入的取值入口：只能查自己，查他人需
// access:manage 或平台管理员（043 D5 越权读收紧）。
func (handler *Handler) registerSubjectRoles(mux *http.ServeMux) {
	basePath := Prefix + "/subject-roles"
	mux.HandleFunc("GET "+basePath, handler.listSubjectRoles)
	mux.HandleFunc("GET "+basePath+"/", handler.listSubjectRoles)
	mux.HandleFunc("PUT "+basePath+"/subjects/{subjectID}", handler.replaceSubjectRoles)

	permissionsPath := Prefix + "/subject-permissions"
	mux.HandleFunc("GET "+permissionsPath, handler.getSubjectPermissions)
	mux.HandleFunc("GET "+permissionsPath+"/", handler.getSubjectPermissions)
}

// maxRoleCodesPerSubject 与 Nexus 的 MAX_ROLE_CODES_PER_SUBJECT 一致。
const maxRoleCodesPerSubject = 100

// subjectRoleAssignment 是响应形状，与 Nexus 的 Assignment 完全同形。
type subjectRoleAssignment struct {
	ID                 string `json:"id"`
	SubjectID          string `json:"subject_id"`
	RoleID             string `json:"role_id"`
	RoleCode           string `json:"role_code"`
	RoleDisplayName    string `json:"role_display_name"`
	GrantedBySubjectID string `json:"granted_by_subject_id"`
	CreatedAt          string `json:"created_at"`
	UpdatedAt          string `json:"updated_at"`
}

// subjectRoleCodesInput 是 PUT /subject-roles/subjects/{subjectID} 的请求体。
// 用指针区分“缺字段”（必须 400，不得静默清空）与“空数组”（清空）。
type subjectRoleCodesInput struct {
	RoleCodes *[]string `json:"role_codes"`
}

// subjectPermissions 与 Nexus 的 SubjectPermissions 完全同形。
type subjectPermissions struct {
	SubjectID       string   `json:"subject_id"`
	RoleCodes       []string `json:"role_codes"`
	PermissionCodes []string `json:"permission_codes"`
	Source          string   `json:"source"`
}

const selectSubjectRoleAssignments = `
SELECT assignment.id, assignment.subject_id, assignment.role_id,
       role.role_code, role.display_name AS role_display_name,
       assignment.granted_by_subject_id, assignment.created_at, assignment.updated_at
FROM subject_roles AS assignment
JOIN roles AS role ON role.id = assignment.role_id`

func scanSubjectRoleAssignment(scanner rowScanner) (subjectRoleAssignment, error) {
	var assignment subjectRoleAssignment
	err := scanner.Scan(
		&assignment.ID,
		&assignment.SubjectID,
		&assignment.RoleID,
		&assignment.RoleCode,
		&assignment.RoleDisplayName,
		&assignment.GrantedBySubjectID,
		&assignment.CreatedAt,
		&assignment.UpdatedAt,
	)
	return assignment, err
}

func (handler *Handler) listSubjectRoles(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	appCode, appErr := handler.requireAppCode(request)
	if appErr != (apiError{}) {
		handler.fail(responseWriter, appErr)
		return
	}
	subjectIDs, parseErr := parseSharedSubjectIDs(request.URL.Query().Get("subject_ids"))
	if parseErr.status != 0 {
		handler.fail(responseWriter, parseErr)
		return
	}
	limit, offset, queryErr := pageQuery(request)
	if queryErr.status != 0 {
		handler.fail(responseWriter, queryErr)
		return
	}

	query := selectSubjectRoleAssignments + " WHERE role.app_code = ?"
	arguments := []any{appCode}
	if len(subjectIDs) > 0 {
		query += " AND assignment.subject_id IN (" + sharedPlaceholders(len(subjectIDs)) + ")"
		for _, subjectID := range subjectIDs {
			arguments = append(arguments, subjectID)
		}
	}
	query += " ORDER BY assignment.subject_id, role.role_code LIMIT ? OFFSET ?"
	arguments = append(arguments, limit, offset)

	rows, err := handler.store.DB.QueryContext(request.Context(), query, arguments...)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	assignments := make([]subjectRoleAssignment, 0, limit)
	for rows.Next() {
		assignment, scanErr := scanSubjectRoleAssignment(rows)
		if scanErr != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		assignments = append(assignments, assignment)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, assignments)
}

// replaceSubjectRoles 全量替换某主体的角色集合；空数组表示清空。
func (handler *Handler) replaceSubjectRoles(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requirePermission(responseWriter, request, AccessManagePermission)
	if !ok {
		return
	}
	appCode, appErr := handler.requireAppCode(request)
	if appErr != (apiError{}) {
		handler.fail(responseWriter, appErr)
		return
	}
	subjectID, parameterErr := validateSharedSubjectID(request.PathValue("subjectID"))
	if parameterErr.status != 0 {
		handler.fail(responseWriter, parameterErr)
		return
	}
	var input subjectRoleCodesInput
	if inputErr := decodeJSON(request, &input); inputErr.status != 0 {
		handler.fail(responseWriter, inputErr)
		return
	}
	if input.RoleCodes == nil {
		handler.fail(responseWriter, badRequest("role_codes is required"))
		return
	}
	roleCodes := normalizeSubjectRoleCodes(*input.RoleCodes)
	if len(roleCodes) > maxRoleCodesPerSubject {
		handler.fail(responseWriter, badRequest(fmt.Sprintf(
			"at most %d role_codes are allowed", maxRoleCodesPerSubject)))
		return
	}

	transaction, err := handler.store.DB.BeginTx(request.Context(), nil)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	roleIDs, resolveErr := resolveSubjectRoleIDs(request, transaction, roleCodes, appCode)
	if resolveErr.status != 0 {
		_ = transaction.Rollback()
		handler.fail(responseWriter, resolveErr)
		return
	}
	// 只替换当前产品的角色分配；其他产品的分配必须保留。
	if _, err := transaction.ExecContext(request.Context(), `
		DELETE FROM subject_roles
		WHERE subject_id = ?
		  AND role_id IN (SELECT id FROM roles WHERE app_code = ?)`, subjectID, appCode); err != nil {
		_ = transaction.Rollback()
		handler.fail(responseWriter, internalError())
		return
	}
	for _, roleID := range roleIDs {
		identifier, idErr := newID()
		if idErr != nil {
			_ = transaction.Rollback()
			handler.fail(responseWriter, internalError())
			return
		}
		if _, err := transaction.ExecContext(request.Context(), `
INSERT INTO subject_roles (id, subject_id, role_id, granted_by_subject_id)
VALUES (?, ?, ?, ?)`, identifier, subjectID, roleID, session.SubjectID); err != nil {
			_ = transaction.Rollback()
			handler.fail(responseWriter, internalError())
			return
		}
	}
	if err := transaction.Commit(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}

	assignments, fetchErr := handler.fetchSubjectRoleAssignments(request, subjectID, appCode)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusOK, assignments)
}

// resolveSubjectRoleIDs 把角色码解析成目录里的角色 ID；未知角色码即 400。
func resolveSubjectRoleIDs(request *http.Request, transaction *sql.Tx, roleCodes []string, appCode string) ([]string, apiError) {
	if len(roleCodes) == 0 {
		return make([]string, 0, 1), apiError{}
	}
	arguments := make([]any, 0, len(roleCodes)+1)
	arguments = append(arguments, appCode)
	for _, roleCode := range roleCodes {
		arguments = append(arguments, roleCode)
	}
	rows, err := transaction.QueryContext(request.Context(),
		"SELECT id, role_code FROM roles WHERE app_code = ? AND role_code IN ("+sharedPlaceholders(len(roleCodes))+")",
		arguments...)
	if err != nil {
		return nil, internalError()
	}
	defer rows.Close()

	identifierByCode := make(map[string]string, len(roleCodes))
	for rows.Next() {
		var identifier string
		var roleCode string
		if err := rows.Scan(&identifier, &roleCode); err != nil {
			return nil, internalError()
		}
		identifierByCode[roleCode] = identifier
	}
	if err := rows.Err(); err != nil {
		return nil, internalError()
	}

	unknown := make([]string, 0, 2)
	for _, roleCode := range roleCodes {
		if _, exists := identifierByCode[roleCode]; !exists {
			unknown = append(unknown, roleCode)
		}
	}
	if len(unknown) > 0 {
		return nil, badRequest("unknown role_code: " + strings.Join(unknown, ", "))
	}
	roleIDs := make([]string, 0, len(roleCodes))
	for _, roleCode := range roleCodes {
		roleIDs = append(roleIDs, identifierByCode[roleCode])
	}
	return roleIDs, apiError{}
}

func (handler *Handler) fetchSubjectRoleAssignments(request *http.Request, subjectID, appCode string) ([]subjectRoleAssignment, apiError) {
	rows, err := handler.store.DB.QueryContext(request.Context(),
		selectSubjectRoleAssignments+" WHERE assignment.subject_id = ? AND role.app_code = ? ORDER BY role.role_code",
		subjectID, appCode)
	if err != nil {
		return nil, internalError()
	}
	defer rows.Close()

	assignments := make([]subjectRoleAssignment, 0, 4)
	for rows.Next() {
		assignment, scanErr := scanSubjectRoleAssignment(rows)
		if scanErr != nil {
			return nil, internalError()
		}
		assignments = append(assignments, assignment)
	}
	if err := rows.Err(); err != nil {
		return nil, internalError()
	}
	return assignments, apiError{}
}

// getSubjectPermissions 返回主体有效角色码与权限码并集（043 D5：只能查自己）。
func (handler *Handler) getSubjectPermissions(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requireSession(responseWriter, request)
	if !ok {
		return
	}
	appCode, appErr := handler.requireAppCode(request)
	if appErr != (apiError{}) {
		handler.fail(responseWriter, appErr)
		return
	}
	subjectID, parameterErr := validateSharedSubjectID(request.URL.Query().Get("subject_id"))
	if parameterErr.status != 0 {
		handler.fail(responseWriter, parameterErr)
		return
	}
	if subjectID != session.SubjectID {
		allowed, err := handler.store.Authorized(request, session, AccessManagePermission)
		if err != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		if !allowed {
			handler.fail(responseWriter, permissionRequired(AccessManagePermission))
			return
		}
	}

	rows, err := handler.store.DB.QueryContext(request.Context(), `
SELECT role.role_code, role.permission_codes
FROM subject_roles AS assignment
JOIN roles AS role ON role.id = assignment.role_id
WHERE assignment.subject_id = ? AND role.app_code = ?
ORDER BY role.role_code`, subjectID, appCode)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	roleCodes := make([]string, 0, 4)
	permissionCodes := make([]string, 0, 8)
	for rows.Next() {
		var roleCode string
		var rawPermissionCodes string
		if err := rows.Scan(&roleCode, &rawPermissionCodes); err != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		roleCodes = append(roleCodes, roleCode)
		var parsed []string
		if err := json.Unmarshal([]byte(rawPermissionCodes), &parsed); err != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		for _, code := range parsed {
			trimmed := trimSpace(code)
			if trimmed == "" {
				continue
			}
			if !containsString(permissionCodes, trimmed) {
				permissionCodes = append(permissionCodes, trimmed)
			}
		}
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}

	writeJSON(responseWriter, http.StatusOK, subjectPermissions{
		SubjectID:       subjectID,
		RoleCodes:       roleCodes,
		PermissionCodes: permissionCodes,
		Source:          "nexus",
	})
}

// sharedPlaceholders 生成 "?, ?, ?" 形式的占位符串（数量来自已校验的长度，不含用户输入）。
func sharedPlaceholders(count int) string {
	if count <= 0 {
		return ""
	}
	return strings.TrimSuffix(strings.Repeat("?, ", count), ", ")
}

// sharedULIDAlphabet 是 Crockford Base32（与 ulid crate 的 LOOKUP 表一致，大小写均可）。
const sharedULIDAlphabet = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"

// isSharedULID 复刻 Nexus 的 Ulid::from_string 校验：26 位 Crockford Base32（大小写不敏感）。
func isSharedULID(value string) bool {
	if len(value) != 26 {
		return false
	}
	for index := 0; index < len(value); index++ {
		character := value[index]
		if character >= 'a' && character <= 'z' {
			character -= 'a' - 'A'
		}
		if !strings.ContainsRune(sharedULIDAlphabet, rune(character)) {
			return false
		}
	}
	return true
}

// validateSharedSubjectID 校验并返回去掉首尾空白的 subject_id（Nexus 同款 detail 文案）。
func validateSharedSubjectID(value string) (string, apiError) {
	trimmed := trimSpace(value)
	if !isSharedULID(trimmed) {
		return "", badRequest("subject_id must be a 26 character ULID")
	}
	return trimmed, apiError{}
}

// parseSharedSubjectIDs 解析逗号分隔的 subject_ids：空段忽略、去重、非法 ULID 400。
func parseSharedSubjectIDs(raw string) ([]string, apiError) {
	result := make([]string, 0, 4)
	for _, candidate := range strings.Split(raw, ",") {
		candidate = trimSpace(candidate)
		if candidate == "" {
			continue
		}
		validated, validationErr := validateSharedSubjectID(candidate)
		if validationErr.status != 0 {
			return nil, validationErr
		}
		if !containsString(result, validated) {
			result = append(result, validated)
		}
	}
	return result, apiError{}
}

// normalizeSubjectRoleCodes 去空白、去重并保持请求顺序。
func normalizeSubjectRoleCodes(codes []string) []string {
	normalized := make([]string, 0, len(codes))
	for _, code := range codes {
		trimmed := trimSpace(code)
		if trimmed == "" {
			continue
		}
		if !containsString(normalized, trimmed) {
			normalized = append(normalized, trimmed)
		}
	}
	return normalized
}
