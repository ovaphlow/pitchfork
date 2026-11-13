package shared_test

import (
	"net/http"
	"net/http/httptest"
	"testing"
)

const catalogOtherSubjectID = "01M3ZR3CENV2DGYY2RS1BPJG2D"

func catalogSetSubjectDepartment(t *testing.T, mux *http.ServeMux, subjectID string, departmentID any) *httptest.ResponseRecorder {
	t.Helper()
	return catalogRequest(t, mux, http.MethodPut, "/subject-departments/subjects/"+subjectID, map[string]any{
		"department_id": departmentID,
	})
}

// TestSetReadAndClearSubjectDepartment 对照 Nexus tests/subject_departments.rs::set_read_and_clear_subject_department。
func TestSetReadAndClearSubjectDepartment(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)
	department := catalogCreateDepartment(t, mux, "nursing", "护理部")
	departmentID := department["id"].(string)

	recorder := catalogSetSubjectDepartment(t, mux, catalogAssignedSubjectID, departmentID)
	catalogAssertStatus(t, recorder, http.StatusOK)
	assignments := catalogArray(t, recorder)
	if len(assignments) != 1 {
		t.Fatalf("assignment length = %d, want 1", len(assignments))
	}
	assignment := assignments[0].(map[string]any)
	if assignment["subject_id"] != catalogAssignedSubjectID {
		t.Fatalf("subject_id = %#v", assignment["subject_id"])
	}
	if assignment["department_id"] != departmentID {
		t.Fatalf("department_id = %#v", assignment["department_id"])
	}
	if assignment["department_code"] != "nursing" || assignment["department_name"] != "护理部" {
		t.Fatalf("department join fields = %#v", assignment)
	}
	if assignment["granted_by_subject_id"] != catalogAdminSubjectID {
		t.Fatalf("granted_by_subject_id = %#v", assignment["granted_by_subject_id"])
	}

	listed := catalogRequest(t, mux, http.MethodGet, "/subject-departments?subject_ids="+catalogAssignedSubjectID, nil)
	catalogAssertStatus(t, listed, http.StatusOK)
	listedBody := catalogObject(t, listed)
	if listedBody["meta"].(map[string]any)["total"] != float64(1) {
		t.Fatalf("meta.total = %#v", listedBody["meta"])
	}
	records := listedBody["records"].([]any)
	if len(records) != 1 || records[0].(map[string]any)["department_code"] != "nursing" {
		t.Fatalf("records = %#v", records)
	}

	// 部门页的成员数来自同一张关系表。
	departments := catalogObject(t, catalogRequest(t, mux, http.MethodGet, "/departments", nil))
	if departments["records"].([]any)[0].(map[string]any)["member_count"] != float64(1) {
		t.Fatalf("member_count = %#v, want 1", departments["records"].([]any)[0])
	}

	unrelated := catalogObject(t, catalogRequest(t, mux, http.MethodGet,
		"/subject-departments?subject_ids="+catalogOtherSubjectID, nil))
	if unrelated["meta"].(map[string]any)["total"] != float64(0) {
		t.Fatalf("unrelated meta.total = %#v", unrelated["meta"])
	}
	if len(unrelated["records"].([]any)) != 0 {
		t.Fatalf("unrelated records must be empty")
	}

	// department_id: null 清空
	cleared := catalogSetSubjectDepartment(t, mux, catalogAssignedSubjectID, nil)
	catalogAssertStatus(t, cleared, http.StatusOK)
	if len(catalogArray(t, cleared)) != 0 {
		t.Fatalf("cleared assignment must be empty")
	}

	// 缺失 department_id 与 null 同义（Nexus Option 语义）：清空
	catalogAssertStatus(t, catalogSetSubjectDepartment(t, mux, catalogAssignedSubjectID, departmentID), http.StatusOK)
	missing := catalogRequest(t, mux, http.MethodPut,
		"/subject-departments/subjects/"+catalogAssignedSubjectID, map[string]any{})
	catalogAssertStatus(t, missing, http.StatusOK)
	if len(catalogArray(t, missing)) != 0 {
		t.Fatalf("missing department_id did not clear assignment (Nexus Option 语义)")
	}
}

// TestUnknownDepartmentAndInvalidSubject 对照 Nexus tests/subject_departments.rs::unknown_department_and_invalid_subject_are_rejected。
func TestUnknownDepartmentAndInvalidSubject(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	mux := catalogMux(t, databaseConnection, nil)

	catalogAssertStatus(t, catalogSetSubjectDepartment(t, mux, catalogAssignedSubjectID,
		"01M3ZR3CENV2DGYY2RS1BPJG2D"), http.StatusBadRequest)
	catalogAssertStatus(t, catalogSetSubjectDepartment(t, mux, "subject-1", nil), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet,
		"/subject-departments?subject_ids=not-a-ulid", nil), http.StatusBadRequest)
	catalogAssertStatus(t, catalogSetSubjectDepartment(t, mux, catalogAssignedSubjectID,
		"not-a-ulid"), http.StatusBadRequest)
}
