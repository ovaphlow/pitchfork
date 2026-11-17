package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/agora-api/internal/apierr"
	"github.com/ovaphlow/pitchfork/agora-api/internal/identityclient"
	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
	"github.com/ovaphlow/pitchfork/agora-api/internal/positions"
)

// fakeOrganizations is an in-memory OrganizationStore. It records the last
// write so tests can assert on what the handler passed down.
type fakeOrganizations struct {
	byID       map[string]organizations.Organization
	roles      map[string]organizations.Role
	members    []organizations.Member
	lastCreate organizations.CreateInput
	lastOwner  string
	lastUpdate organizations.UpdateInput
	lastReview organizations.ReviewInput
	lastFilter organizations.Filter
	lastActor  string
}

func newFakeOrganizations() *fakeOrganizations {
	return &fakeOrganizations{
		byID:  map[string]organizations.Organization{},
		roles: map[string]organizations.Role{},
	}
}

func roleKey(organizationID, subjectID string) string { return organizationID + "/" + subjectID }

func (f *fakeOrganizations) grant(organizationID, subjectID string, role organizations.Role) {
	f.roles[roleKey(organizationID, subjectID)] = role
}

func (f *fakeOrganizations) Create(_ context.Context, input organizations.CreateInput, ownerSubjectID string) (organizations.Organization, error) {
	f.lastCreate = input
	f.lastOwner = ownerSubjectID
	organization := organizations.Organization{
		ID:             "01ARZ3NDEKTSV4RRFFQ69G5FAV",
		Name:           input.Name,
		OrgType:        input.OrgType,
		City:           input.City,
		Status:         organizations.StatusPending,
		OwnerSubjectID: ownerSubjectID,
		Metadata:       input.Metadata,
	}
	f.byID[organization.ID] = organization
	f.grant(organization.ID, ownerSubjectID, organizations.RoleOwner)
	return organization, nil
}

func (f *fakeOrganizations) Get(_ context.Context, organizationID string) (organizations.Organization, error) {
	organization, ok := f.byID[organizationID]
	if !ok {
		return organizations.Organization{}, &apierr.NotFound{Resource: "机构"}
	}
	return organization, nil
}

func (f *fakeOrganizations) Status(_ context.Context, organizationID string) (organizations.Status, error) {
	organization, ok := f.byID[organizationID]
	if !ok {
		return "", &apierr.NotFound{Resource: "机构"}
	}
	return organization.Status, nil
}

func (f *fakeOrganizations) Update(_ context.Context, organizationID string, input organizations.UpdateInput) (organizations.Organization, error) {
	f.lastUpdate = input
	organization, ok := f.byID[organizationID]
	if !ok {
		return organizations.Organization{}, &apierr.NotFound{Resource: "机构"}
	}
	if input.Name != nil {
		organization.Name = *input.Name
	}
	f.byID[organizationID] = organization
	return organization, nil
}

func (f *fakeOrganizations) Review(_ context.Context, organizationID string, input organizations.ReviewInput, reviewer string) (organizations.Organization, error) {
	f.lastReview = input
	f.lastActor = reviewer
	organization, ok := f.byID[organizationID]
	if !ok {
		return organizations.Organization{}, &apierr.NotFound{Resource: "机构"}
	}
	organization.Status = input.Status
	organization.RejectReason = input.Reason
	f.byID[organizationID] = organization
	return organization, nil
}

func (f *fakeOrganizations) List(_ context.Context, filter organizations.Filter) ([]organizations.Organization, int64, error) {
	f.lastFilter = filter
	status := filter.Status
	if status == "" && !filter.AllStatuses {
		status = organizations.StatusVerified
	}
	out := make([]organizations.Organization, 0, len(f.byID))
	for _, organization := range f.byID {
		if status != "" && organization.Status != status {
			continue
		}
		out = append(out, organization)
	}
	return out, int64(len(out)), nil
}

func (f *fakeOrganizations) ListForSubject(_ context.Context, subjectID string) ([]organizations.Membership, error) {
	out := make([]organizations.Membership, 0, 2)
	for key, role := range f.roles {
		organizationID, memberSubject, _ := strings.Cut(key, "/")
		if memberSubject != subjectID {
			continue
		}
		organization, ok := f.byID[organizationID]
		if !ok {
			continue
		}
		out = append(out, organizations.Membership{Organization: organization, MemberRole: role})
	}
	return out, nil
}

func (f *fakeOrganizations) Role(_ context.Context, organizationID, subjectID string) (organizations.Role, error) {
	role, ok := f.roles[roleKey(organizationID, subjectID)]
	if !ok {
		return "", organizations.ErrNotMember
	}
	return role, nil
}

func (f *fakeOrganizations) Members(_ context.Context, organizationID string) ([]organizations.Member, error) {
	out := make([]organizations.Member, 0, len(f.members))
	for _, member := range f.members {
		if member.OrganizationID == organizationID {
			out = append(out, member)
		}
	}
	return out, nil
}

