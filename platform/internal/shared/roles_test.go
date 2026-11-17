package shared_test

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"testing"

	"github.com/ovaphlow/pitchfork/platform/db/migrations"
	"github.com/ovaphlow/pitchfork/platform/internal/database"
	"github.com/ovaphlow/pitchfork/platform/internal/shared"
)

// 与 Nexus tests/authorization.rs 相同的测试主体（均为合法 26 位 ULID）。
const (
	catalogAdminSubjectID    = "01KYE3F6CPNJS16RHT01M64D8B"
	catalogManagerSubjectID  = "01M3ZR3CENV2DGYY2RS1BPJG2D"
	catalogStaffSubjectID    = "01M3ZR3CDAQ6ZKHCYD065RSYMT"
	catalogOrgAdminSubjectID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
)

// openCatalogTestDB 打开临时文件 SQLite 并执行全部迁移（与线上同一套 migration）。
func openCatalogTestDB(t *testing.T) *sql.DB {
	t.Helper()
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "platformd.sqlite"))
	if err != nil {
		t.Fatalf("open SQLite database: %v", err)
	}
	t.Cleanup(func() {
		_ = databaseConnection.Close()
	})
	if _, err := database.Migrate(context.Background(), databaseConnection, migrations.Files); err != nil {
		t.Fatalf("migrate database: %v", err)
	}
	return databaseConnection
}

// catalogAdminSession 是默认会话桩：平台管理员（门禁的引导者路径）。
func catalogAdminSession(*http.Request) (shared.Session, bool) {
	return shared.Session{SubjectID: catalogAdminSubjectID, PlatformAdmin: true}, true
}

func catalogSession(subjectID string, platformAdmin bool) func(*http.Request) (shared.Session, bool) {
	return func(*http.Request) (shared.Session, bool) {
		return shared.Session{SubjectID: subjectID, PlatformAdmin: platformAdmin}, true
	}
}

func catalogNoSession(*http.Request) (shared.Session, bool) {
	return shared.Session{}, false
}

// catalogMux 用真实 Store + 会话桩构造共享模块路由（不依赖真实端口）。
func catalogMux(t *testing.T, databaseConnection *sql.DB, sessions func(*http.Request) (shared.Session, bool)) *http.ServeMux {
	t.Helper()
	if sessions == nil {
		sessions = catalogAdminSession
	}
	mux := http.NewServeMux()
	shared.NewHandler(shared.Store{DB: databaseConnection, RequireSession: sessions}).Register(mux)
	return mux
}

func catalogRequest(t *testing.T, mux *http.ServeMux, method string, path string, body any) *httptest.ResponseRecorder {
	t.Helper()
	var payload []byte
	if body != nil {
		encoded, err := json.Marshal(body)
		if err != nil {
			t.Fatalf("encode request body: %v", err)
		}
		payload = encoded
	}
	request := httptest.NewRequest(method, shared.Prefix+path, bytes.NewReader(payload))
	request.Header.Set(shared.AppCodeHeader, "aceso")
	request.Header.Set("Cookie", "platformd_session=test-session")
	if body != nil {
		request.Header.Set("Content-Type", "application/json")
	}
	recorder := httptest.NewRecorder()
	mux.ServeHTTP(recorder, request)
	return recorder
}

func catalogJSON(t *testing.T, recorder *httptest.ResponseRecorder) any {
	t.Helper()
	var payload any
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("decode JSON response %q: %v", recorder.Body.String(), err)
	}
	return payload
}

func catalogObject(t *testing.T, recorder *httptest.ResponseRecorder) map[string]any {
	t.Helper()
	payload, ok := catalogJSON(t, recorder).(map[string]any)
	if !ok {
		t.Fatalf("expected JSON object, got %q", recorder.Body.String())
	}
	return payload
}

func catalogArray(t *testing.T, recorder *httptest.ResponseRecorder) []any {
	t.Helper()
	payload, ok := catalogJSON(t, recorder).([]any)
	if !ok {
		t.Fatalf("expected JSON array, got %q", recorder.Body.String())
	}
	return payload
}

