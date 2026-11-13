package shared

import (
	"encoding/json"
	"fmt"
	"net/http"
	"strings"
)

// registerRoles 挂载 /roles（roles.go）。
//
// 只读路由只要求会话：角色目录是产品元数据，用户页/角色页依赖它渲染（043 D7）。
// 写路由挂 access:manage 门禁；新增写路由必须在此登记，否则就是新的提权面。
func (handler *Handler) registerRoles(mux *http.ServeMux) {
	basePath := Prefix + "/roles"
	mux.HandleFunc("GET "+basePath, handler.listRoles)
	mux.HandleFunc("GET "+basePath+"/", handler.listRoles)
	mux.HandleFunc("POST "+basePath, handler.createRole)
	mux.HandleFunc("POST "+basePath+"/", handler.createRole)
	mux.HandleFunc("GET "+basePath+"/{id}", handler.getRole)
	mux.HandleFunc("PUT "+basePath+"/{id}", handler.replaceRole)
	mux.HandleFunc("DELETE "+basePath+"/{id}", handler.deleteRole)
}

// roleResource 是响应形状，与 Nexus 的 Role 完全同形。
type roleResource struct {
	ID              string   `json:"id"`
	RoleCode        string   `json:"role_code"`
	DisplayName     string   `json:"display_name"`
	Description     string   `json:"description"`
	PermissionCodes []string `json:"permission_codes"`
	CreatedAt       string   `json:"created_at"`
	UpdatedAt       string   `json:"updated_at"`
}

// roleInput 是创建/整替换角色请求体（缺失字段与 Nexus serde 的 Option 语义一致）。
type roleInput struct {
	RoleCode        string    `json:"role_code"`
	DisplayName     string    `json:"display_name"`
	Description     *string   `json:"description"`
	PermissionCodes *[]string `json:"permission_codes"`
}

const selectRole = `
SELECT id, role_code, display_name, description, permission_codes, created_at, updated_at
FROM roles`

// rowScanner 抽象 *sql.Row 与 *sql.Rows 共有的 Scan。
type rowScanner interface {
	Scan(dest ...any) error
}

func scanRole(scanner rowScanner) (roleResource, error) {
	var role roleResource
	var permissionCodes string
	if err := scanner.Scan(
		&role.ID,
		&role.RoleCode,
		&role.DisplayName,
		&role.Description,
		&permissionCodes,
		&role.CreatedAt,
		&role.UpdatedAt,
	); err != nil {
		return roleResource{}, err
	}
	codes, err := decodeRolePermissionCodes(permissionCodes)
	if err != nil {
		return roleResource{}, err
	}
	role.PermissionCodes = codes
	return role, nil
}

func decodeRolePermissionCodes(raw string) ([]string, error) {
	codes := make([]string, 0, 4)
	if err := json.Unmarshal([]byte(raw), &codes); err != nil {
		return nil, err
	}
	if codes == nil {
		codes = make([]string, 0, 4)
	}
	return codes, nil
}

