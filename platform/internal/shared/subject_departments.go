package shared

import "net/http"

// registerSubjectDepartments 挂载 /subject-departments（subject_departments.go）。
//
// 只读列表只要求会话；“设置某人的主部门”是改人（账号管理），写路由挂 access:manage，
// 与部门目录的 base_data:manage 分离（043 §11 裁定 2）。
func (handler *Handler) registerSubjectDepartments(mux *http.ServeMux) {
	basePath := Prefix + "/subject-departments"
	mux.HandleFunc("GET "+basePath, handler.listSubjectDepartments)
	mux.HandleFunc("GET "+basePath+"/", handler.listSubjectDepartments)
	mux.HandleFunc("PUT "+basePath+"/subjects/{subjectID}", handler.replaceSubjectDepartment)
}

// subjectDepartmentAssignment 与 Nexus 的 Assignment 完全同形。
type subjectDepartmentAssignment struct {
	ID                 string `json:"id"`
	SubjectID          string `json:"subject_id"`
	DepartmentID       string `json:"department_id"`
	DepartmentCode     string `json:"department_code"`
	DepartmentName     string `json:"department_name"`
	GrantedBySubjectID string `json:"granted_by_subject_id"`
	CreatedAt          string `json:"created_at"`
	UpdatedAt          string `json:"updated_at"`
}

// subjectDepartmentList 是 Nexus 的 records + meta.total 信封。
type subjectDepartmentList struct {
	Records []subjectDepartmentAssignment `json:"records"`
	Meta    departmentMeta                `json:"meta"`
}

// subjectDepartmentInput 是 PUT /subject-departments/subjects/{subjectID} 的请求体；
// department_id 缺失或 null 表示清空（与 Nexus 的 Option 语义一致）。
type subjectDepartmentInput struct {
	DepartmentID *string `json:"department_id"`
}

const selectSubjectDepartmentAssignments = `
SELECT assignment.id, assignment.subject_id, assignment.department_id,
       department.code AS department_code, department.name AS department_name,
       assignment.granted_by_subject_id, assignment.created_at, assignment.updated_at
FROM subject_departments AS assignment
JOIN departments AS department ON department.id = assignment.department_id`

func scanSubjectDepartmentAssignment(scanner rowScanner) (subjectDepartmentAssignment, error) {
	var assignment subjectDepartmentAssignment
	err := scanner.Scan(
		&assignment.ID,
		&assignment.SubjectID,
		&assignment.DepartmentID,
		&assignment.DepartmentCode,
		&assignment.DepartmentName,
		&assignment.GrantedBySubjectID,
		&assignment.CreatedAt,
		&assignment.UpdatedAt,
	)
	return assignment, err
}

func (handler *Handler) listSubjectDepartments(responseWriter http.ResponseWriter, request *http.Request) {
	if _, ok := handler.requireSession(responseWriter, request); !ok {
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

	filterClause := ""
	countQuery := `SELECT COUNT(*) FROM subject_departments AS assignment`
	countArguments := make([]any, 0, len(subjectIDs))
	if len(subjectIDs) > 0 {
		filterClause = " WHERE assignment.subject_id IN (" + sharedPlaceholders(len(subjectIDs)) + ")"
		for _, subjectID := range subjectIDs {
			countArguments = append(countArguments, subjectID)
		}
	}
	var total int64
	if err := handler.store.DB.QueryRowContext(request.Context(),
		countQuery+filterClause, countArguments...).Scan(&total); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}

	selectArguments := make([]any, 0, len(subjectIDs)+2)
	for _, subjectID := range subjectIDs {
		selectArguments = append(selectArguments, subjectID)
	}
	selectArguments = append(selectArguments, limit, offset)
	rows, err := handler.store.DB.QueryContext(request.Context(),
		selectSubjectDepartmentAssignments+filterClause+
			" ORDER BY assignment.subject_id LIMIT ? OFFSET ?", selectArguments...)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	defer rows.Close()

	records := make([]subjectDepartmentAssignment, 0, limit)
	for rows.Next() {
		assignment, scanErr := scanSubjectDepartmentAssignment(rows)
		if scanErr != nil {
			handler.fail(responseWriter, internalError())
			return
		}
		records = append(records, assignment)
	}
	if err := rows.Err(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	writeJSON(responseWriter, http.StatusOK, subjectDepartmentList{
		Records: records,
		Meta:    departmentMeta{Total: total},
	})
}

// replaceSubjectDepartment 全量替换某主体的部门归属；department_id: null/缺失 表示清空。
func (handler *Handler) replaceSubjectDepartment(responseWriter http.ResponseWriter, request *http.Request) {
	session, ok := handler.requirePermission(responseWriter, request, AccessManagePermission)
	if !ok {
		return
	}
	subjectID, parameterErr := validateSharedSubjectID(request.PathValue("subjectID"))
	if parameterErr.status != 0 {
		handler.fail(responseWriter, parameterErr)
		return
	}
	var input subjectDepartmentInput
	if inputErr := decodeJSON(request, &input); inputErr.status != 0 {
		handler.fail(responseWriter, inputErr)
		return
	}

	departmentID := ""
	if input.DepartmentID != nil {
		candidate := trimSpace(*input.DepartmentID)
		if candidate != "" {
			var existingID string
			err := handler.store.DB.QueryRowContext(request.Context(),
				`SELECT id FROM departments WHERE id = ?`, candidate).Scan(&existingID)
			if isNoRows(err) {
				handler.fail(responseWriter, badRequest("unknown department_id: "+candidate))
				return
			}
			if err != nil {
				handler.fail(responseWriter, internalError())
				return
			}
			departmentID = existingID
		}
	}

	transaction, err := handler.store.DB.BeginTx(request.Context(), nil)
	if err != nil {
		handler.fail(responseWriter, internalError())
		return
	}
	if _, err := transaction.ExecContext(request.Context(),
		`DELETE FROM subject_departments WHERE subject_id = ?`, subjectID); err != nil {
		_ = transaction.Rollback()
		handler.fail(responseWriter, internalError())
		return
	}
	if departmentID != "" {
		identifier, idErr := newID()
		if idErr != nil {
			_ = transaction.Rollback()
			handler.fail(responseWriter, internalError())
			return
		}
		if _, err := transaction.ExecContext(request.Context(), `
INSERT INTO subject_departments (id, subject_id, department_id, granted_by_subject_id)
VALUES (?, ?, ?, ?)`, identifier, subjectID, departmentID, session.SubjectID); err != nil {
			_ = transaction.Rollback()
			handler.fail(responseWriter, internalError())
			return
		}
	}
	if err := transaction.Commit(); err != nil {
		handler.fail(responseWriter, internalError())
		return
	}

	assignments, fetchErr := handler.fetchSubjectDepartmentAssignments(request, subjectID)
	if fetchErr.status != 0 {
		handler.fail(responseWriter, fetchErr)
		return
	}
	writeJSON(responseWriter, http.StatusOK, assignments)
}

func (handler *Handler) fetchSubjectDepartmentAssignments(request *http.Request, subjectID string) ([]subjectDepartmentAssignment, apiError) {
	rows, err := handler.store.DB.QueryContext(request.Context(),
		selectSubjectDepartmentAssignments+" WHERE assignment.subject_id = ?", subjectID)
	if err != nil {
		return nil, internalError()
	}
	defer rows.Close()

	assignments := make([]subjectDepartmentAssignment, 0, 1)
	for rows.Next() {
		assignment, scanErr := scanSubjectDepartmentAssignment(rows)
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
