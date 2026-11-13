package shared_test

// 对照 service-nexus-shared/tests/authentication.rs 与 tests/authorization.rs：
// 401 全覆盖、043 写门禁矩阵（403 + required_permission）、读路由开放、
// subject-permissions 越权读收紧、引导者与委派路径。

import (
	"net/http"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

func TestContractAuthenticationRequiresSessionForEveryResource(t *testing.T) {
	harness := newContractHarness(t)

	resources := []string{
		"/settings",
		"/messages",
		"/files",
		"/interactions",
		"/roles",
		"/subject-roles",
		"/subject-permissions?subject_id=" + contractStaffID,
		"/departments",
		"/subject-departments",
	}
	for _, resource := range resources {
		response := harness.request(http.MethodGet, sharedPath(resource), "", nil)
		response.requireProblem(http.StatusUnauthorized, "not-authenticated", "")
	}

	// 写路由同样要求会话（先认证、再授权）。
	writes := []struct {
		method string
		path   string
		body   any
	}{
		{http.MethodPost, "/roles", map[string]any{"role_code": "x.role", "display_name": "X"}},
		{http.MethodPost, "/departments", map[string]any{"code": "x", "name": "X"}},
		{http.MethodPut, "/subject-roles/subjects/" + contractStaffID, map[string]any{"role_codes": []string{}}},
		{http.MethodPut, "/subject-departments/subjects/" + contractStaffID, map[string]any{"department_id": nil}},
	}
	for _, write := range writes {
		response := harness.request(write.method, sharedPath(write.path), "", write.body)
		response.requireProblem(http.StatusUnauthorized, "not-authenticated", "")
	}
}

// TestContractBusinessAccountCannotEscalateOrWriteBaseData 对照
// authorization.rs::business_accounts_cannot_escalate_or_write_base_data。
func TestContractBusinessAccountCannotEscalateOrWriteBaseData(t *testing.T) {
	harness := newContractHarness(t)
	departmentID := harness.seedDepartment(t, "nursing", "护理部")

	// 提权链第一半：业务账号不能建带权限码的角色。
	escalateRole := harness.request(http.MethodPost, sharedPath("/roles"), contractStaffSession, map[string]any{
		"role_code":        "self.escalate",
		"display_name":     "自授角色",
		"permission_codes": []string{"nursing:execute"},
	})
	escalateRole.requireProblem(http.StatusForbidden, "permission-required", shared.AccessManagePermission)

	// 提权链第二半：不能把角色分给自己。先由管理员建一个真实角色。
	created := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":        "nursing.staff",
		"display_name":     "护理人员",
		"permission_codes": []string{"nursing:execute"},
	})
	created.requireStatus(http.StatusCreated)

	escalateAssignment := harness.request(
		http.MethodPut,
		sharedPath("/subject-roles/subjects/"+contractStaffID),
		contractStaffSession,
		map[string]any{"role_codes": []string{"nursing.staff"}},
	)
	escalateAssignment.requireProblem(http.StatusForbidden, "permission-required", shared.AccessManagePermission)

	// 403 不得留下任何副作用（回读断言，而不是只看状态码）。
	ownPermissions := harness.request(
		http.MethodGet,
		sharedPath("/subject-permissions?subject_id="+contractStaffID),
		contractStaffSession,
		nil,
	)
	ownPermissions.requireStatus(http.StatusOK)
	contractStringsEqual(t, "403 must not assign roles", contractStringSlice(t, ownPermissions.object(), "role_codes"), nil)

	// 基础数据写：部门目录需要 base_data:manage。
	createDepartment := harness.request(http.MethodPost, sharedPath("/departments"), contractStaffSession, map[string]any{
		"code": "qa-authz",
		"name": "越权部门",
	})
	createDepartment.requireProblem(http.StatusForbidden, "permission-required", shared.BaseDataManagePermission)

	updateDepartment := harness.request(http.MethodPut, sharedPath("/departments/"+departmentID), contractStaffSession, map[string]any{
		"code": "nursing",
		"name": "改名越权",
	})
	updateDepartment.requireProblem(http.StatusForbidden, "permission-required", shared.BaseDataManagePermission)

	deleteDepartment := harness.request(http.MethodDelete, sharedPath("/departments/"+departmentID), contractStaffSession, nil)
	deleteDepartment.requireProblem(http.StatusForbidden, "permission-required", shared.BaseDataManagePermission)

	// 越权读收紧（043 D5）：普通主体不能读别人的权限。
	readOther := harness.request(
		http.MethodGet,
		sharedPath("/subject-permissions?subject_id="+contractAdminID),
		contractStaffSession,
		nil,
	)
	readOther.requireProblem(http.StatusForbidden, "permission-required", shared.AccessManagePermission)

	// 读路由保持开放（043 D7）。
	for _, resource := range []string{"/roles", "/departments", "/subject-roles", "/subject-departments"} {
		listed := harness.request(http.MethodGet, sharedPath(resource), contractStaffSession, nil)
		listed.requireStatus(http.StatusOK)
	}

	// 被拒的改名不得生效。
	departments := harness.request(http.MethodGet, sharedPath("/departments"), contractStaffSession, nil)
	departments.requireStatus(http.StatusOK)
	body := departments.object()
	records := contractArrayField(t, body, "records")
	if len(records) != 1 {
		t.Fatalf("departments records = %v, want 1", records)
	}
	record, ok := records[0].(map[string]any)
	if !ok {
		t.Fatalf("department record = %v (%T), want object", records[0], records[0])
	}
	if got := contractString(t, record, "name"); got != "护理部" {
		t.Fatalf("department name = %q, want 护理部", got)
	}
}

