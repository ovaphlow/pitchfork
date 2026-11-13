package shared_test

// 对照 service-nexus-shared/tests/roles.rs：角色目录 CRUD、列表为裸数组且按
// role_code 升序、permission_codes 服务端去重并校验格式、role_code 创建后不可改、
// 已分配角色不可删除（409）。

import (
	"net/http"
	"testing"
)

func TestContractRolesDirectoryCRUDFlow(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":        "pharmacy.manager",
		"display_name":     "药房管理员",
		"description":      "负责药房日常管理",
		"permission_codes": []string{"pharmacy:manage", "pharmacy:execute", "pharmacy:manage"},
	})
	created.requireStatus(http.StatusCreated)
	role := created.object()
	pharmacyID := contractULIDField(t, role, "id")
	if got := contractString(t, role, "role_code"); got != "pharmacy.manager" {
		t.Fatalf("role_code = %q", got)
	}
	contractStringsEqual(t, "permission_codes (deduplicated)",
		contractStringSlice(t, role, "permission_codes"),
		[]string{"pharmacy:manage", "pharmacy:execute"})
	contractTimestampField(t, role, "created_at")
	contractTimestampField(t, role, "updated_at")

	nursing := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":        "nursing.staff",
		"display_name":     "护理人员",
		"permission_codes": []string{"nursing:execute"},
	})
	nursing.requireStatus(http.StatusCreated)

	// 列表为裸数组，按 role_code 升序。
	listed := harness.request(http.MethodGet, sharedPath("/roles?page=1&page_size=100"), contractAdminSession, nil)
	listed.requireStatus(http.StatusOK)
	records := listed.array()
	if len(records) != 2 {
		t.Fatalf("roles list length = %d, want 2", len(records))
	}
	codes := make([]string, 0, len(records))
	for _, record := range records {
		object, ok := record.(map[string]any)
		if !ok {
			t.Fatalf("role record = %v (%T), want object", record, record)
		}
		codes = append(codes, contractString(t, object, "role_code"))
	}
	contractStringsEqual(t, "roles order", codes, []string{"nursing.staff", "pharmacy.manager"})

	fetched := harness.request(http.MethodGet, sharedPath("/roles/"+pharmacyID), contractAdminSession, nil)
	fetched.requireStatus(http.StatusOK)
	if got := contractString(t, fetched.object(), "display_name"); got != "药房管理员" {
		t.Fatalf("display_name = %q", got)
	}

	// role_code 唯一 -> 409。
	duplicate := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":    "pharmacy.manager",
		"display_name": "重复角色",
	})
	duplicate.requireProblem(http.StatusConflict, "conflict", "")

	// role_code 只允许小写字母/数字/点。
	invalidCode := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":    "Pharmacy.Manager",
		"display_name": "非法编码",
	})
	invalidCode.requireProblem(http.StatusBadRequest, "invalid-request", "")

	blankName := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":    "nursing.manager",
		"display_name": "   ",
	})
	blankName.requireProblem(http.StatusBadRequest, "invalid-request", "")

	// 权限码格式：<domain>:<action>。
	for index, rejected := range []string{"totally.made.up:code", "中文权限码", "12345"} {
		response := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
			"role_code":        "junk.role" + string(rune('0'+index)),
			"display_name":     "垃圾权限码",
			"permission_codes": []string{rejected},
		})
		response.requireProblem(http.StatusBadRequest, "invalid-request", "")
	}

	updated := harness.request(http.MethodPut, sharedPath("/roles/"+pharmacyID), contractAdminSession, map[string]any{
		"role_code":        "pharmacy.manager",
		"display_name":     "药房主管",
		"description":      "负责药房全面管理",
		"permission_codes": []string{"pharmacy:manage"},
	})
	updated.requireStatus(http.StatusOK)
	updatedRole := updated.object()
	if got := contractString(t, updatedRole, "display_name"); got != "药房主管" {
		t.Fatalf("updated display_name = %q", got)
	}
	contractStringsEqual(t, "updated permission_codes",
		contractStringSlice(t, updatedRole, "permission_codes"),
		[]string{"pharmacy:manage"})

	// role_code 创建后不可改。
	codeChange := harness.request(http.MethodPut, sharedPath("/roles/"+pharmacyID), contractAdminSession, map[string]any{
		"role_code":    "pharmacy.chief",
		"display_name": "药房主管",
	})
	codeChange.requireProblem(http.StatusBadRequest, "invalid-request", "")

	missing := harness.request(http.MethodGet, sharedPath("/roles/not-a-real-id"), contractAdminSession, nil)
	missing.requireProblem(http.StatusNotFound, "not-found", "")

	deleted := harness.request(http.MethodDelete, sharedPath("/roles/"+pharmacyID), contractAdminSession, nil)
	deleted.requireStatus(http.StatusNoContent)

	gone := harness.request(http.MethodGet, sharedPath("/roles/"+pharmacyID), contractAdminSession, nil)
	gone.requireProblem(http.StatusNotFound, "not-found", "")

	deleteMissing := harness.request(http.MethodDelete, sharedPath("/roles/"+pharmacyID), contractAdminSession, nil)
	deleteMissing.requireProblem(http.StatusNotFound, "not-found", "")
}

func TestContractAssignedRoleCannotBeDeleted(t *testing.T) {
	harness := newContractHarness(t)

	created := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":    "nursing.staff",
		"display_name": "护理人员",
	})
	created.requireStatus(http.StatusCreated)
	roleID := contractULIDField(t, created.object(), "id")

	assigned := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{"nursing.staff"},
	})
	assigned.requireStatus(http.StatusOK)

	blocked := harness.request(http.MethodDelete, sharedPath("/roles/"+roleID), contractAdminSession, nil)
	blocked.requireProblem(http.StatusConflict, "conflict", "")

	stillThere := harness.request(http.MethodGet, sharedPath("/roles/"+roleID), contractAdminSession, nil)
	stillThere.requireStatus(http.StatusOK)

	cleared := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{},
	})
	cleared.requireStatus(http.StatusOK)

	deleted := harness.request(http.MethodDelete, sharedPath("/roles/"+roleID), contractAdminSession, nil)
	deleted.requireStatus(http.StatusNoContent)
}