func (f *fakeOrganizations) AddMember(_ context.Context, organizationID, subjectID string, role organizations.Role) (organizations.Member, error) {
	if _, ok := f.roles[roleKey(organizationID, subjectID)]; ok {
		return organizations.Member{}, &apierr.Conflict{Message: "该成员已在机构中"}
	}
	member := organizations.Member{
		OrganizationID: organizationID,
		SubjectID:      subjectID,
		MemberRole:     role,
		CreatedAt:      time.Now().UTC(),
		UpdatedAt:      time.Now().UTC(),
	}
	f.members = append(f.members, member)
	f.grant(organizationID, subjectID, role)
	return member, nil
}

func (f *fakeOrganizations) RemoveMember(_ context.Context, organizationID, subjectID string) error {
	role, ok := f.roles[roleKey(organizationID, subjectID)]
	if !ok {
		return &apierr.NotFound{Resource: "成员"}
	}
	if role == organizations.RoleOwner {
		return &apierr.Conflict{Message: "所有者不能被移除"}
	}
	delete(f.roles, roleKey(organizationID, subjectID))
	return nil
}

// fakePositions is an in-memory PositionStore.
type fakePositions struct {
	byID       map[string]positions.Position
	lastCreate positions.CreateInput
	lastActor  string
	lastStatus positions.Status
	lastUpdate positions.UpdateInput
	deleted    []string
	views      int64
}

func newFakePositions() *fakePositions {
	return &fakePositions{byID: map[string]positions.Position{}}
}

func (f *fakePositions) Create(_ context.Context, input positions.CreateInput, actorSubjectID string) (positions.Position, error) {
	f.lastCreate = input
	f.lastActor = actorSubjectID
	position := positions.Position{
		ID:             "01ARZ3NDEKTSV4RRFFQ69G5FAW",
		OrganizationID: input.OrganizationID,
		Title:          input.Title,
		Status:         positions.StatusDraft,
		CreatedBy:      actorSubjectID,
		Metadata:       input.Metadata,
	}
	f.byID[position.ID] = position
	return position, nil
}

func (f *fakePositions) Get(_ context.Context, positionID string) (positions.Position, error) {
	position, ok := f.byID[positionID]
	if !ok {
		return positions.Position{}, &apierr.NotFound{Resource: "岗位"}
	}
	return position, nil
}

func (f *fakePositions) IncreaseViewCount(_ context.Context, positionID string) (int64, error) {
	position, ok := f.byID[positionID]
	if !ok {
		return 0, &apierr.NotFound{Resource: "岗位"}
	}
	position.ViewCount++
	f.views = position.ViewCount
	f.byID[positionID] = position
	return position.ViewCount, nil
}

func (f *fakePositions) List(_ context.Context, filter positions.Filter) ([]positions.Position, int64, error) {
	out := make([]positions.Position, 0, len(f.byID))
	for _, position := range f.byID {
		if position.Status == positions.StatusOpen {
			out = append(out, position)
		}
	}
	return out, int64(len(out)), nil
}

func (f *fakePositions) ListByOrganization(_ context.Context, organizationID string, limit, offset int32) ([]positions.Position, int64, error) {
	out := make([]positions.Position, 0, len(f.byID))
	for _, position := range f.byID {
		if position.OrganizationID == organizationID {
			out = append(out, position)
		}
	}
	return out, int64(len(out)), nil
}

func (f *fakePositions) Update(_ context.Context, positionID string, input positions.UpdateInput) (positions.Position, error) {
	f.lastUpdate = input
	position, ok := f.byID[positionID]
	if !ok {
		return positions.Position{}, &apierr.NotFound{Resource: "岗位"}
	}
	if input.Title != nil {
		position.Title = *input.Title
	}
	f.byID[positionID] = position
	return position, nil
}

func (f *fakePositions) SetStatus(_ context.Context, positionID string, next positions.Status) (positions.Position, error) {
	f.lastStatus = next
	position, ok := f.byID[positionID]
	if !ok {
		return positions.Position{}, &apierr.NotFound{Resource: "岗位"}
	}
	position.Status = next
	if next == positions.StatusOpen && position.PublishedAt == nil {
		published := time.Now().UTC()
		position.PublishedAt = &published
	}
	f.byID[positionID] = position
	return position, nil
}

func (f *fakePositions) Delete(_ context.Context, positionID string) error {
	if _, ok := f.byID[positionID]; !ok {
		return &apierr.NotFound{Resource: "岗位"}
	}
	f.deleted = append(f.deleted, positionID)
	delete(f.byID, positionID)
	return nil
}

