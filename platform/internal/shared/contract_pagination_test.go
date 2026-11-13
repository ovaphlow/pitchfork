package shared_test

// 分页契约（Rust PageQuery：page 默认 1、page_size 默认 20、上限 100、非法值 400）、
// 各模块信封形状（settings/messages/interactions/files/roles/subject-roles 为裸数组；
// departments/subject-departments 为 {records, meta:{total}}）、尾斜杠兼容。

import (
	"fmt"
	"net/http"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

func TestContractPaginationDefaultsAndLimits(t *testing.T) {
	harness := newContractHarness(t)

	// 21 个角色：默认 page_size=20 应只返回 20 条。
	for index := 0; index < 21; index++ {
		created := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
			"role_code":    fmt.Sprintf("role.%02d", index),
			"display_name": fmt.Sprintf("角色 %02d", index),
		})
		created.requireStatus(http.StatusCreated)
	}

	defaultPage := harness.request(http.MethodGet, sharedPath("/roles"), contractAdminSession, nil)
	defaultPage.requireStatus(http.StatusOK)
	if got := len(defaultPage.array()); got != 20 {
		t.Fatalf("roles default page length = %d, want default page_size 20", got)
	}

	maximumPage := harness.request(http.MethodGet, sharedPath("/roles?page_size=100"), contractAdminSession, nil)
	maximumPage.requireStatus(http.StatusOK)
	if got := len(maximumPage.array()); got != 21 {
		t.Fatalf("roles page_size=100 length = %d, want 21", got)
	}

	secondPage := harness.request(http.MethodGet, sharedPath("/roles?page=2&page_size=10"), contractAdminSession, nil)
	secondPage.requireStatus(http.StatusOK)
	if got := len(secondPage.array()); got != 10 {
		t.Fatalf("roles page=2&page_size=10 length = %d, want 10 (21 rows: 10+10+1)", got)
	}

	// 非法分页 -> 400（page 必须 >=1，page_size 必须 1..100 且为整数）。
	for _, query := range []string{"page=0", "page_size=0", "page_size=101"} {
		invalid := harness.request(http.MethodGet, sharedPath("/roles?"+query), contractAdminSession, nil)
		invalid.requireProblem(http.StatusBadRequest, "invalid-request", "")
	}
	for _, query := range []string{"page=abc", "page_size=abc"} {
		invalid := harness.request(http.MethodGet, sharedPath("/roles?"+query), contractAdminSession, nil)
		if invalid.status != http.StatusBadRequest {
			t.Fatalf("GET /roles?%s: status = %d, want 400; body = %s", query, invalid.status, invalid.body)
		}
	}

	// 所有分页模块对 page_size=101 都应 400（而不是静默截断）。
	for _, resource := range []string{
		"/settings", "/messages", "/files", "/interactions",
		"/roles", "/subject-roles", "/departments", "/subject-departments",
	} {
		invalid := harness.request(http.MethodGet, sharedPath(resource+"?page_size=101"), contractAdminSession, nil)
		if invalid.status != http.StatusBadRequest {
			t.Fatalf("GET %s?page_size=101: status = %d, want 400; body = %s", resource, invalid.status, invalid.body)
		}
	}
}

