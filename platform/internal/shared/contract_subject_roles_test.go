package shared_test

// 对照 service-nexus-shared/tests/subject_roles.rs：全量替换用户↔角色、去重排序、
// 列表按 subject_ids 过滤（裸数组）、subject-permissions 并集去重、无效输入 400。

import (
	"net/http"
	"testing"
)

func TestContractSubjectRolesAssignReplaceAndList(t *testing.T) {
	harness := newContractHarness(t)

	createRole := func(roleCode, displayName string, permissionCodes []string) {
		response := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
			"role_code":        roleCode,
			"display_name":     displayName,
			"permission_codes": permissionCodes,
		})
		response.requireStatus(http.StatusCreated)
	}
	createRole("nursing.staff", "护理人员", []string{"nursing:execute", "nursing:read"})
	createRole("pharmacy.manager", "药房管理员", []string{"pharmacy:manage", "nursing:read"})

	// 无效输入：未知角色码 / 非 ULID 主体 / 非法 subject_ids 过滤 / 缺 role_codes。
	unknownRole := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{"nursing.staff", "ghost.role"},
	})
	unknownRole.requireProblem(http.StatusBadRequest, "invalid-request", "")

	badSubject := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/subject-1"), contractAdminSession, map[string]any{
		"role_codes": []string{"nursing.staff"},
	})
	badSubject.requireProblem(http.StatusBadRequest, "invalid-request", "")

	badFilter := harness.request(http.MethodGet, sharedPath("/subject-roles?subject_ids=not-a-ulid"), contractAdminSession, nil)
	badFilter.requireProblem(http.StatusBadRequest, "invalid-request", "")

	missingCodes := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{})
	if missingCodes.status < 400 || missingCodes.status >= 500 {
		t.Fatalf("PUT without role_codes: status = %d, want 4xx; body = %s", missingCodes.status, missingCodes.body)
	}

	// 校验失败的写入不得留下任何分配。
	emptyBefore := harness.request(http.MethodGet, sharedPath("/subject-roles?subject_ids="+contractStaffID), contractAdminSession, nil)
	emptyBefore.requireStatus(http.StatusOK)
	if got := len(emptyBefore.array()); got != 0 {
		t.Fatalf("assignments after failed writes = %d, want 0", got)
	}

	// 全量替换：重复角色码去重、结果按 role_code 升序、带 display_name 与授予者。
	assigned := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{"nursing.staff", "nursing.staff", "pharmacy.manager"},
	})
	assigned.requireStatus(http.StatusOK)
	assignments := assigned.array()
	if len(assignments) != 2 {
		t.Fatalf("assignments length = %d, want 2 (deduplicated)", len(assignments))
	}
	codes := make([]string, 0, len(assignments))
	for _, item := range assignments {
		assignment, ok := item.(map[string]any)
		if !ok {
			t.Fatalf("assignment = %v (%T), want object", item, item)
		}
		codes = append(codes, contractString(t, assignment, "role_code"))
		contractULIDField(t, assignment, "id")
		if got := contractString(t, assignment, "subject_id"); got != contractStaffID {
			t.Fatalf("assignment subject_id = %q", got)
		}
		if got := contractString(t, assignment, "granted_by_subject_id"); got != contractAdminID {
			t.Fatalf("granted_by_subject_id = %q, want %q", got, contractAdminID)
		}
		contractULIDField(t, assignment, "role_id")
		contractTimestampField(t, assignment, "created_at")
		contractTimestampField(t, assignment, "updated_at")
	}
	contractStringsEqual(t, "assignment order", codes, []string{"nursing.staff", "pharmacy.manager"})
	first, ok := assignments[0].(map[string]any)
	if !ok {
		t.Fatalf("first assignment = %v (%T)", assignments[0], assignments[0])
	}
	if got := contractString(t, first, "role_display_name"); got != "护理人员" {
		t.Fatalf("role_display_name = %q", got)
	}

	listed := harness.request(http.MethodGet, sharedPath("/subject-roles?subject_ids="+contractStaffID), contractAdminSession, nil)
	listed.requireStatus(http.StatusOK)
	if got := len(listed.array()); got != 2 {
		t.Fatalf("filtered assignments = %d, want 2", got)
	}

	unrelated := harness.request(http.MethodGet, sharedPath("/subject-roles?subject_ids="+contractOtherID), contractAdminSession, nil)
	unrelated.requireStatus(http.StatusOK)
	if got := len(unrelated.array()); got != 0 {
		t.Fatalf("unrelated assignments = %d, want 0", got)
	}

	narrowed := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{"nursing.staff"},
	})
	narrowed.requireStatus(http.StatusOK)
	if got := len(narrowed.array()); got != 1 {
		t.Fatalf("narrowed assignments = %d, want 1", got)
	}

	cleared := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{},
	})
	cleared.requireStatus(http.StatusOK)
	if got := len(cleared.array()); got != 0 {
		t.Fatalf("cleared assignments = %d, want 0", got)
	}

	afterClear := harness.request(http.MethodGet, sharedPath("/subject-roles?subject_ids="+contractStaffID), contractAdminSession, nil)
	afterClear.requireStatus(http.StatusOK)
	if got := len(afterClear.array()); got != 0 {
		t.Fatalf("assignments after clear = %d, want 0", got)
	}
}