// newSessionProvider fakes platformd GET /session. It accepts only the
// "platformd_session=session-token" cookie, matching the auth tests.
func newSessionProvider(t *testing.T, subjectID string, platformAdmin bool) *httptest.Server {
	t.Helper()
	return httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet || r.URL.Path != "/crate-api/identity/v1/session" {
			http.NotFound(w, r)
			return
		}
		if !strings.Contains(r.Header.Get("Cookie"), identityclient.SessionCookieName+"=session-token") {
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"detail":"no session"}`))
			return
		}
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode(map[string]any{
			"subject_id":     subjectID,
			"display_name":   "测试用户",
			"access":         "完整",
			"platform_admin": platformAdmin,
		})
	}))
}

func newDirectoryHandler(identityURL string, orgs OrganizationStore, pos PositionStore) http.Handler {
	return New(Deps{
		Logger:           testLogger(),
		DB:               pingerFunc(func(context.Context) error { return nil }),
		AllowedOrigins:   []string{"http://localhost:4321"},
		ReadinessTimeout: time.Second,
		Identity:         identityclient.New(identityURL, time.Second),
		Organizations:    orgs,
		Positions:        pos,
	})
}

func withSession(extra map[string]string) map[string]string {
	headers := map[string]string{
		"Cookie":       identityclient.SessionCookieName + "=session-token",
		"Content-Type": "application/json",
	}
	for key, value := range extra {
		headers[key] = value
	}
	return headers
}

func decodeError(t *testing.T, body []byte) string {
	t.Helper()
	var payload struct {
		Error string `json:"error"`
	}
	if err := json.Unmarshal(body, &payload); err != nil {
		t.Fatalf("decode error body %s: %v", body, err)
	}
	return payload.Error
}

// emailLinkCall records one forwarded platformd email-link request.
type emailLinkCall struct {
	Email       string
	DisplayName string
	RedirectURI string
	Source      string
}

// fakeEmailIdentity mimics platformd's email link endpoints. platformd answers
// 202 for every address, so the fake only rejects the sentinel
// "invalid@example.com" with a 400 problem, and "token-abc" is the one token it
// exchanges successfully.
type fakeEmailIdentity struct {
	mutex     sync.Mutex
	links     []emailLinkCall
	exchanges []string
	callCount int
}

func newFakeEmailIdentity(t *testing.T) (*fakeEmailIdentity, *httptest.Server) {
	t.Helper()
	fake := &fakeEmailIdentity{}
	server := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fake.mutex.Lock()
		fake.callCount++
		fake.mutex.Unlock()

		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/crate-api/identity/v1/email-login-links":
			var body struct {
				Email       string `json:"email"`
				DisplayName string `json:"display_name"`
				RedirectURI string `json:"redirect_uri"`
			}
			if err := json.NewDecoder(r.Body).Decode(&body); err != nil {
				http.Error(w, "bad body", http.StatusBadRequest)
				return
			}
			if body.Email == "invalid@example.com" {
				w.Header().Set("Content-Type", "application/problem+json")
				w.WriteHeader(http.StatusBadRequest)
				_, _ = w.Write([]byte(`{"detail":"invalid email login link request"}`))
				return
			}
			fake.mutex.Lock()
			fake.links = append(fake.links, emailLinkCall{
				Email:       body.Email,
				DisplayName: body.DisplayName,
				RedirectURI: body.RedirectURI,
				Source:      r.Header.Get("X-Forwarded-For"),
			})
			fake.mutex.Unlock()
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusAccepted)
			_, _ = w.Write([]byte(`{"status":"accepted"}`))
		case r.Method == http.MethodPost && r.URL.Path == "/crate-api/identity/v1/email-login-links/token-abc/exchange":
			fake.mutex.Lock()
			fake.exchanges = append(fake.exchanges, r.URL.Path)
			fake.mutex.Unlock()
			http.SetCookie(w, &http.Cookie{Name: identityclient.SessionCookieName, Value: "session-token", Path: "/", HttpOnly: true, SameSite: http.SameSiteLaxMode})
			http.SetCookie(w, &http.Cookie{Name: identityclient.CSRFCookieName, Value: "csrf-token", Path: "/", SameSite: http.SameSiteLaxMode})
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"access":"完整"}`))
		case r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/exchange"):
			w.Header().Set("Content-Type", "application/problem+json")
			w.WriteHeader(http.StatusUnauthorized)
			_, _ = w.Write([]byte(`{"detail":"invalid or expired email login link"}`))
		case r.Method == http.MethodGet && r.URL.Path == "/crate-api/identity/v1/session":
			if !strings.Contains(r.Header.Get("Cookie"), identityclient.SessionCookieName+"=session-token") {
				w.Header().Set("Content-Type", "application/problem+json")
				w.WriteHeader(http.StatusUnauthorized)
				_, _ = w.Write([]byte(`{"detail":"not authenticated"}`))
				return
			}
			w.Header().Set("Content-Type", "application/json")
			_, _ = w.Write([]byte(`{"subject_id":"01ARZ3NDEKTSV4RRFFQ69G5FAV","display_name":"张三","access":"完整","platform_admin":false}`))
		default:
			http.NotFound(w, r)
		}
	}))
	return fake, server
}

func (fake *fakeEmailIdentity) linksSnapshot() []emailLinkCall {
	fake.mutex.Lock()
	defer fake.mutex.Unlock()
	return append([]emailLinkCall(nil), fake.links...)
}

func (fake *fakeEmailIdentity) exchangePaths() []string {
	fake.mutex.Lock()
	defer fake.mutex.Unlock()
	return append([]string(nil), fake.exchanges...)
}

func (fake *fakeEmailIdentity) calls() int {
	fake.mutex.Lock()
	defer fake.mutex.Unlock()
	return fake.callCount
}
