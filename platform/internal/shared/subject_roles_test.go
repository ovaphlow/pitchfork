package shared_test

import (
	"net/http"
	"testing"
)

const catalogAssignedSubjectID = "01BX5ZZKBKACTAV9WEVGEMMVRZ"

// TestSubjectRolesAssignReplaceAndList 对照 Nexus tests/subject_roles.rs::assign_replace_and_list_subject_roles。
func TestSubjectRolesAssignReplaceAndList(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)
	catalogCreateRole(t, mux, "nursing.staff", "护理人员", nil)
	catalogCreateRole(t, mux, "pharmacy.manager", "药房管理员", nil)

	recorder := catalogRequest(t, mux, http.MethodPut, "/subject-roles/subjects/"+catalogAssignedSubjectID, map[string]any{
		"role_codes": []string{"nursing.staff", "nursing.staff", "pharmacy.manager"},
	})
	catalogAssertStatus(t, recorder, http.StatusOK)
	assignments := catalogArray(t, recorder)
	if len(assignments) != 2 {
		t.Fatalf("assignments length = %d, want 2 (deduplicated)", len(assignments))
	}
	codes := make([]string, 0, len(assignments))
	for _, item := range assignments {
		codes = append(codes, item.(map[string]any)["role_code"].(string))
	}
	if codes[0] != "nursing.staff" || codes[1] != "pharmacy.manager" {
		t.Fatalf("assignment order = %v, want sorted by role_code", codes)
	}
	first := assignments[0].(map[string]any)
	if first["subject_id"] != catalogAssignedSubjectID {
		t.Fatalf("subject_id = %#v", first["subject_id"])
	}
	if first["granted_by_subject_id"] != catalogAdminSubjectID {
		t.Fatalf("granted_by_subject_id = %#v, want session subject", first["granted_by_subject_id"])
	}
	if first["role_display_name"] != "护理人员" {
		t.Fatalf("role_display_name = %#v", first["role_display_name"])
	}
	if first["role_id"] == "" || first["id"] == "" || first["created_at"] == "" {
		t.Fatalf("assignment fields missing: %#v", first)
	}

	listed := catalogRequest(t, mux, http.MethodGet, "/subject-roles?subject_ids="+catalogAssignedSubjectID, nil)
	catalogAssertStatus(t, listed, http.StatusOK)
	if len(catalogArray(t, listed)) != 2 {
		t.Fatalf("filtered list length mismatch")
	}

	unrelated := catalogRequest(t, mux, http.MethodGet, "/subject-roles?subject_ids=01M3ZR3CENV2DGYY2RS1BPJG2D", nil)
	catalogAssertStatus(t, unrelated, http.StatusOK)
	if len(catalogArray(t, unrelated)) != 0 {
		t.Fatalf("unrelated subject must have no assignments")
	}

	narrowed := catalogRequest(t, mux, http.MethodPut, "/subject-roles/subjects/"+catalogAssignedSubjectID, map[string]any{
		"role_codes": []string{"nursing.staff"},
	})
	catalogAssertStatus(t, narrowed, http.StatusOK)
	if len(catalogArray(t, narrowed)) != 1 {
		t.Fatalf("narrowed assignment count mismatch")
	}

	cleared := catalogRequest(t, mux, http.MethodPut, "/subject-roles/subjects/"+catalogAssignedSubjectID, map[string]any{
		"role_codes": []string{},
	})
	catalogAssertStatus(t, cleared, http.StatusOK)
	if len(catalogArray(t, cleared)) != 0 {
		t.Fatalf("cleared assignment count mismatch")
	}
	afterClear := catalogRequest(t, mux, http.MethodGet, "/subject-roles?subject_ids="+catalogAssignedSubjectID, nil)
	if len(catalogArray(t, afterClear)) != 0 {
		t.Fatalf("assignments survived clear")
	}
}