func catalogStrings(t *testing.T, value any) []string {
	t.Helper()
	items, ok := value.([]any)
	if !ok {
		t.Fatalf("expected JSON array of strings, got %#v", value)
	}
	result := make([]string, 0, len(items))
	for _, item := range items {
		text, ok := item.(string)
		if !ok {
			t.Fatalf("expected JSON string element, got %#v", item)
		}
		result = append(result, text)
	}
	return result
}

func catalogAssertStrings(t *testing.T, value any, want []string) {
	t.Helper()
	got := catalogStrings(t, value)
	if len(got) != len(want) {
		t.Fatalf("string list length = %v, want %v", got, want)
	}
	for index := range want {
		if got[index] != want[index] {
			t.Fatalf("string list = %v, want %v", got, want)
		}
	}
}

func catalogAssertStatus(t *testing.T, recorder *httptest.ResponseRecorder, want int) {
	t.Helper()
	if recorder.Code != want {
		t.Fatalf("status = %d, want %d (body %q)", recorder.Code, want, recorder.Body.String())
	}
}

func catalogCreateRole(t *testing.T, mux *http.ServeMux, roleCode string, displayName string, permissionCodes []string) map[string]any {
	t.Helper()
	recorder := catalogRequest(t, mux, http.MethodPost, "/roles", map[string]any{
		"role_code":        roleCode,
		"display_name":     displayName,
		"permission_codes": permissionCodes,
	})
	catalogAssertStatus(t, recorder, http.StatusCreated)
	return catalogObject(t, recorder)
}

func catalogCreateDepartment(t *testing.T, mux *http.ServeMux, code string, name string) map[string]any {
	t.Helper()
	recorder := catalogRequest(t, mux, http.MethodPost, "/departments", map[string]any{
		"code": code,
		"name": name,
	})
	catalogAssertStatus(t, recorder, http.StatusCreated)
	return catalogObject(t, recorder)
}