// listRoles 返回直接 JSON 数组（Nexus 契约：roles 列表不套 records/meta）。
func (handler *Handler) listRoles(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	limit, offset, queryErr := pageQuery(request)
	if queryErr.status != 0 {
		handler.fail(responseWriter, queryErr)
		return
	}
	rows, err := handler.store.DB.QueryContext(request.Context(),
		selectRole+" ORDER BY role_code LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	roles := make([]roleResource, 0, limit)
	for rows.Next() {
		role, scanErr := scanRole(rows)
		if scanErr != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		roles = append(roles, role)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, roles)
}

func (handler *Handler) createRole(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requirePermission(responseWriter, request, AccessManagePermission); !ok {
		return
	}
	var input roleInput
	if inputErr := decodeJSON(request, &input); inputErr.status != 0 {
		handler.fail(responseWriter, inputErr)
		return
	}
	if validationErr := validateRoleInput(input); validationErr.status != 0 {
		handler.fail(responseWriter, validationErr)
		return
	}
	permissionCodes, permissionErr := normalizeRolePermissionCodes(input.PermissionCodes)
	if permissionErr.status != 0 {
		handler.fail(responseWriter, permissionErr)
		return
	}
	identifier, err := newID()
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	encodedPermissions, err := json.Marshal(permissionCodes)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	_, err = handler.store.DB.ExecContext(request.Context(), `
INSERT INTO roles (id, role_code, display_name, description, permission_codes)
VALUES (?, ?, ?, ?, ?)`,
		identifier,
		trimSpace(input.RoleCode),
		trimSpace(input.DisplayName),
		roleDescription(input.Description),
		string(encodedPermissions),
	)
	if err != nil {
		if isSharedUniqueViolation(err) {
			handler.fail(responseWriter, conflict("role_code already exists"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	created, fetchErr := handler.fetchRole(request, identifier)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, created)
}

func (handler *Handler) getRole(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	role, fetchErr := handler.fetchRole(request, request.PathValue("id"))
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusOK, role)
}

func (handler *Handler) replaceRole(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requirePermission(responseWriter, request, AccessManagePermission); !ok {
		return
	}
	var input roleInput
	if inputErr := decodeJSON(request, &input); inputErr.status != 0 {
		handler.fail(responseWriter, inputErr)
		return
	}
	if validationErr := validateRoleInput(input); validationErr.status != 0 {
		handler.fail(responseWriter, validationErr)
		return
	}
	identifier := request.PathValue("id")
	existing, fetchErr := handler.fetchRole(request, identifier)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	// role_code 创建后不可变：改了会破坏历史审计与已分配角色的可读性。
	if trimSpace(input.RoleCode) != existing.RoleCode {
		handler.fail(responseWriter, badRequest("role_code cannot be changed after creation"))
		return
	}
	permissionCodes, permissionErr := normalizeRolePermissionCodes(input.PermissionCodes)
	if permissionErr.status != 0 {
		handler.fail(responseWriter, permissionErr)
		return
	}
	encodedPermissions, err := json.Marshal(permissionCodes)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(), `
UPDATE roles
SET display_name = ?, description = ?, permission_codes = ?, updated_at = ?
WHERE id = ?`,
		trimSpace(input.DisplayName),
		roleDescription(input.Description),
		string(encodedPermissions),
		timestampNow(),
		identifier,
	)
	if err != nil {
		if isSharedUniqueViolation(err) {
			handler.fail(responseWriter, conflict("role_code already exists"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	if affected, rowsErr := result.RowsAffected(); rowsErr == nil && affected == 0 {
		handler.fail(responseWriter, notFound("role not found"))
		return
	}
	updated, fetchErr := handler.fetchRole(request, identifier)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusOK, updated)
}

func (handler *Handler) deleteRole(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requirePermission(responseWriter, request, AccessManagePermission); !ok {
		return
	}
	identifier := request.PathValue("id")
	var assignments int64
	if err := handler.store.DB.QueryRowContext(request.Context(),
		`SELECT COUNT(*) FROM subject_roles WHERE role_id = ?`, identifier).Scan(&assignments); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	// 仍被分配的角色不能删：删掉会留下悬空的用户↔角色引用（ON DELETE RESTRICT 兜底）。
	if assignments > 0 {
		handler.fail(responseWriter, conflict(fmt.Sprintf(
			"role is assigned to %d subject(s); unassign before deleting", assignments)))
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(),
		`DELETE FROM roles WHERE id = ?`, identifier)
	if err != nil {
		if isSharedForeignKeyViolation(err) {
			var count int64
			_ = handler.store.DB.QueryRowContext(request.Context(),
				`SELECT COUNT(*) FROM subject_roles WHERE role_id = ?`, identifier).Scan(&count)
			handler.fail(responseWriter, conflict(fmt.Sprintf(
				"role is assigned to %d subject(s); unassign before deleting", count)))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	affected, rowsErr := result.RowsAffected()
	if rowsErr != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	if affected == 0 {
		handler.fail(responseWriter, notFound("role not found"))
		return
	}
	writeNoContent(responseWriter)
}

func (handler *Handler) fetchRole(request *http.Request, identifier string) (roleResource, apiError) {
	row := handler.store.DB.QueryRowContext(request.Context(), selectRole+" WHERE id = ?", identifier)
	role, err := scanRole(row)
	if isNoRows(err) {
		return roleResource{}, notFound("role not found")
	}
	if err != nil {
		return roleResource{}, internalError()
	}
	return role, apiError{}
}

// validateRoleInput 复刻 Nexus 的 role_code/display_name 校验（文档：小写字母、数字、点，1..64）。
func validateRoleInput(input roleInput) apiError {
	roleCode := trimSpace(input.RoleCode)
	if roleCode == "" || len(roleCode) > 64 {
		return badRequest("role_code is required and must be at most 64 characters")
	}
	for index := 0; index < len(roleCode); index++ {
		character := roleCode[index]
		if !((character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '.') {
			return badRequest("role_code may only contain lowercase letters, digits, and dots")
		}
	}
	if trimSpace(input.DisplayName) == "" {
		return badRequest("display_name is required")
	}
	return apiError{}
}

// normalizeRolePermissionCodes 去重保序，格式为 <域>:<动作>（两段均小写字母/数字/下划线且字母开头）。
func normalizeRolePermissionCodes(codes *[]string) ([]string, apiError) {
	normalized := make([]string, 0, 4)
	if codes == nil {
		return normalized, apiError{}
	}
	for _, code := range *codes {
		trimmed := trimSpace(code)
		if trimmed == "" {
			continue
		}
		if permissionErr := validateRolePermissionCode(trimmed); permissionErr.status != 0 {
			return nil, permissionErr
		}
		duplicate := false
		for _, existing := range normalized {
			if existing == trimmed {
				duplicate = true
				break
			}
		}
		if !duplicate {
			normalized = append(normalized, trimmed)
		}
	}
	return normalized, apiError{}
}

func validateRolePermissionCode(code string) apiError {
	domain, action, found := strings.Cut(code, ":")
	if !found {
		return badRequest("permission code must look like <domain>:<action>: " + code)
	}
	if !isRolePermissionSegment(domain) || !isRolePermissionSegment(action) {
		return badRequest("permission code segments must start with a lowercase letter and contain only lowercase letters, digits, and underscores: " + code)
	}
	return apiError{}
}

func isRolePermissionSegment(segment string) bool {
	if segment == "" {
		return false
	}
	if segment[0] < 'a' || segment[0] > 'z' {
		return false
	}
	for index := 0; index < len(segment); index++ {
		character := segment[index]
		if !((character >= 'a' && character <= 'z') ||
			(character >= '0' && character <= '9') ||
			character == '_') {
			return false
		}
	}
	return true
}

func roleDescription(description *string) string {
	if description == nil {
		return ""
	}
	return trimSpace(*description)
}

// isSharedUniqueViolation 识别 SQLite 唯一约束冲突（modernc 驱动错误文本稳定）。
func isSharedUniqueViolation(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

// isSharedForeignKeyViolation 识别 SQLite 外键/触发器约束冲突（ON DELETE RESTRICT 兜底）。
func isSharedForeignKeyViolation(err error) bool {
	if err == nil {
		return false
	}
	return strings.Contains(err.Error(), "FOREIGN KEY constraint failed") ||
		strings.Contains(err.Error(), "constraint failed")
}