func TestContractPaginationAcrossShapes(t *testing.T) {
	harness := newContractHarness(t)

	// settings：裸数组 + 稳定排序（category, code）。
	for _, code := range []string{"aaa", "bbb", "ccc"} {
		created := harness.request(http.MethodPost, sharedPath("/settings"), contractAdminSession, map[string]any{
			"category": "contract",
			"code":     code,
			"payload":  map[string]any{"code": code},
		})
		created.requireStatus(http.StatusCreated)
	}
	settingsPage := harness.request(http.MethodGet, sharedPath("/settings?page=1&page_size=2"), contractAdminSession, nil)
	settingsPage.requireStatus(http.StatusOK)
	settingsRecords := settingsPage.array()
	if len(settingsRecords) != 2 {
		t.Fatalf("settings page length = %d, want 2", len(settingsRecords))
	}
	firstSetting, ok := settingsRecords[0].(map[string]any)
	if !ok {
		t.Fatalf("setting record = %v (%T)", settingsRecords[0], settingsRecords[0])
	}
	if got := contractString(t, firstSetting, "code"); got != "aaa" {
		t.Fatalf("first setting code = %q, want aaa (ordered by category, code)", got)
	}
	settingsSecondPage := harness.request(http.MethodGet, sharedPath("/settings?page=2&page_size=2"), contractAdminSession, nil)
	settingsSecondPage.requireStatus(http.StatusOK)
	if got := len(settingsSecondPage.array()); got != 1 {
		t.Fatalf("settings page 2 length = %d, want 1", got)
	}

	// departments：records+meta 信封，meta.total 是过滤后的总数而不是当前页条数。
	for _, code := range []string{"dept.a", "dept.b", "dept.c"} {
		created := harness.request(http.MethodPost, sharedPath("/departments"), contractAdminSession, map[string]any{
			"code": code,
			"name": code,
		})
		created.requireStatus(http.StatusCreated)
	}
	departmentPage := harness.request(http.MethodGet, sharedPath("/departments?page=1&page_size=2"), contractAdminSession, nil)
	departmentPage.requireStatus(http.StatusOK)
	departmentBody := departmentPage.object()
	if got := contractNumber(t, contractObjectField(t, departmentBody, "meta"), "total"); got != 3 {
		t.Fatalf("departments meta.total = %v, want 3", got)
	}
	if got := len(contractArrayField(t, departmentBody, "records")); got != 2 {
		t.Fatalf("departments records length = %d, want 2", got)
	}
	departmentSecondPage := harness.request(http.MethodGet, sharedPath("/departments?page=2&page_size=2"), contractAdminSession, nil)
	departmentSecondPage.requireStatus(http.StatusOK)
	departmentSecondBody := departmentSecondPage.object()
	if got := contractNumber(t, contractObjectField(t, departmentSecondBody, "meta"), "total"); got != 3 {
		t.Fatalf("departments page2 meta.total = %v, want 3", got)
	}
	if got := len(contractArrayField(t, departmentSecondBody, "records")); got != 1 {
		t.Fatalf("departments page2 records length = %d, want 1", got)
	}
}

func TestContractTrailingSlashCompatibility(t *testing.T) {
	harness := newContractHarness(t)

	for _, resource := range []string{
		"/settings", "/messages", "/files", "/interactions",
		"/roles", "/subject-roles", "/departments", "/subject-departments",
	} {
		response := harness.request(http.MethodGet, sharedPath(resource+"/"), contractAdminSession, nil)
		if response.status != http.StatusOK {
			t.Fatalf("GET %s/: status = %d, want 200 (axum nest 尾斜杠兼容); body = %s",
				resource, response.status, response.body)
		}
	}

	permissions := harness.request(
		http.MethodGet,
		sharedPath("/subject-permissions/?subject_id="+contractStaffID),
		contractStaffSession,
		nil,
	)
	permissions.requireStatus(http.StatusOK)
	if got := contractString(t, permissions.object(), "subject_id"); got != contractStaffID {
		t.Fatalf("subject-permissions trailing slash subject_id = %q", got)
	}

	// 写路由同样兼容尾斜杠（axum nest 行为）。
	trailingCreate := harness.request(http.MethodPost, sharedPath("/settings/"), contractAdminSession, map[string]any{
		"category": "trailing",
		"code":     "create",
		"payload":  map[string]any{},
	})
	trailingCreate.requireStatus(http.StatusCreated)

	// 前缀常量必须与 Nexus API_PREFIX 一致。
	if shared.Prefix != "/crate-api/v1" {
		t.Fatalf("shared.Prefix = %q, want /crate-api/v1", shared.Prefix)
	}
}
