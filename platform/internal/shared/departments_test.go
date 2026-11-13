package shared_test

import (
	"net/http"
	"testing"
)

// TestDepartmentsCreateListUpdateDelete 对照 Nexus tests/departments.rs::create_list_update_and_delete_department。
func TestDepartmentsCreateListUpdateDelete(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)

	created := catalogCreateDepartment(t, mux, "nursing", "护理部")
	departmentID := created["id"].(string)
	if created["code"] != "nursing" || created["name"] != "护理部" {
		t.Fatalf("created department = %#v", created)
	}
	if created["parent_code"] != "" {
		t.Fatalf("parent_code default = %#v, want empty", created["parent_code"])
	}
	if created["sort_order"] != float64(0) {
		t.Fatalf("sort_order default = %#v", created["sort_order"])
	}
	if created["member_count"] != float64(0) {
		t.Fatalf("member_count default = %#v", created["member_count"])
	}
	if created["description"] != "" {
		t.Fatalf("description default = %#v", created["description"])
	}

	listed := catalogRequest(t, mux, http.MethodGet, "/departments", nil)
	catalogAssertStatus(t, listed, http.StatusOK)
	listedBody := catalogObject(t, listed)
	meta := listedBody["meta"].(map[string]any)
	if meta["total"] != float64(1) {
		t.Fatalf("meta.total = %#v, want 1", meta["total"])
	}
	records := listedBody["records"].([]any)
	if len(records) != 1 || records[0].(map[string]any)["code"] != "nursing" {
		t.Fatalf("listed records = %#v", records)
	}
	// 尾斜杠兼容
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/departments/", nil), http.StatusOK)

	fetched := catalogRequest(t, mux, http.MethodGet, "/departments/"+departmentID, nil)
	catalogAssertStatus(t, fetched, http.StatusOK)
	if catalogObject(t, fetched)["code"] != "nursing" {
		t.Fatalf("fetched department mismatch")
	}

	updatedRecorder := catalogRequest(t, mux, http.MethodPut, "/departments/"+departmentID, map[string]any{
		"code": "nursing", "name": "护理一部", "sort_order": 5,
	})
	catalogAssertStatus(t, updatedRecorder, http.StatusOK)
	updated := catalogObject(t, updatedRecorder)
	if updated["name"] != "护理一部" || updated["sort_order"] != float64(5) {
		t.Fatalf("updated department = %#v", updated)
	}

	// code 不可变
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut, "/departments/"+departmentID, map[string]any{
		"code": "nursing-2", "name": "护理一部",
	}), http.StatusBadRequest)

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodDelete, "/departments/"+departmentID, nil), http.StatusNoContent)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodDelete, "/departments/"+departmentID, nil), http.StatusNotFound)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/departments/"+departmentID, nil), http.StatusNotFound)
}

// TestDepartmentsDuplicateCodeAndUnknownParent 对照 Nexus tests/departments.rs::duplicate_code_and_unknown_parent_are_rejected。
func TestDepartmentsDuplicateCodeAndUnknownParent(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)
	catalogCreateDepartment(t, mux, "nursing", "护理部")

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": "nursing", "name": "护理二部",
	}), http.StatusConflict)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": "ward-a", "name": "病区 A", "parent_code": "ghost",
	}), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": "ward-b", "name": "   ",
	}), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": "ward c", "name": "非法编码",
	}), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": "", "name": "空编码",
	}), http.StatusBadRequest)

	// 校验失败的写入不得留下任何部门。
	listed := catalogRequest(t, mux, http.MethodGet, "/departments", nil)
	meta := catalogObject(t, listed)["meta"].(map[string]any)
	if meta["total"] != float64(1) {
		t.Fatalf("meta.total after rejected writes = %#v, want 1", meta["total"])
	}

	// 合法的上级编码
	ward := catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": "ward-a", "name": "病区 A", "parent_code": "nursing", "sort_order": 10,
	})
	catalogAssertStatus(t, ward, http.StatusCreated)
	wardID := catalogObject(t, ward)["id"].(string)
	// 更新时不得把自己挂到自己下面
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut, "/departments/"+wardID, map[string]any{
		"code": "ward-a", "name": "病区 A", "parent_code": "ward-a",
	}), http.StatusBadRequest)
	// 更新时未知上级 400
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut, "/departments/"+wardID, map[string]any{
		"code": "ward-a", "name": "病区 A", "parent_code": "ghost",
	}), http.StatusBadRequest)

	// 列表按 sort_order, code 升序
	ordered := catalogObject(t, catalogRequest(t, mux, http.MethodGet, "/departments", nil))
	orderedRecords := ordered["records"].([]any)
	if len(orderedRecords) != 2 {
		t.Fatalf("ordered records length = %d", len(orderedRecords))
	}
	if orderedRecords[0].(map[string]any)["code"] != "nursing" ||
		orderedRecords[1].(map[string]any)["code"] != "ward-a" {
		t.Fatalf("department order = %#v", orderedRecords)
	}

	// 分页：page_size 生效且 meta.total 是总数
	pageOne := catalogObject(t, catalogRequest(t, mux, http.MethodGet, "/departments?page=1&page_size=1", nil))
	if len(pageOne["records"].([]any)) != 1 || pageOne["meta"].(map[string]any)["total"] != float64(2) {
		t.Fatalf("paged list = %#v", pageOne)
	}
}
