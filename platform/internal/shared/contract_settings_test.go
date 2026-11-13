package shared_test

// 对照 service-nexus-shared/src/settings/mod.rs（该模块无 tests/*.rs，契约取自
// Rust 源码与计划 055 §2：列表为裸数组、category+code 唯一、payload 必须是对象）。

import (
	"net/http"
	"testing"
)

func TestContractSettingsCRUD(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/settings"), contractAdminSession, map[string]any{
		"category": "department",
		"code":     "care",
		"payload":  map[string]any{"name": "Care"},
	})
	created.requireStatus(http.StatusCreated)
	setting := created.object()
	settingID := contractULIDField(t, setting, "id")
	if got := contractString(t, setting, "category"); got != "department" {
		t.Fatalf("category = %q", got)
	}
	if got := contractString(t, setting, "code"); got != "care" {
		t.Fatalf("code = %q", got)
	}
	if got := contractString(t, setting, "root_code"); got != "" {
		t.Fatalf("root_code = %q, want empty default", got)
	}
	if got := contractString(t, setting, "parent_code"); got != "" {
		t.Fatalf("parent_code = %q, want empty default", got)
	}
	if payload := contractObjectField(t, setting, "payload"); contractString(t, payload, "name") != "Care" {
		t.Fatalf("payload = %v", payload)
	}
	contractTimestampField(t, setting, "created_at")
	contractTimestampField(t, setting, "updated_at")

	// 列表是裸数组（不是 records+meta 信封）。
	listed := harness.request(http.MethodGet, sharedPath("/settings"), contractAdminSession, nil)
	listed.requireStatus(http.StatusOK)
	if got := len(listed.array()); got != 1 {
		t.Fatalf("settings list length = %d, want 1", got)
	}

	fetched := harness.request(http.MethodGet, sharedPath("/settings/"+settingID), contractAdminSession, nil)
	fetched.requireStatus(http.StatusOK)
	if got := contractString(t, fetched.object(), "id"); got != settingID {
		t.Fatalf("fetched id = %q, want %q", got, settingID)
	}

	updated := harness.request(http.MethodPut, sharedPath("/settings/"+settingID), contractAdminSession, map[string]any{
		"category":    "department",
		"code":        "care",
		"root_code":   "clinical",
		"parent_code": "care",
		"payload":     map[string]any{"name": "Care updated"},
	})
	updated.requireStatus(http.StatusOK)
	updatedSetting := updated.object()
	if got := contractString(t, updatedSetting, "root_code"); got != "clinical" {
		t.Fatalf("updated root_code = %q", got)
	}
	if got := contractString(t, updatedSetting, "parent_code"); got != "care" {
		t.Fatalf("updated parent_code = %q", got)
	}
	if payload := contractObjectField(t, updatedSetting, "payload"); contractString(t, payload, "name") != "Care updated" {
		t.Fatalf("updated payload = %v", payload)
	}

	// 唯一键（category, code）冲突 -> 409。
	duplicate := harness.request(http.MethodPost, sharedPath("/settings"), contractAdminSession, map[string]any{
		"category": "department",
		"code":     "care",
		"payload":  map[string]any{},
	})
	duplicate.requireProblem(http.StatusConflict, "conflict", "")

	// payload 必须是 JSON 对象。
	invalidPayload := harness.request(http.MethodPost, sharedPath("/settings"), contractAdminSession, map[string]any{
		"category": "department",
		"code":     "nursing",
		"payload":  "not-an-object",
	})
	invalidPayload.requireProblem(http.StatusBadRequest, "invalid-request", "")

	// category/code 必填。
	missingCategory := harness.request(http.MethodPost, sharedPath("/settings"), contractAdminSession, map[string]any{
		"code":    "nursing",
		"payload": map[string]any{},
	})
	missingCategory.requireProblem(http.StatusBadRequest, "invalid-request", "")

	missing := harness.request(http.MethodGet, sharedPath("/settings/not-a-real-id"), contractAdminSession, nil)
	missing.requireProblem(http.StatusNotFound, "not-found", "")

	deleted := harness.request(http.MethodDelete, sharedPath("/settings/"+settingID), contractAdminSession, nil)
	deleted.requireStatus(http.StatusNoContent)

	gone := harness.request(http.MethodGet, sharedPath("/settings/"+settingID), contractAdminSession, nil)
	gone.requireProblem(http.StatusNotFound, "not-found", "")

	deleteMissing := harness.request(http.MethodDelete, sharedPath("/settings/"+settingID), contractAdminSession, nil)
	deleteMissing.requireProblem(http.StatusNotFound, "not-found", "")

	// 尾斜杠兼容：与 Nexus 的 axum nest 行为一致。
	initial := harness.request(http.MethodGet, sharedPath("/settings/"), contractAdminSession, nil)
	initial.requireStatus(http.StatusOK)
	if got := len(initial.array()); got != 0 {
		t.Fatalf("settings list after delete = %d, want 0", got)
	}

	trailingCreate := harness.request(http.MethodPost, sharedPath("/settings/"), contractAdminSession, map[string]any{
		"category": "trailing",
		"code":     "slash",
		"payload":  map[string]any{"ok": true},
	})
	trailingCreate.requireStatus(http.StatusCreated)
}
