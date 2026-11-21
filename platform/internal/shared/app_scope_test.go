package shared_test

// app_scope_test 验证 000021 引入的 X-App-Code 隔离：同一张共享表里，
// 一个产品看不到、改不到、也分配不了另一个产品的数据。

import (
	"encoding/json"
	"net/http"
	"testing"

)

func appScopeJSON(t *testing.T, value any) []byte {
	t.Helper()
	encoded, err := json.Marshal(value)
	if err != nil {
		t.Fatalf("encode request body: %v", err)
	}
	return encoded
}

func TestSharedAppScopeIsolation(t *testing.T) {
	harness := newContractHarness(t)

	// 缺少 app 声明时拒绝，且不落任何数据。
	missing := harness.requestRawWithApp(http.MethodGet, sharedPath("/settings"), contractStaffSession, "", nil, "")
	missing.requireProblem(http.StatusBadRequest, "invalid-request", "")

	createSetting := func(app, code string) *contractResponse {
		return harness.requestRawWithApp(http.MethodPost, sharedPath("/settings"), contractAdminSession, app,
			appScopeJSON(t, map[string]any{
				"category": "department",
				"code":     code,
				"payload":  map[string]any{"name": code},
			}), "application/json")
	}
	agoraSetting := createSetting("agora", "care")
	agoraSetting.requireStatus(http.StatusCreated)
	agoraSettingID := contractULIDField(t, agoraSetting.object(), "id")
	// 相同的 category+code 在不同产品下必须能共存。
	createSetting("aceso", "care").requireStatus(http.StatusCreated)

	settingsOf := func(app string) []any {
		response := harness.requestRawWithApp(http.MethodGet, sharedPath("/settings"), contractStaffSession, app, nil, "")
		response.requireStatus(http.StatusOK)
		return response.array()
	}
	if got := len(settingsOf("agora")); got != 1 {
		t.Fatalf("agora settings = %d, want 1", got)
	}
	if got := len(settingsOf("aceso")); got != 1 {
		t.Fatalf("aceso settings = %d, want 1", got)
	}
	// 未写入过任何数据的 app code（无论是否在 PLATFORMD_APP_CODES 里）都必须读到空集。
	// 2026-11-21 之前这里用的是 trainova；该产品已删除，改用一个从未写入的 code。
	if got := len(settingsOf("unwritten-app")); got != 0 {
		t.Fatalf("unwritten-app settings = %d, want 0", got)
	}
	harness.requestRawWithApp(http.MethodGet, sharedPath("/settings/"+agoraSettingID), contractStaffSession, "aceso", nil, "").
		requireProblem(http.StatusNotFound, "not-found", "")

	// 文件实体同样按产品隔离。
	uploaded := harness.uploadFileAsApp(contractStaffSession, "agora", "file", "licence.pdf", "application/pdf", []byte("licence"))
	uploaded.requireStatus(http.StatusCreated)
	fileID := contractULIDField(t, uploaded.object(), "id")
	harness.requestRawWithApp(http.MethodGet, sharedPath("/files/"+fileID), contractStaffSession, "aceso", nil, "").
		requireProblem(http.StatusNotFound, "not-found", "")
	harness.requestRawWithApp(http.MethodGet, sharedPath("/files/"+fileID+"/content"), contractStaffSession, "aceso", nil, "").
		requireProblem(http.StatusNotFound, "not-found", "")
	harness.requestRawWithApp(http.MethodGet, sharedPath("/files/"+fileID), contractStaffSession, "agora", nil, "").
		requireStatus(http.StatusOK)
	if got := len(harness.requestRawWithApp(http.MethodGet, sharedPath("/files"), contractStaffSession, "aceso", nil, "").array()); got != 0 {
		t.Fatalf("aceso files = %d, want 0", got)
	}

	// 角色目录与角色分配按产品隔离。
	harness.requestRawWithApp(http.MethodPost, sharedPath("/roles"), contractAdminSession, "agora",
		appScopeJSON(t, map[string]any{
			"role_code":        "agora.user",
			"display_name":     "Agora user",
			"permission_codes": []string{"agora:read"},
		}), "application/json").requireStatus(http.StatusCreated)
	if got := len(harness.requestRawWithApp(http.MethodGet, sharedPath("/roles"), contractStaffSession, "aceso", nil, "").array()); got != 0 {
		t.Fatalf("aceso roles = %d, want 0", got)
	}
	harness.requestRawWithApp(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, "aceso",
		appScopeJSON(t, map[string]any{"role_codes": []string{"agora.user"}}), "application/json").
		requireProblem(http.StatusBadRequest, "invalid-request", "")

	harness.requestRawWithApp(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, "agora",
		appScopeJSON(t, map[string]any{"role_codes": []string{"agora.user"}}), "application/json").
		requireStatus(http.StatusOK)

	// 另一个产品也分配角色；随后重设 Agora 角色不得清掉 Aceso 的分配。
	harness.requestRawWithApp(http.MethodPost, sharedPath("/roles"), contractAdminSession, "aceso",
		appScopeJSON(t, map[string]any{
			"role_code":        "aceso.user",
			"display_name":     "Aceso user",
			"permission_codes": []string{"aceso:read"},
		}), "application/json").requireStatus(http.StatusCreated)
	harness.requestRawWithApp(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, "aceso",
		appScopeJSON(t, map[string]any{"role_codes": []string{"aceso.user"}}), "application/json").
		requireStatus(http.StatusOK)
	harness.requestRawWithApp(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, "agora",
		appScopeJSON(t, map[string]any{"role_codes": []string{"agora.user"}}), "application/json").
		requireStatus(http.StatusOK)

	permissionsOf := func(app string) map[string]any {
		response := harness.requestRawWithApp(http.MethodGet,
			sharedPath("/subject-permissions?subject_id="+contractStaffID), contractStaffSession, app, nil, "")
		response.requireStatus(http.StatusOK)
		return response.object()
	}
	agoraPermissions := permissionsOf("agora")
	if got := contractStringSlice(t, agoraPermissions, "role_codes"); len(got) != 1 || got[0] != "agora.user" {
		t.Fatalf("agora role_codes = %v", got)
	}
	if got := contractStringSlice(t, agoraPermissions, "permission_codes"); len(got) != 1 || got[0] != "agora:read" {
		t.Fatalf("agora permission_codes = %v", got)
	}
	acesoPermissions := permissionsOf("aceso")
	if got := contractStringSlice(t, acesoPermissions, "role_codes"); len(got) != 1 || got[0] != "aceso.user" {
		t.Fatalf("aceso role_codes = %v, want [aceso.user]", got)
	}
}
