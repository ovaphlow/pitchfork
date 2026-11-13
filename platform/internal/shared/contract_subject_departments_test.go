package shared_test

// 对照 service-nexus-shared/tests/subject_departments.rs：一人一主部门（PUT 全量替换，
// null 清空）、列表 records+meta、部门 member_count 来自同一关系表、
// 未知部门/非法主体 400、有成员的部门不可删除（409）。

import (
	"net/http"
	"testing"
)

func TestContractSubjectDepartmentsSetReadClear(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
		"code": "nursing",
		"name": "护理部",
	})
	created.requireStatus(http.StatusCreated)
	departmentID := contractULIDField(t, created.object(), "id")

	assigned := harness.request(http.MethodPut, sharedPath("/subject-departments/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"department_id": departmentID,
	})
	assigned.requireStatus(http.StatusOK)
	assignments := assigned.array()
	if len(assignments) != 1 {
		t.Fatalf("assignments length = %d, want 1", len(assignments))
	}
	assignment, ok := assignments[0].(map[string]any)
	if !ok {
		t.Fatalf("assignment = %v (%T), want object", assignments[0], assignments[0])
	}
	contractULIDField(t, assignment, "id")
	if got := contractString(t, assignment, "subject_id"); got != contractStaffID {
		t.Fatalf("subject_id = %q", got)
	}
	if got := contractString(t, assignment, "department_id"); got != departmentID {
		t.Fatalf("department_id = %q, want %q", got, departmentID)
	}
	if got := contractString(t, assignment, "department_code"); got != "nursing" {
		t.Fatalf("department_code = %q", got)
	}
	if got := contractString(t, assignment, "department_name"); got != "护理部" {
		t.Fatalf("department_name = %q", got)
	}
	if got := contractString(t, assignment, "granted_by_subject_id"); got != contractAdminID {
		t.Fatalf("granted_by_subject_id = %q, want %q", got, contractAdminID)
	}

	// 列表信封：{records, meta:{total}}。
	listed := harness.request(http.MethodGet, sharedPath("/subject-departments?subject_ids="+contractStaffID), contractAdminSession, nil)
	listed.requireStatus(http.StatusOK)
	listedBody := listed.object()
	if got := contractNumber(t, contractObjectField(t, listedBody, "meta"), "total"); got != 1 {
		t.Fatalf("meta.total = %v, want 1", got)
	}
	records := contractArrayField(t, listedBody, "records")
	if len(records) != 1 {
		t.Fatalf("records length = %d, want 1", len(records))
	}
	record, ok := records[0].(map[string]any)
	if !ok {
		t.Fatalf("record = %v (%T), want object", records[0], records[0])
	}
	if got := contractString(t, record, "department_code"); got != "nursing" {
		t.Fatalf("record department_code = %q", got)
	}

	// 部门页 member_count 来自同一张关系表。
	departments := harness.request(http.MethodGet, sharedPath("/departments"), contractAdminSession, nil)
	departments.requireStatus(http.StatusOK)
	departmentRecords := contractArrayField(t, departments.object(), "records")
	if len(departmentRecords) != 1 {
		t.Fatalf("departments records = %d, want 1", len(departmentRecords))
	}
	departmentRecord, ok := departmentRecords[0].(map[string]any)
	if !ok {
		t.Fatalf("department record = %v (%T)", departmentRecords[0], departmentRecords[0])
	}
	if got := contractNumber(t, departmentRecord, "member_count"); got != 1 {
		t.Fatalf("department member_count = %v, want 1", got)
	}

	// 别人的归属为空。
	unrelated := harness.request(http.MethodGet, sharedPath("/subject-departments?subject_ids="+contractOtherID), contractAdminSession, nil)
	unrelated.requireStatus(http.StatusOK)
	unrelatedBody := unrelated.object()
	if got := contractNumber(t, contractObjectField(t, unrelatedBody, "meta"), "total"); got != 0 {
		t.Fatalf("unrelated meta.total = %v, want 0", got)
	}
	if got := len(contractArrayField(t, unrelatedBody, "records")); got != 0 {
		t.Fatalf("unrelated records = %d, want 0", got)
	}

	// department_id: null 清空。
	cleared := harness.request(http.MethodPut, sharedPath("/subject-departments/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"department_id": nil,
	})
	cleared.requireStatus(http.StatusOK)
	if got := len(cleared.array()); got != 0 {
		t.Fatalf("cleared assignments = %d, want 0", got)
	}
}

func TestContractSubjectDepartmentsValidationAndRestrictedDelete(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
		"code": "nursing",
		"name": "护理部",
	})
	created.requireStatus(http.StatusCreated)
	departmentID := contractULIDField(t, created.object(), "id")

	assigned := harness.request(http.MethodPut, sharedPath("/subject-departments/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"department_id": departmentID,
	})
	assigned.requireStatus(http.StatusOK)

	// 有成员的部门不可删除。
	blocked := harness.request(http.MethodDelete, sharedPath("/departments/"+departmentID), contractAdminSession, nil)
	blocked.requireProblem(http.StatusConflict, "conflict", "")

	cleared := harness.request(http.MethodPut, sharedPath("/subject-departments/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"department_id": nil,
	})
	cleared.requireStatus(http.StatusOK)

	deleted := harness.request(http.MethodDelete, sharedPath("/departments/"+departmentID), contractAdminSession, nil)
	deleted.requireStatus(http.StatusNoContent)

	// 校验：未知 department_id / 非法 subject / 非法 subject_ids 过滤。
	unknownDepartment := harness.request(http.MethodPut, sharedPath("/subject-departments/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"department_id": contractOtherID,
	})
	unknownDepartment.requireProblem(http.StatusBadRequest, "invalid-request", "")

	badSubject := harness.request(http.MethodPut, sharedPath("/subject-departments/subjects/subject-1"), contractAdminSession, map[string]any{
		"department_id": nil,
	})
	badSubject.requireProblem(http.StatusBadRequest, "invalid-request", "")

	badFilter := harness.request(http.MethodGet, sharedPath("/subject-departments?subject_ids=not-a-ulid"), contractAdminSession, nil)
	badFilter.requireProblem(http.StatusBadRequest, "invalid-request", "")
}