// TestRolesDirectoryCRUDFlow 对照 Nexus tests/roles.rs::roles_directory_crud_flow。
func TestRolesDirectoryCRUDFlow(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	anonymousMux := catalogMux(t, databaseConnection, catalogNoSession)
	catalogAssertStatus(t, catalogRequest(t, anonymousMux, http.MethodGet, "/roles", nil), http.StatusUnauthorized)

	mux := catalogMux(t, databaseConnection, nil)

	created := catalogCreateRole(t, mux, "pharmacy.manager", "药房管理员", []string{"pharmacy:manage", "pharmacy:execute", "pharmacy:manage"})
	pharmacyID, ok := created["id"].(string)
	if !ok || pharmacyID == "" {
		t.Fatalf("role id missing: %#v", created)
	}
	if created["role_code"] != "pharmacy.manager" {
		t.Fatalf("role_code = %#v", created["role_code"])
	}
	if created["description"] != "" {
		t.Fatalf("description default = %#v, want empty", created["description"])
	}
	catalogAssertStrings(t, created["permission_codes"], []string{"pharmacy:manage", "pharmacy:execute"})
	if created["created_at"] == "" || created["updated_at"] == "" {
		t.Fatalf("timestamps missing: %#v", created)
	}

	catalogCreateRole(t, mux, "nursing.staff", "护理人员", []string{"nursing:execute"})

	listed := catalogRequest(t, mux, http.MethodGet, "/roles?page=1&page_size=100", nil)
	catalogAssertStatus(t, listed, http.StatusOK)
	records := catalogArray(t, listed)
	if len(records) != 2 {
		t.Fatalf("list length = %d, want 2", len(records))
	}
	codes := make([]string, 0, len(records))
	for _, record := range records {
		codes = append(codes, record.(map[string]any)["role_code"].(string))
	}
	catalogAssertStrings(t, toAnyStrings(codes), []string{"nursing.staff", "pharmacy.manager"})

	// 尾斜杠兼容
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/roles/", nil), http.StatusOK)

	fetched := catalogRequest(t, mux, http.MethodGet, "/roles/"+pharmacyID, nil)
	catalogAssertStatus(t, fetched, http.StatusOK)
	if catalogObject(t, fetched)["display_name"] != "药房管理员" {
		t.Fatalf("fetched display_name = %#v", catalogObject(t, fetched)["display_name"])
	}

	duplicate := catalogRequest(t, mux, http.MethodPost, "/roles", map[string]any{
		"role_code": "pharmacy.manager", "display_name": "重复角色",
	})
	catalogAssertStatus(t, duplicate, http.StatusConflict)

	for _, rejected := range []map[string]any{
		{"role_code": "Pharmacy.Manager", "display_name": "非法编码"},
		{"role_code": "nursing.manager", "display_name": "   "},
		{"role_code": "junk.role", "display_name": "垃圾权限码", "permission_codes": []string{"totally.made.up:code"}},
		{"role_code": "junk.role2", "display_name": "垃圾权限码", "permission_codes": []string{"中文权限码"}},
		{"role_code": "junk.role3", "display_name": "垃圾权限码", "permission_codes": []string{"12345"}},
	} {
		catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/roles", rejected), http.StatusBadRequest)
	}
	// 空角色码与超长角色码
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/roles", map[string]any{
		"role_code": "", "display_name": "空编码",
	}), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPost, "/roles", map[string]any{
		"role_code": repeatString("a", 65), "display_name": "超长编码",
	}), http.StatusBadRequest)

	updatedRecorder := catalogRequest(t, mux, http.MethodPut, "/roles/"+pharmacyID, map[string]any{
		"role_code":        "pharmacy.manager",
		"display_name":     "药房主管",
		"description":      "负责药房全面管理",
		"permission_codes": []string{"pharmacy:manage"},
	})
	catalogAssertStatus(t, updatedRecorder, http.StatusOK)
	updated := catalogObject(t, updatedRecorder)
	if updated["display_name"] != "药房主管" {
		t.Fatalf("updated display_name = %#v", updated["display_name"])
	}
	catalogAssertStrings(t, updated["permission_codes"], []string{"pharmacy:manage"})
	if updated["created_at"] != created["created_at"] {
		t.Fatalf("created_at changed on update: %v -> %v", created["created_at"], updated["created_at"])
	}
	if updated["updated_at"] == created["updated_at"] {
		t.Logf("updated_at unchanged within the same millisecond (acceptable)")
	}

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodPut, "/roles/"+pharmacyID, map[string]any{
		"role_code": "pharmacy.chief", "display_name": "药房主管",
	}), http.StatusBadRequest)

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/roles/not-a-real-id", nil), http.StatusNotFound)

	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodDelete, "/roles/"+pharmacyID, nil), http.StatusNoContent)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/roles/"+pharmacyID, nil), http.StatusNotFound)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodDelete, "/roles/"+pharmacyID, nil), http.StatusNotFound)

	// 非法分页 400
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/roles?page=0", nil), http.StatusBadRequest)
	catalogAssertStatus(t, catalogRequest(t, mux, http.MethodGet, "/roles?page_size=101", nil), http.StatusBadRequest)
}

