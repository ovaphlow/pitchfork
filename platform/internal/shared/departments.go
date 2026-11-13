package shared

import (
	"fmt"
	"net/http"
	"unicode/utf8"
)

// registerDepartments 挂载 /departments（departments.go）。
//
// 只读路由只要求会话（申领科室下拉等业务页面依赖部门目录）；
// 写路由挂 base_data:manage 门禁（043：组织管理员能维护目录）。
func (handler *Handler) registerDepartments(mux *http.ServeMux) {
	basePath := Prefix + "/departments"
	mux.HandleFunc("GET "+basePath, handler.listDepartments)
	mux.HandleFunc("GET "+basePath+"/", handler.listDepartments)
	mux.HandleFunc("POST "+basePath, handler.createDepartment)
	mux.HandleFunc("POST "+basePath+"/", handler.createDepartment)
	mux.HandleFunc("GET "+basePath+"/{id}", handler.getDepartment)
	mux.HandleFunc("PUT "+basePath+"/{id}", handler.replaceDepartment)
	mux.HandleFunc("DELETE "+basePath+"/{id}", handler.deleteDepartment)
}

// departmentResource 与 Nexus 的 Department 完全同形（含 member_count）。
type departmentResource struct {
	ID          string `json:"id"`
	Code        string `json:"code"`
	Name        string `json:"name"`
	Description string `json:"description"`
	ParentCode  string `json:"parent_code"`
	SortOrder   int64  `json:"sort_order"`
	MemberCount int64  `json:"member_count"`
	CreatedAt   string `json:"created_at"`
	UpdatedAt   string `json:"updated_at"`
}

// departmentList 是 Nexus 的 records + meta.total 信封（departments 与 subject-departments 用）。
type departmentList struct {
	Records []departmentResource `json:"records"`
	Meta    departmentMeta       `json:"meta"`
}

type departmentMeta struct {
	Total int64 `json:"total"`
}

// departmentInput 是创建/整替换部门请求体（缺失字段与 Nexus serde 的 Option 语义一致）。
type departmentInput struct {
	Code        string  `json:"code"`
	Name        string  `json:"name"`
	Description *string `json:"description"`
	ParentCode  *string `json:"parent_code"`
	SortOrder   *int64  `json:"sort_order"`
}

const selectDepartments = `
SELECT department.id, department.code, department.name, department.description,
       department.parent_code, department.sort_order,
       (SELECT COUNT(*) FROM subject_departments WHERE department_id = department.id) AS member_count,
       department.created_at, department.updated_at
FROM departments AS department`

func scanDepartment(scanner rowScanner) (departmentResource, error) {
	var department departmentResource
	err := scanner.Scan(
		&department.ID,
		&department.Code,
		&department.Name,
		&department.Description,
		&department.ParentCode,
		&department.SortOrder,
		&department.MemberCount,
		&department.CreatedAt,
		&department.UpdatedAt,
	)
	return department, err
}

func (handler *Handler) listDepartments(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	limit, offset, queryErr := pageQuery(request)
	if queryErr.status != 0 {
		handler.fail(responseWriter, queryErr)
		return
	}
	var total int64
	if err := handler.store.DB.QueryRowContext(request.Context(),
		`SELECT COUNT(*) FROM departments`).Scan(&total); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	rows, err := handler.store.DB.QueryContext(request.Context(),
		selectDepartments+" ORDER BY department.sort_order, department.code LIMIT ? OFFSET ?", limit, offset)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	records := make([]departmentResource, 0, limit)
	for rows.Next() {
		department, scanErr := scanDepartment(rows)
		if scanErr != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		records = append(records, department)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, departmentList{
		Records: records,
		Meta:    departmentMeta{Total: total},
	})
}

func (handler *Handler) createDepartment(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requirePermission(responseWriter, request, BaseDataManagePermission); !ok {
		return
	}
	var input departmentInput
	if inputErr := decodeJSON(request, &input); inputErr.status != 0 {
		handler.fail(responseWriter, inputErr)
		return
	}
	if validationErr := validateDepartmentInput(input); validationErr.status != 0 {
		handler.fail(responseWriter, validationErr)
		return
	}
	if parentErr := handler.ensureDepartmentParent(request, input, ""); parentErr.status != 0 {
		handler.fail(responseWriter, parentErr)
		return
	}
	identifier, err := newID()
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	_, err = handler.store.DB.ExecContext(request.Context(), `
INSERT INTO departments (id, code, name, description, parent_code, sort_order)
VALUES (?, ?, ?, ?, ?, ?)`,
		identifier,
		trimSpace(input.Code),
		trimSpace(input.Name),
		trimSpace(departmentText(input.Description)),
		trimSpace(departmentText(input.ParentCode)),
		departmentSortOrder(input.SortOrder),
	)
	if err != nil {
		if isSharedUniqueViolation(err) {
			handler.fail(responseWriter, conflict("department code already exists"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	created, fetchErr := handler.fetchDepartment(request, identifier)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusCreated, created)
}

func (handler *Handler) getDepartment(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
		return
	}
	department, fetchErr := handler.fetchDepartment(request, request.PathValue("id"))
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusOK, department)
}

func (handler *Handler) replaceDepartment(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requirePermission(responseWriter, request, BaseDataManagePermission); !ok {
		return
	}
	var input departmentInput
	if inputErr := decodeJSON(request, &input); inputErr.status != 0 {
		handler.fail(responseWriter, inputErr)
		return
	}
	if validationErr := validateDepartmentInput(input); validationErr.status != 0 {
		handler.fail(responseWriter, validationErr)
		return
	}
	identifier := request.PathValue("id")
	existing, fetchErr := handler.fetchDepartment(request, identifier)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	// code 创建后不可变：subject_departments 与外部系统按 code 引用部门。
	if trimSpace(input.Code) != existing.Code {
		handler.fail(responseWriter, badRequest("code cannot be changed after creation"))
		return
	}
	if parentErr := handler.ensureDepartmentParent(request, input, identifier); parentErr.status != 0 {
		handler.fail(responseWriter, parentErr)
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(), `
UPDATE departments
SET name = ?, description = ?, parent_code = ?, sort_order = ?, updated_at = ?
WHERE id = ?`,
		trimSpace(input.Name),
		trimSpace(departmentText(input.Description)),
		trimSpace(departmentText(input.ParentCode)),
		departmentSortOrder(input.SortOrder),
		timestampNow(),
		identifier,
	)
	if err != nil {
		if isSharedUniqueViolation(err) {
			handler.fail(responseWriter, conflict("department code already exists"))
			return
		}
		handler.fail(responseWriter, internalError())
		return
	}
	if affected, rowsErr := result.RowsAffected(); rowsErr == nil && affected == 0 {
		handler.fail(responseWriter, notFound("department not found"))
		return
	}
	updated, fetchErr := handler.fetchDepartment(request, identifier)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusOK, updated)
}

