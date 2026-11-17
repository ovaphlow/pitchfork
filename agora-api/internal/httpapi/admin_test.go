package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"sort"
	"strings"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/intents"
)

type fakeIntents struct {
	values map[string]intents.UserType
}

func newFakeIntents() *fakeIntents {
	return &fakeIntents{values: map[string]intents.UserType{}}
}

func (f *fakeIntents) Set(_ context.Context, subjectID string, userType intents.UserType) error {
	f.values[subjectID] = userType
	return nil
}

func (f *fakeIntents) Ensure(_ context.Context, subjectID string) error {
	if _, ok := f.values[subjectID]; !ok {
		f.values[subjectID] = ""
	}
	return nil
}

func (f *fakeIntents) List(_ context.Context, limit, offset int32) ([]string, int64, error) {
	ids := make([]string, 0, len(f.values))
	for subjectID := range f.values {
		ids = append(ids, subjectID)
	}
	sort.Strings(ids)
	total := int64(len(ids))
	start := int(offset)
	if start > len(ids) {
		start = len(ids)
	}
	end := start + int(limit)
	if end > len(ids) {
		end = len(ids)
	}
	return ids[start:end], total, nil
}

func (f *fakeIntents) BySubjects(_ context.Context, subjectIDs []string) (map[string]intents.UserType, error) {
	out := map[string]intents.UserType{}
	for _, subjectID := range subjectIDs {
		if value, ok := f.values[subjectID]; ok {
			out[subjectID] = value
		}
	}
	return out, nil
}

type adminIdentityStub struct {
	platformAdmin bool
	lastCSRF      string
	lastStatus    string
}

func (stub *adminIdentityStub) server() *httptest.Server {
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodGet && r.URL.Path == "/crate-api/identity/v1/session":
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"subject_id":     "ADMIN1",
				"display_name":   "平台管理员",
				"access":         "完整",
				"platform_admin": stub.platformAdmin,
			})
		case r.Method == http.MethodGet && r.URL.Path == "/crate-api/identity/v1/subjects":
			records := []map[string]any{{
				"id":             "01ARZ3NDEKTSV4RRFFQ69G5FAV",
				"status":         "启用",
				"display_name":   "张三",
				"identifier":     "zhangsan",
				"platform_admin": false,
			}}
			if rawIDs := r.URL.Query().Get("subject_ids"); rawIDs != "" {
				wanted := map[string]bool{}
				for _, id := range strings.Split(rawIDs, ",") {
					wanted[id] = true
				}
				filtered := make([]map[string]any, 0, len(records))
				for _, record := range records {
					if wanted[record["id"].(string)] {
						filtered = append(filtered, record)
					}
				}
				records = filtered
			}
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"records": records,
				"meta":    map[string]int64{"total": int64(len(records))},
			})
		case r.Method == http.MethodPatch && strings.HasPrefix(r.URL.Path, "/crate-api/identity/v1/subjects/"):
			var body struct {
				Status string `json:"status"`
			}
			_ = json.NewDecoder(r.Body).Decode(&body)
			stub.lastCSRF = r.Header.Get("X-CSRF-Token")
			stub.lastStatus = body.Status
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"id":             strings.TrimPrefix(r.URL.Path, "/crate-api/identity/v1/subjects/"),
				"status":         body.Status,
				"display_name":   "张三",
				"identifier":     "zhangsan",
				"platform_admin": false,
			})
		default:
			http.NotFound(w, r)
		}
	}))
}

func adminHandler(stub *adminIdentityStub, intentStore *fakeIntents) (http.Handler, func()) {
	identity := stub.server()
	handler := New(Deps{
		Logger:           testLogger(),
		DB:               pingerFunc(func(context.Context) error { return nil }),
		AllowedOrigins:   []string{"http://localhost:4321"},
		ReadinessTimeout: time.Second,
		Identity:         identityclient.New(identity.URL, time.Second),
		Organizations:    newFakeOrganizations(),
		Positions:        newFakePositions(),
		Intents:          intentStore,
	})
	return handler, identity.Close
}

func TestAdminSubjectsRequirePlatformAdmin(t *testing.T) {
	stub := &adminIdentityStub{platformAdmin: false}
	handler, cleanup := adminHandler(stub, newFakeIntents())
	defer cleanup()

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/admin/subjects", "", withSession(nil))
	if response.Code != http.StatusForbidden {
		t.Fatalf("list status = %d, want 403: %s", response.Code, response.Body.String())
	}
	response = doRequest(handler, http.MethodPatch, "/crate-api/agora/v1/admin/subjects/SUB1",
		`{"status":"禁用"}`, withSession(nil))
	if response.Code != http.StatusForbidden {
		t.Fatalf("update status = %d, want 403: %s", response.Code, response.Body.String())
	}
}