// TestCatalogWriteGates 对照 Nexus tests/authorization.rs 的 403 矩阵（含 required_permission）。
func TestCatalogWriteGates(t *testing.T) {
	databaseConnection := openCatalogTestDB(t)
	adminMux := catalogMux(t, databaseConnection, nil)
	staffMux := catalogMux(t, databaseConnection, catalogSession(catalogStaffSubjectID, false))

	assertPermissionRequired(t, catalogRequest(t, staffMux, http.MethodPost, "/roles", map[string]any{
		"role_code": "self.escalate", "display_name": "自授角色", "permission_codes": []string{"nursing:execute"},
	}), shared.AccessManagePermission)
	assertPermissionRequired(t, catalogRequest(t, staffMux, http.MethodPut,
		"/subject-roles/subjects/"+catalogStaffSubjectID, map[string]any{"role_codes": []string{"nursing.staff"}}),
		shared.AccessManagePermission)
	assertPermissionRequired(t, catalogRequest(t, staffMux, http.MethodPut,
		"/subject-departments/subjects/"+catalogStaffSubjectID, map[string]any{"department_id": nil}),
		shared.AccessManagePermission)
	assertPermissionRequired(t, catalogRequest(t, staffMux, http.MethodPost, "/departments", map[string]any{
		"code": "qa-authz", "name": "越权部门",
	}), shared.BaseDataManagePermission)

	// 读路由保持开放（043 D7）
	for _, resource := range []string{"/roles", "/departments", "/subject-roles", "/subject-departments"} {
		catalogAssertStatus(t, catalogRequest(t, staffMux, http.MethodGet, resource, nil), http.StatusOK)
	}

	// 越权读收紧：staff 不能读他人的 subject-permissions
	assertPermissionRequired(t, catalogRequest(t, staffMux, http.MethodGet,
		"/subject-permissions?subject_id="+catalogAdminSubjectID, nil), shared.AccessManagePermission)
	// 读自己允许
	catalogAssertStatus(t, catalogRequest(t, staffMux, http.MethodGet,
		"/subject-permissions?subject_id="+catalogStaffSubjectID, nil), http.StatusOK)

	// 引导者 + 委派：admin 建 access.manage 角色并分给 manager
	catalogCreateRole(t, adminMux, "access.manager", "账号管理员", []string{shared.AccessManagePermission})
	catalogAssertStatus(t, catalogRequest(t, adminMux, http.MethodPut,
		"/subject-roles/subjects/"+catalogManagerSubjectID, map[string]any{"role_codes": []string{"access.manager"}}),
		http.StatusOK)
	managerMux := catalogMux(t, databaseConnection, catalogSession(catalogManagerSubjectID, false))
	catalogCreateRole(t, managerMux, "pharmacy.staff", "药房药师", []string{"pharmacy:dispense"})
	// manager 仍改不了部门目录
	assertPermissionRequired(t, catalogRequest(t, managerMux, http.MethodPost, "/departments", map[string]any{
		"code": "manager-dept", "name": "越权目录",
	}), shared.BaseDataManagePermission)

	// 反向：base_data:manage 不能建角色，但能写部门
	catalogCreateRole(t, adminMux, "org.admin", "组织管理员", []string{shared.BaseDataManagePermission})
	catalogAssertStatus(t, catalogRequest(t, adminMux, http.MethodPut,
		"/subject-roles/subjects/"+catalogOrgAdminSubjectID, map[string]any{"role_codes": []string{"org.admin"}}),
		http.StatusOK)
	orgAdminMux := catalogMux(t, databaseConnection, catalogSession(catalogOrgAdminSubjectID, false))
	assertPermissionRequired(t, catalogRequest(t, orgAdminMux, http.MethodPost, "/roles", map[string]any{
		"role_code": "org.other", "display_name": "越权角色",
	}), shared.AccessManagePermission)
	catalogCreateDepartment(t, orgAdminMux, "nursing", "护理部")
}

func assertPermissionRequired(t *testing.T, recorder *httptest.ResponseRecorder, permission string) {
	t.Helper()
	catalogAssertStatus(t, recorder, http.StatusForbidden)
	body := catalogObject(t, recorder)
	if body["required_permission"] != permission {
		t.Fatalf("required_permission = %#v, want %q (body %#v)", body["required_permission"], permission, body)
	}
	wantType := shared.Prefix + "/problems/permission-required"
	if body["type"] != wantType {
		t.Fatalf("problem type = %#v, want %q", body["type"], wantType)
	}
	if body["status"] != float64(http.StatusForbidden) {
		t.Fatalf("problem status = %#v", body["status"])
	}
}

func toAnyStrings(values []string) any {
	result := make([]any, 0, len(values))
	for _, value := range values {
		result = append(result, value)
	}
	return result
}

func repeatString(value string, count int) string {
	result := ""
	for index := 0; index < count; index++ {
		result += value
	}
	return result
}