// TestSubjectRolesInvalidInputs 对照 Nexus tests/subject_roles.rs::invalid_inputs_are_rejected。
func TestSubjectRolesInvalidInputs(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)
	catalogCreateRole(t, mux, "nursing.staff", "护理人员", nil)

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/"+catalogAssignedSubjectID,
		map[string]any{"role_codes": []string{"nursing.staff", "ghost.role"}}), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/subject-1",
		map[string]any{"role_codes": []string{"nursing.staff"}}), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet,
		"/subject-roles?subject_ids=not-a-ulid", nil), http.StatusBadRequest)

	// 缺 role_codes 必须是客户端错误，且不得静默清空既有分配。
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/"+catalogAssignedSubjectID,
		map[string]any{"role_codes": []string{"nursing.staff"}}), http.StatusOK)
	missing := catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/"+catalogAssignedSubjectID, map[string]any{})
	if missing.Code < 400 || missing.Code >= 500 {
		t.Fatalf("missing role_codes status = %d, want 4xx", missing.Code)
	}
	surviving := catalogRequest(t, mux, http.MethodGet, "/subject-roles?subject_ids="+catalogAssignedSubjectID, nil)
	if len(catalogArray(t, surviving)) != 1 {
		t.Fatalf("missing role_codes silently cleared assignments")
	}
	// 重复/空白角色码去重后不得报错
	duplicated := catalogRequest(t, mux, http.MethodPut, "/subject-roles/subjects/"+catalogAssignedSubjectID,
		map[string]any{"role_codes": []string{" nursing.staff ", "nursing.staff", ""}})
	catalogAssertStatus(t, duplicated, http.StatusOK)
	if len(catalogArray(t, duplicated)) != 1 {
		t.Fatalf("duplicate role_codes were not deduplicated")
	}
}

// TestSubjectRolesAssignedRoleCannotBeDeleted 对照 Nexus tests/subject_roles.rs::assigned_role_cannot_be_deleted。
func TestSubjectRolesAssignedRoleCannotBeDeleted(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)
	role := catalogCreateRole(t, mux, "nursing.staff", "护理人员", nil)
	roleID := role["id"].(string)

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/"+catalogAssignedSubjectID,
		map[string]any{"role_codes": []string{"nursing.staff"}}), http.StatusOK)

	blocked := catalogRequest(t, mux, http.MethodDelete, "/roles/"+roleID, nil)
	catalogAssertStatus(t, blocked, http.StatusConflict)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/roles/"+roleID, nil), http.StatusOK)

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/"+catalogAssignedSubjectID,
		map[string]any{"role_codes": []string{}}), http.StatusOK)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodDelete, "/roles/"+roleID, nil), http.StatusNoContent)
}

// TestSubjectPermissionsUnionRolePermissionCodes 对照 Nexus tests/subject_roles.rs::subject_permissions_union_role_permission_codes。
func TestSubjectPermissionsUnionRolePermissionCodes(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)
	catalogCreateRole(t, mux, "nursing.staff", "护理人员", []string{"nursing:execute", "nursing:read"})
	catalogCreateRole(t, mux, "pharmacy.manager", "药房管理员", []string{"pharmacy:manage", "nursing:read"})
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut,
		"/subject-roles/subjects/"+catalogAssignedSubjectID,
		map[string]any{"role_codes": []string{"nursing.staff", "pharmacy.manager"}}), http.StatusOK)

	recorder := catalogRequest(t, mux, http.MethodGet,
		"/subject-permissions?subject_id="+catalogAssignedSubjectID, nil)
	catalogAssertStatus(t, recorder, http.StatusOK)
	payload := catalogObject(t, recorder)
	if payload["subject_id"] != catalogAssignedSubjectID {
		t.Fatalf("subject_id = %#v", payload["subject_id"])
	}
	catalogAssertStrings(t, payload["role_codes"], []string{"nursing.staff", "pharmacy.manager"})
	catalogAssertStrings(t, payload["permission_codes"], []string{"nursing:execute", "nursing:read", "pharmacy:manage"})
	if payload["source"] != "nexus" {
		t.Fatalf("source = %#v, want nexus (Nexus 契约)", payload["source"])
	}

	emptyRecorder := catalogRequest(t, mux, http.MethodGet,
		"/subject-permissions?subject_id=01M3ZR3CENV2DGYY2RS1BPJG2D", nil)
	catalogAssertStatus(t, emptyRecorder, http.StatusOK)
	empty := catalogObject(t, emptyRecorder)
	catalogAssertStrings(t, empty["role_codes"], []string{})
	catalogAssertStrings(t, empty["permission_codes"], []string{})

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet,
		"/subject-permissions?subject_id=not-a-ulid", nil), http.StatusBadRequest)
}