func TestAdminSubjectsListAndDisable(t *testing.T) {
	stub := &adminIdentityStub{platformAdmin: true}
	intentStore := newFakeIntents()
	intentStore.values["01ARZ3NDEKTSV4RRFFQ69G5FAV"] = intents.JobSeeker
	handler, cleanup := adminHandler(stub, intentStore)
	defer cleanup()

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/admin/subjects?limit=20", "", withSession(nil))
	if response.Code != http.StatusOK {
		t.Fatalf("list status = %d, want 200: %s", response.Code, response.Body.String())
	}
	var page struct {
		Records []struct {
			identityclient.Subject
			UserType string `json:"user_type"`
		} `json:"records"`
		Meta struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatalf("decode list: %v", err)
	}
	if len(page.Records) != 1 || page.Meta.Total != 1 || page.Records[0].Identifier != "zhangsan" {
		t.Fatalf("page = %+v", page)
	}
	if page.Records[0].UserType != string(intents.JobSeeker) {
		t.Fatalf("user_type = %q", page.Records[0].UserType)
	}

	headers := withSession(map[string]string{"X-CSRF-Token": "csrf-123"})
	response = doRequest(handler, http.MethodPatch, "/crate-api/agora/v1/admin/subjects/SUB1",
		`{"status":"禁用"}`, headers)
	if response.Code != http.StatusOK {
		t.Fatalf("update status = %d, want 200: %s", response.Code, response.Body.String())
	}
	if stub.lastCSRF != "csrf-123" || stub.lastStatus != "禁用" {
		t.Fatalf("forwarded csrf/status = %q/%q", stub.lastCSRF, stub.lastStatus)
	}

	response = doRequest(handler, http.MethodPatch, "/crate-api/agora/v1/admin/subjects/SUB1",
		`{"status":"未知"}`, headers)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid status = %d, want 400: %s", response.Code, response.Body.String())
	}
}

func TestAdminSubjectsExcludeAccountsOutsideAgora(t *testing.T) {
	stub := &adminIdentityStub{platformAdmin: true}
	handler, cleanup := adminHandler(stub, newFakeIntents())
	defer cleanup()

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/admin/subjects", "", withSession(nil))
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", response.Code, response.Body.String())
	}
	var page struct {
		Records []json.RawMessage `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &page); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(page.Records) != 0 || page.Meta.Total != 0 {
		t.Fatalf("platformd-only accounts leaked: %+v", page)
	}
}

func TestSessionRegistersAgoraUser(t *testing.T) {
	stub := &adminIdentityStub{platformAdmin: false}
	intentStore := newFakeIntents()
	handler, cleanup := adminHandler(stub, intentStore)
	defer cleanup()

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/auth/session", "", withSession(nil))
	if response.Code != http.StatusOK {
		t.Fatalf("session status = %d, want 200: %s", response.Code, response.Body.String())
	}
	if _, ok := intentStore.values["ADMIN1"]; !ok {
		t.Fatal("session did not register the Agora user")
	}
}

func TestSaveMyIntentRequiresSessionAndValidType(t *testing.T) {
	stub := &adminIdentityStub{platformAdmin: false}
	intentStore := newFakeIntents()
	handler, cleanup := adminHandler(stub, intentStore)
	defer cleanup()

	response := doRequest(handler, http.MethodPut, "/crate-api/agora/v1/me/intent",
		`{"user_type":"recruiter"}`, nil)
	if response.Code != http.StatusUnauthorized {
		t.Fatalf("anonymous intent status = %d, want 401", response.Code)
	}

	response = doRequest(handler, http.MethodPut, "/crate-api/agora/v1/me/intent",
		`{"user_type":"recruiter"}`, withSession(nil))
	if response.Code != http.StatusNoContent {
		t.Fatalf("intent status = %d, want 204: %s", response.Code, response.Body.String())
	}
	// platformd stub uses subject ADMIN1 for every session.
	if got := intentStore.values["ADMIN1"]; got != intents.Recruiter {
		t.Fatalf("stored intent = %q", got)
	}

	response = doRequest(handler, http.MethodPut, "/crate-api/agora/v1/me/intent",
		`{"user_type":"admin"}`, withSession(nil))
	if response.Code != http.StatusBadRequest {
		t.Fatalf("invalid intent status = %d, want 400: %s", response.Code, response.Body.String())
	}
}