func TestContractSubjectPermissionsUnionAndDeduplication(t *testing.T) {
	harness := newContractHarness(t)

	for _, role := range []struct {
		code        string
		displayName string
		permissions []string
	}{
		{"nursing.staff", "护理人员", []string{"nursing:execute", "nursing:read"}},
		{"pharmacy.manager", "药房管理员", []string{"pharmacy:manage", "nursing:read"}},
	} {
		created := harness.request(http.MethodPost, sharedPath("/roles"), contractAdminSession, map[string]any{
			"role_code":        role.code,
			"display_name":     role.displayName,
			"permission_codes": role.permissions,
		})
		created.requireStatus(http.StatusCreated)
	}

	assigned := harness.request(http.MethodPut, sharedPath("/subject-roles/subjects/"+contractStaffID), contractAdminSession, map[string]any{
		"role_codes": []string{"nursing.staff", "pharmacy.manager"},
	})
	assigned.requireStatus(http.StatusOK)

	// 查自己的权限：角色码 + 权限码并集（去重、保持角色顺序）。
	own := harness.request(http.MethodGet, sharedPath("/subject-permissions?subject_id="+contractStaffID), contractStaffSession, nil)
	own.requireStatus(http.StatusOK)
	payload := own.object()
	if got := contractString(t, payload, "subject_id"); got != contractStaffID {
		t.Fatalf("subject_id = %q", got)
	}
	contractStringsEqual(t, "role_codes", contractStringSlice(t, payload, "role_codes"),
		[]string{"nursing.staff", "pharmacy.manager"})
	contractStringsEqual(t, "permission_codes union", contractStringSlice(t, payload, "permission_codes"),
		[]string{"nursing:execute", "nursing:read", "pharmacy:manage"})
	if got := contractString(t, payload, "source"); got != "nexus" {
		t.Fatalf("source = %q, want nexus (Nexus 契约字段)", got)
	}

	// 无角色主体：空数组（管理员可查他人）。
	empty := harness.request(http.MethodGet, sharedPath("/subject-permissions?subject_id="+contractOtherID), contractAdminSession, nil)
	empty.requireStatus(http.StatusOK)
	emptyPayload := empty.object()
	contractStringsEqual(t, "empty role_codes", contractStringSlice(t, emptyPayload, "role_codes"), nil)
	contractStringsEqual(t, "empty permission_codes", contractStringSlice(t, emptyPayload, "permission_codes"), nil)

	invalid := harness.request(http.MethodGet, sharedPath("/subject-permissions?subject_id=not-a-ulid"), contractAdminSession, nil)
	invalid.requireProblem(http.StatusBadRequest, "invalid-request", "")
}
