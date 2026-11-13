package httpapi

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
)

const testOrganizationID = "01ARZ3NDEKTSV4RRFFQ69G5FAV"

func TestListOrganizationsReturnsEmptyEnvelope(t *testing.T) {
	handler := newDirectoryHandler("http://127.0.0.1:1", newFakeOrganizations(), newFakePositions())

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/organizations", "", nil)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", response.Code)
	}
	var body struct {
		Records []organizations.Organization `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Records == nil || len(body.Records) != 0 {
		t.Fatalf("records = %v, want an empty array", body.Records)
	}
	if body.Meta.Total != 0 {
		t.Fatalf("meta.total = %d, want 0", body.Meta.Total)
	}
}

func TestCreateOrganizationRequiresSession(t *testing.T) {
	handler := newDirectoryHandler("http://127.0.0.1:1", newFakeOrganizations(), newFakePositions())

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations", `{"name":"甲医院"}`, nil)

	if response.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", response.Code)
	}
}

func TestCreateOrganizationRejectsUnknownField(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	orgs := newFakeOrganizations()
	handler := newDirectoryHandler(identity.URL, orgs, newFakePositions())

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations",
		`{"name":"甲医院","owner":"someone-else"}`, withSession(nil))

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400: %s", response.Code, response.Body.String())
	}
	if len(orgs.byID) != 0 {
		t.Fatal("a rejected request must not reach the store")
	}
}

func TestCreateOrganizationRecordsCallerAsOwner(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	orgs := newFakeOrganizations()
	handler := newDirectoryHandler(identity.URL, orgs, newFakePositions())

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations",
		`{"name":"甲医院","city":"上海","metadata":{"industry":"医疗健康"}}`, withSession(nil))

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201: %s", response.Code, response.Body.String())
	}
	if orgs.lastOwner != "SUB1" {
		t.Fatalf("owner = %q, want SUB1", orgs.lastOwner)
	}
	if orgs.lastCreate.Metadata.Industry != "医疗健康" {
		t.Fatalf("metadata was not passed through: %+v", orgs.lastCreate.Metadata)
	}

	var body organizations.Organization
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.Status != organizations.StatusPending {
		t.Fatalf("status = %q, want 待审核", body.Status)
	}
}

func TestCreateOrganizationValidatesInput(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	handler := newDirectoryHandler(identity.URL, newFakeOrganizations(), newFakePositions())

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations",
		`{"name":"   "}`, withSession(nil))

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
	if message := decodeError(t, response.Body.Bytes()); message != "机构名称不能为空" {
		t.Fatalf("error = %q", message)
	}
}

func TestGetUnverifiedOrganizationIsHiddenFromStrangers(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	orgs := newFakeOrganizations()
	orgs.byID[testOrganizationID] = organizations.Organization{
		ID:     testOrganizationID,
		Name:   "甲医院",
		Status: organizations.StatusPending,
	}
	handler := newDirectoryHandler(identity.URL, orgs, newFakePositions())

	anonymous := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/organizations/"+testOrganizationID, "", nil)
	if anonymous.Code != http.StatusNotFound {
		t.Fatalf("anonymous status = %d, want 404", anonymous.Code)
	}

	orgs.grant(testOrganizationID, "SUB1", organizations.RoleRecruiter)
	member := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/organizations/"+testOrganizationID, "", withSession(nil))
	if member.Code != http.StatusOK {
		t.Fatalf("member status = %d, want 200: %s", member.Code, member.Body.String())
	}
}

func TestUpdateOrganizationRequiresProfileRights(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	orgs := newFakeOrganizations()
	orgs.byID[testOrganizationID] = organizations.Organization{ID: testOrganizationID, Name: "甲医院", Status: organizations.StatusVerified}
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleRecruiter)
	handler := newDirectoryHandler(identity.URL, orgs, newFakePositions())

	response := doRequest(handler, http.MethodPatch, "/crate-api/agora/v1/organizations/"+testOrganizationID,
		`{"name":"乙医院"}`, withSession(nil))

	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403: %s", response.Code, response.Body.String())
	}
	if orgs.lastUpdate.Name != nil {
		t.Fatal("a forbidden update must not reach the store")
	}

	orgs.grant(testOrganizationID, "SUB1", organizations.RoleAdmin)
	response = doRequest(handler, http.MethodPatch, "/crate-api/agora/v1/organizations/"+testOrganizationID,
		`{"name":"乙医院"}`, withSession(nil))
	if response.Code != http.StatusOK {
		t.Fatalf("admin status = %d, want 200: %s", response.Code, response.Body.String())
	}
}

func TestReviewOrganizationRequiresPlatformAdmin(t *testing.T) {
	orgs := newFakeOrganizations()
	orgs.byID[testOrganizationID] = organizations.Organization{ID: testOrganizationID, Name: "甲医院", Status: organizations.StatusPending}

	member := newSessionProvider(t, "SUB1", false)
	defer member.Close()
	handler := newDirectoryHandler(member.URL, orgs, newFakePositions())

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations/"+testOrganizationID+"/review",
		`{"status":"已认证"}`, withSession(nil))
	if response.Code != http.StatusForbidden {
		t.Fatalf("member status = %d, want 403", response.Code)
	}

	admin := newSessionProvider(t, "ADMIN1", true)
	defer admin.Close()
	handler = newDirectoryHandler(admin.URL, orgs, newFakePositions())

	response = doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations/"+testOrganizationID+"/review",
		`{"status":"已认证"}`, withSession(nil))
	if response.Code != http.StatusOK {
		t.Fatalf("admin status = %d, want 200: %s", response.Code, response.Body.String())
	}
	if orgs.lastActor != "ADMIN1" || orgs.lastReview.Status != organizations.StatusVerified {
		t.Fatalf("review = %+v by %q", orgs.lastReview, orgs.lastActor)
	}
}

func TestOrganizationMemberEndpoints(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	orgs := newFakeOrganizations()
	orgs.byID[testOrganizationID] = organizations.Organization{ID: testOrganizationID, Name: "甲医院", Status: organizations.StatusVerified}
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleOwner)
	handler := newDirectoryHandler(identity.URL, orgs, newFakePositions())

	added := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations/"+testOrganizationID+"/members",
		`{"subject_id":"SUB2","member_role":"招聘专员"}`, withSession(nil))
	if added.Code != http.StatusCreated {
		t.Fatalf("add status = %d, want 201: %s", added.Code, added.Body.String())
	}

	duplicate := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/organizations/"+testOrganizationID+"/members",
		`{"subject_id":"SUB2","member_role":"招聘专员"}`, withSession(nil))
	if duplicate.Code != http.StatusConflict {
		t.Fatalf("duplicate status = %d, want 409", duplicate.Code)
	}

	removed := doRequest(handler, http.MethodDelete, "/crate-api/agora/v1/organizations/"+testOrganizationID+"/members/SUB2", "", withSession(nil))
	if removed.Code != http.StatusNoContent {
		t.Fatalf("remove status = %d, want 204: %s", removed.Code, removed.Body.String())
	}

	owner := doRequest(handler, http.MethodDelete, "/crate-api/agora/v1/organizations/"+testOrganizationID+"/members/SUB1", "", withSession(nil))
	if owner.Code != http.StatusConflict {
		t.Fatalf("owner removal status = %d, want 409", owner.Code)
	}
}

func TestListMyOrganizations(t *testing.T) {
	identity := newSessionProvider(t, "SUB1", false)
	defer identity.Close()
	orgs := newFakeOrganizations()
	orgs.byID[testOrganizationID] = organizations.Organization{ID: testOrganizationID, Name: "甲医院", Status: organizations.StatusPending}
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleOwner)
	handler := newDirectoryHandler(identity.URL, orgs, newFakePositions())

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/organizations/mine", "", withSession(nil))

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", response.Code, response.Body.String())
	}
	var body struct {
		Records []organizations.Membership `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Records) != 1 || body.Records[0].MemberRole != organizations.RoleOwner {
		t.Fatalf("records = %+v", body.Records)
	}
	if body.Meta.Total != 1 {
		t.Fatalf("meta.total = %d, want 1", body.Meta.Total)
	}
}
