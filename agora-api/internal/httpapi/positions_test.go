package httpapi

import (
	"encoding/json"
	"net/http"
	"testing"

	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
	"github.com/ovaphlow/pitchfork/agora-api/internal/positions"
)

const testPositionID = "01ARZ3NDEKTSV4RRFFQ69G5FAW"

func newPositionFixture(t *testing.T) (*fakeOrganizations, *fakePositions, http.Handler, func()) {
	t.Helper()
	identity := newSessionProvider(t, "SUB1", false)
	orgs := newFakeOrganizations()
	orgs.byID[testOrganizationID] = organizations.Organization{
		ID:     testOrganizationID,
		Name:   "甲医院",
		Status: organizations.StatusVerified,
	}
	board := newFakePositions()
	return orgs, board, newDirectoryHandler(identity.URL, orgs, board), identity.Close
}

func TestListPositionsReturnsEnvelope(t *testing.T) {
	_, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Title:          "护士",
		Status:         positions.StatusOpen,
	}

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/positions?city=上海", "", nil)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", response.Code, response.Body.String())
	}
	var body struct {
		Records []positions.Position `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Records) != 1 || body.Meta.Total != 1 {
		t.Fatalf("records = %d, total = %d", len(body.Records), body.Meta.Total)
	}
}

func TestListPositionsRejectsBadPagination(t *testing.T) {
	_, _, handler, cleanup := newPositionFixture(t)
	defer cleanup()

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/positions?limit=0", "", nil)

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
}

func TestCreatePositionRequiresMembership(t *testing.T) {
	_, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()

	body := `{"organization_id":"` + testOrganizationID + `","title":"护士","salary_min":9000}`
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/positions", body, withSession(nil))

	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403: %s", response.Code, response.Body.String())
	}
	if board.lastCreate.Title != "" {
		t.Fatal("a forbidden request must not reach the store")
	}
}

func TestCreatePositionAsRecruiter(t *testing.T) {
	orgs, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleRecruiter)

	body := `{"organization_id":"` + testOrganizationID + `","title":" 护士 ",
		"salary_min":9000,"salary_max":12000,
		"metadata":{"description":"病房护理","tags":["急招"]}}`
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/positions", body, withSession(nil))

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201: %s", response.Code, response.Body.String())
	}
	if board.lastActor != "SUB1" {
		t.Fatalf("actor = %q, want SUB1", board.lastActor)
	}
	if board.lastCreate.Title != "护士" {
		t.Fatalf("title = %q, want it trimmed", board.lastCreate.Title)
	}
}

func TestCreatePositionAcceptsFormPayload(t *testing.T) {
	orgs, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleOwner)

	// Mirrors the payload the recruiter UI sends when publishing a role.
	body := `{"organization_id":"` + testOrganizationID + `","title":"护士",
		"employment_type":"全职","work_mode":"坐班","headcount":2,
		"salary_negotiable":false,"salary_min":9000,"salary_max":12000,"salary_unit":"月",
		"education":"大专","experience":"1-3年","city":"上海",
		"metadata":{"description":"病房护理","requirements":"持证上岗"}}`
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/positions", body, withSession(nil))

	if response.Code != http.StatusCreated {
		t.Fatalf("status = %d, want 201: %s", response.Code, response.Body.String())
	}
	created := board.lastCreate
	if created.EmploymentType != "全职" || created.WorkMode != "坐班" || created.Headcount != 2 {
		t.Fatalf("employment fields = %+v", created)
	}
	if created.SalaryMin == nil || *created.SalaryMin != 9000 || created.SalaryMax == nil || *created.SalaryMax != 12000 {
		t.Fatalf("salary = %v-%v", created.SalaryMin, created.SalaryMax)
	}
	if created.Education != "大专" || created.Experience != "1-3年" || created.City != "上海" {
		t.Fatalf("requirements = %+v", created)
	}
	if created.Metadata.Description != "病房护理" || created.Metadata.Requirements != "持证上岗" {
		t.Fatalf("metadata = %+v", created.Metadata)
	}
}

func TestCreatePositionRejectsUnusableOrganization(t *testing.T) {
	orgs, _, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.byID[testOrganizationID] = organizations.Organization{
		ID:     testOrganizationID,
		Name:   "甲医院",
		Status: organizations.StatusDisabled,
	}
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleOwner)

	body := `{"organization_id":"` + testOrganizationID + `","title":"护士","salary_min":9000}`
	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/positions", body, withSession(nil))

	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403: %s", response.Code, response.Body.String())
	}
	if message := decodeError(t, response.Body.Bytes()); message != "机构当前状态无法维护岗位" {
		t.Fatalf("error = %q", message)
	}
}

func TestPublishRequiresVerifiedOrganization(t *testing.T) {
	orgs, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.byID[testOrganizationID] = organizations.Organization{
		ID:     testOrganizationID,
		Name:   "甲医院",
		Status: organizations.StatusPending,
	}
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleOwner)
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusDraft,
	}

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/positions/"+testPositionID+"/status",
		`{"status":"招聘中"}`, withSession(nil))

	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403: %s", response.Code, response.Body.String())
	}
	if board.lastStatus != "" {
		t.Fatal("an unverified organisation must not reach the status update")
	}
}

func TestPublishRejectsIllegalTransition(t *testing.T) {
	orgs, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleOwner)
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusClosed,
	}

	response := doRequest(handler, http.MethodPost, "/crate-api/agora/v1/positions/"+testPositionID+"/status",
		`{"status":"招聘中"}`, withSession(nil))

	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400: %s", response.Code, response.Body.String())
	}
	if message := decodeError(t, response.Body.Bytes()); message != "岗位状态不能从「已关闭」变为「招聘中」" {
		t.Fatalf("error = %q", message)
	}
}

func TestGetDraftPositionIsHiddenFromStrangers(t *testing.T) {
	_, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusDraft,
	}

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/positions/"+testPositionID, "", nil)

	if response.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", response.Code)
	}
}

func TestGetPublicPositionCountsView(t *testing.T) {
	_, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusOpen,
	}

	response := doRequest(handler, http.MethodGet, "/crate-api/agora/v1/positions/"+testPositionID, "", nil)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", response.Code, response.Body.String())
	}
	var body positions.Position
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if body.ViewCount != 1 {
		t.Fatalf("view_count = %d, want 1", body.ViewCount)
	}
}

func TestDeletePositionRestrictsRecruitersToTheirOwnPostings(t *testing.T) {
	orgs, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleRecruiter)
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusDraft,
		CreatedBy:      "SUB9",
	}

	response := doRequest(handler, http.MethodDelete, "/crate-api/agora/v1/positions/"+testPositionID, "", withSession(nil))
	if response.Code != http.StatusForbidden {
		t.Fatalf("status = %d, want 403: %s", response.Code, response.Body.String())
	}

	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusDraft,
		CreatedBy:      "SUB1",
	}
	response = doRequest(handler, http.MethodDelete, "/crate-api/agora/v1/positions/"+testPositionID, "", withSession(nil))
	if response.Code != http.StatusNoContent {
		t.Fatalf("own posting status = %d, want 204: %s", response.Code, response.Body.String())
	}
	if len(board.deleted) != 1 {
		t.Fatalf("deleted = %v", board.deleted)
	}
}

func TestListOrganizationPositionsIncludesDrafts(t *testing.T) {
	orgs, board, handler, cleanup := newPositionFixture(t)
	defer cleanup()
	orgs.grant(testOrganizationID, "SUB1", organizations.RoleRecruiter)
	board.byID[testPositionID] = positions.Position{
		ID:             testPositionID,
		OrganizationID: testOrganizationID,
		Status:         positions.StatusDraft,
	}

	response := doRequest(handler, http.MethodGet,
		"/crate-api/agora/v1/organizations/"+testOrganizationID+"/positions", "", withSession(nil))

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200: %s", response.Code, response.Body.String())
	}
	var body struct {
		Records []positions.Position `json:"records"`
		Meta    struct {
			Total int64 `json:"total"`
		} `json:"meta"`
	}
	if err := json.Unmarshal(response.Body.Bytes(), &body); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(body.Records) != 1 || body.Records[0].Status != positions.StatusDraft {
		t.Fatalf("records = %+v", body.Records)
	}
}
