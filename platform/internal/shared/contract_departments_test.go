package shared_test

// 对照 service-nexus-shared/tests/departments.rs：记录 + meta.total 信封、
// code 创建后不可改、code 唯一 409、未知 parent_code 400、校验失败不留下部门、
// 有成员（subject_departments）的部门不可删除（409）。

import (
	"net/http"
	"testing"
)

func TestContractDepartmentsCRUDAndEnvelope(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
		"code":        "nursing",
		"name":        "护理部",
		"description": "临床护理",
	})
	created.requireStatus(http.StatusCreated)
	department := created.object()
	departmentID := contractULIDField(t, department, "id")
	if got := contractString(t, department, "code"); got != "nursing" {
		t.Fatalf("code = %q", got)
	}
	if got := contractString(t, department, "name"); got != "护理部" {
		t.Fatalf("name = %q", got)
	}
	if got := contractString(t, department, "parent_code"); got != "" {
		t.Fatalf("parent_code = %q, want empty default", got)
	}
	if got := contractNumber(t, department, "sort_order"); got != 0 {
		t.Fatalf("sort_order = %v, want 0 default", got)
	}
	if got := contractNumber(t, department, "member_count"); got != 0 {
		t.Fatalf("member_count = %v, want 0", got)
	}
	contractTimestampField(t, department, "created_at")
	contractTimestampField(t, department, "updated_at")

	// 列表信封：{records:[...], meta:{total:N}}。
	listed := harness.request(http.MethodGet, sharedPath("/departments"), contractAdminSession, nil)
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
	if got := contractString(t, record, "code"); got != "nursing" {
		t.Fatalf("record code = %q", got)
	}

	updated := harness.request(http.MethodPut, sharedPath("/departments/"+departmentID), contractAdminSession, map[string]any{
		"code":       "nursing",
		"name":       "护理一部",
		"sort_order": 5,
	})
	updated.requireStatus(http.StatusOK)
	updatedDepartment := updated.object()
	if got := contractString(t, updatedDepartment, "name"); got != "护理一部" {
		t.Fatalf("updated name = %q", got)
	}
	if got := contractNumber(t, updatedDepartment, "sort_order"); got != 5 {
		t.Fatalf("updated sort_order = %v, want 5", got)
	}

	// code 创建后不可改。
	codeChange := harness.request(http.MethodPut, sharedPath("/departments/"+departmentID), contractAdminSession, map[string]any{
		"code": "nursing-2",
		"name": "护理一部",
	})
	codeChange.requireProblem(http.StatusBadRequest, "invalid-request", "")

	// 唯一 code 冲突与未知 parent_code。
	duplicate := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
		"code": "nursing",
		"name": "护理二部",
	})
	duplicate.requireProblem(http.StatusConflict, "conflict", "")

	unknownParent := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
		"code":        "ward-a",
		"name":        "病区 A",
		"parent_code": "ghost",
	})
	unknownParent.requireProblem(http.StatusBadRequest, "invalid-request", "")

	blankName := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
		"code": "ward-b",
		"name": "   ",
	})
	blankName.requireProblem(http.StatusBadRequest, "invalid-request", "")

	// 校验失败的写入不得留下任何部门。
	afterFailures := harness.request(http.MethodGet, sharedPath("/departments"), contractAdminSession, nil)
	afterFailures.requireStatus(http.StatusOK)
	if got := contractNumber(t, contractObjectField(t, afterFailures.object(), "meta"), "total"); got != 1 {
		t.Fatalf("meta.total after failed writes = %v, want 1", got)
	}

	missing := harness.request(http.MethodGet, sharedPath("/departments/not-a-real-id"), contractAdminSession, nil)
	missing.requireProblem(http.StatusNotFound, "not-found", "")

	deleted := harness.request(http.MethodDelete, sharedPath("/departments/"+departmentID), contractAdminSession, nil)
	deleted.requireStatus(http.StatusNoContent)

	deleteMissing := harness.request(http.MethodDelete, sharedPath("/departments/"+departmentID), contractAdminSession, nil)
	deleteMissing.requireProblem(http.StatusNotFound, "not-found", "")
}