// TestContractPlatformAdministratorBootstrapsAndDelegationStaysWithinItsCode 对照
// authorization.rs::platform_administrator_bootstraps_and_delegation_stays_within_its_code。
func TestContractPlatformAdministratorBootstrapsAndDelegationStaysWithinItsCode(t *testing.T) {
	harness := newContractHarness(t)

	// 引导者：库里没有任何 access:manage 持有者时，平台管理员仍可建角色。
	managerRole := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":        "access.manager",
		"display_name":     "账号管理员",
		"permission_codes": []string{shared.AccessManagePermission},
	})
	managerRole.requireStatus(http.StatusCreated)

	delegated := harness.request(
		http.MethodPut,
		sharedPath("/subject-roles/subjects/"+contractManagerID),
		contractAdminSession,
		map[string]any{"role_codes": []string{"access.manager"}},
	)
	delegated.requireStatus(http.StatusOK)

	// 委派生效：manager 能管角色与归属。
	createdByManager := harness.request(http.MethodPost, sharedPath("/roles"), contractManagerSession, map[string]any{
		"role_code":        "pharmacy.staff",
		"display_name":     "药房药师",
		"permission_codes": []string{"pharmacy:dispense"},
	})
	createdByManager.requireStatus(http.StatusCreated)

	assignByManager := harness.request(
		http.MethodPut,
		sharedPath("/subject-roles/subjects/"+contractStaffID),
		contractManagerSession,
		map[string]any{"role_codes": []string{"pharmacy.staff"}},
	)
	assignByManager.requireStatus(http.StatusOK)

	setDepartmentByManager := harness.request(
		http.MethodPut,
		sharedPath("/subject-departments/subjects/"+contractStaffID),
		contractManagerSession,
		map[string]any{"department_id": nil},
	)
	setDepartmentByManager.requireStatus(http.StatusOK)

	// 但 manager 仍改不了部门目录（两个权限码相互独立）。
	departmentByManager := harness.request(http.MethodPost, sharedPath("/departments"), contractManagerSession, map[string]any{
		"code": "manager-dept",
		"name": "越权目录",
	})
	departmentByManager.requireProblem(http.StatusForbidden, "permission-required", shared.BaseDataManagePermission)

	// 反向：拥有 base_data:manage 也不得动角色。
	baseDataRole := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
		"role_code":        "org.admin",
		"display_name":     "组织管理员",
		"permission_codes": []string{shared.BaseDataManagePermission},
	})
	baseDataRole.requireStatus(http.StatusCreated)

	reassigned := harness.request(
		http.MethodPut,
		sharedPath("/subject-roles/subjects/"+contractManagerID),
		contractAdminSession,
		map[string]any{"role_codes": []string{"org.admin"}},
	)
	reassigned.requireStatus(http.StatusOK)

	departmentByOrgAdmin := harness.request(http.MethodPost, sharedPath("/departments"), contractManagerSession, map[string]any{
		"code": "nursing",
		"name": "护理部",
	})
	departmentByOrgAdmin.requireStatus(http.StatusCreated)

	roleByOrgAdmin := harness.request(http.MethodPost, sharedPath("/roles"), contractManagerSession, map[string]any{
		"role_code":    "org.other",
		"display_name": "越权角色",
	})
	roleByOrgAdmin.requireProblem(http.StatusForbidden, "permission-required", shared.AccessManagePermission)
}