func (handler *Handler) deleteDepartment(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requirePermission(responseWriter, request, BaseDataManagePermission); !ok {
		return
	}
	identifier := request.PathValue("id")
	var members int64
	if err := handler.store.DB.QueryRowContext(request.Context(),
		`SELECT COUNT(*) FROM subject_departments WHERE department_id = ?`, identifier).Scan(&members); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	// 仍有成员的部门不能删：ON DELETE RESTRICT 的语义，HTTP 层映射为 409。
	if members > 0 {
		handler.fail(responseWriter, conflict(fmt.Sprintf(
			"department is assigned to %d subject(s); unassign before deleting", members)))
		return
	}
	result, err := handler.store.DB.ExecContext(request.Context(),
		`DELETE FROM departments WHERE id = ?`, identifier)
	if err != nil {
		if isSharedForeignKeyViolation(err) {
			var count int64
			_ = handler.store.DB.QueryRowContext(request.Context(),
				`SELECT COUNT(*) FROM subject_departments WHERE department_id = ?`, identifier).Scan(&count)
			handler.fail(responseWriter, conflict(fmt.Sprintf(
				"department is assigned to %d subject(s); unassign before deleting", count)))
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
		handler.fail(responseWriter, notFound("department not found"))
		return
	}
	writeNoContent(responseWriter)
}

func (handler *Handler) fetchDepartment(request *http.Request, identifier string) (departmentResource, apiError) {
	row := handler.store.DB.QueryRowContext(request.Context(),
		selectDepartments+" WHERE department.id = ?", identifier)
	department, err := scanDepartment(row)
	if isNoRows(err) {
		return departmentResource{}, notFound("department not found")
	}
	if err != nil {
		return departmentResource{}, internalError()
	}
	return department, apiError{}
}

// ensureDepartmentParent 校验 parent_code：空串为根、不得自指、必须存在（更新时按上级 ID 判自指）。
func (handler *Handler) ensureDepartmentParent(request *http.Request, input departmentInput, selfID string) apiError {
	parentCode := trimSpace(departmentText(input.ParentCode))
	if parentCode == "" {
		return apiError{}
	}
	if parentCode == trimSpace(input.Code) {
		return badRequest("parent_code cannot point to the department itself")
	}
	var parentID string
	err := handler.store.DB.QueryRowContext(request.Context(),
		`SELECT id FROM departments WHERE code = ?`, parentCode).Scan(&parentID)
	if isNoRows(err) {
		return badRequest("unknown parent_code: " + parentCode)
	}
	if err != nil {
		return internalError()
	}
	if selfID != "" && parentID == selfID {
		return badRequest("parent_code cannot point to the department itself")
	}
	return apiError{}
}

// validateDepartmentInput 复刻 Nexus 校验：code 1..64（字母/数字/点/横线/下划线），name 1..120 字。
func validateDepartmentInput(input departmentInput) apiError {
	code := trimSpace(input.Code)
	if code == "" || len(code) > 64 {
		return badRequest("code is required and must be at most 64 characters")
	}
	for index := 0; index < len(code); index++ {
		character := code[index]
		allowed := (character >= 'a' && character <= 'z') ||
			(character >= 'A' && character <= 'Z') ||
			(character >= '0' && character <= '9') ||
			character == '.' || character == '-' || character == '_'
		if !allowed {
			return badRequest("code may only contain letters, digits, dots, dashes, and underscores")
		}
	}
	name := trimSpace(input.Name)
	if name == "" || utf8.RuneCountInString(name) > 120 {
		return badRequest("name is required and must be at most 120 characters")
	}
	return apiError{}
}

func departmentText(value *string) string {
	if value == nil {
		return ""
	}
	return *value
}

func departmentSortOrder(value *int64) int64 {
	if value == nil {
		return 0
	}
	return *value
}
