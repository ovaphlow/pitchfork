//go:build integration

// Package integration exercises the stores against a throwaway PostgreSQL
// instance. It is excluded from the default build; run it with
//
//	AGORA_TEST_DATABASE_URL=postgres://check:check@127.0.0.1:55432/check?sslmode=disable \
//	  go test -tags integration ./internal/integration/
//
// Never point it at a shared development or business database: the suite
// truncates the business tables.
package integration_test

import (
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/jackc/pgx/v5/pgxpool"

	"github.com/ovaphlow/pitchfork/agora-api/internal/database"
	"github.com/ovaphlow/pitchfork/agora-api/internal/migrate"
	"github.com/ovaphlow/pitchfork/agora-api/internal/intents"
	"github.com/ovaphlow/pitchfork/agora-api/internal/organizations"
	"github.com/ovaphlow/pitchfork/agora-api/internal/positions"
	"github.com/ovaphlow/pitchfork/agora-api/migrations"
)

const (
	ownerSubject     = "01ARZ3NDEKTSV4RRFFQ69G5FAV"
	recruiterSubject = "01ARZ3NDEKTSV4RRFFQ69G5FAW"
	strangerSubject  = "01ARZ3NDEKTSV4RRFFQ69G5FAX"
)

func openPool(t *testing.T) *pgxpool.Pool {
	t.Helper()

	dsn := os.Getenv("AGORA_TEST_DATABASE_URL")
	if dsn == "" {
		t.Skip("AGORA_TEST_DATABASE_URL is not set")
	}

	pool, err := database.Open(context.Background(), database.Config{
		URL:            dsn,
		MaxConns:       4,
		ConnectTimeout: 5 * time.Second,
	})
	if err != nil {
		t.Fatalf("connect: %v", err)
	}
	t.Cleanup(pool.Close)

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if err := migrate.Run(context.Background(), pool, migrations.FS, logger); err != nil {
		t.Fatalf("migrate: %v", err)
	}
	// Running twice must be a no-op.
	if err := migrate.Run(context.Background(), pool, migrations.FS, logger); err != nil {
		t.Fatalf("migrate is not idempotent: %v", err)
	}
	return pool
}

func reset(t *testing.T, pool *pgxpool.Pool) {
	t.Helper()
	if _, err := pool.Exec(context.Background(),
		`TRUNCATE subject_intents, job_positions, organization_members, organizations`); err != nil {
		t.Fatalf("truncate: %v", err)
	}
}

func intPointer(value int) *int { return &value }

func TestOrganizationLifecycle(t *testing.T) {
	pool := openPool(t)
	reset(t, pool)
	ctx := context.Background()
	store := organizations.NewStore(pool)

	created, err := createOrganization(ctx, store, organizations.CreateInput{
		Name:     "甲医院",
		OrgType:  "医院",
		City:     "上海",
		District: "徐汇区",
		Metadata: organizations.Metadata{Industry: "医疗健康", Tags: []string{"双休"}},
	}, ownerSubject)
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if created.Status != organizations.StatusPending {
		t.Fatalf("status = %q, want 待审核", created.Status)
	}
	if len(created.ID) != 26 {
		t.Fatalf("id = %q, want a 26-character ULID", created.ID)
	}
	if created.Metadata.Industry != "医疗健康" {
		t.Fatalf("metadata = %+v", created.Metadata)
	}

	// Pending organisations only show up in an explicit status listing.
	if _, total, err := store.List(ctx, organizations.Filter{Limit: 20}); err != nil || total != 0 {
		t.Fatalf("public listing leaked a pending organisation: %d, %v", total, err)
	}
	pending, total, err := store.List(ctx, organizations.Filter{Status: organizations.StatusPending, Limit: 20})
	if err != nil || total != 1 || len(pending) != 1 || pending[0].ID != created.ID {
		t.Fatalf("pending listing = %d, %v", total, err)
	}

	role, err := store.Role(ctx, created.ID, ownerSubject)
	if err != nil || role != organizations.RoleOwner {
		t.Fatalf("Role = %q, %v", role, err)
	}

	if _, err := createOrganization(ctx, store, organizations.CreateInput{Name: "甲医院", City: "上海"}, strangerSubject); err == nil {
		t.Fatal("a duplicate name+city must conflict")
	}

	member, err := store.AddMember(ctx, created.ID, recruiterSubject, organizations.RoleRecruiter)
	if err != nil {
		t.Fatalf("AddMember: %v", err)
	}
	if member.MemberRole != organizations.RoleRecruiter {
		t.Fatalf("role = %q", member.MemberRole)
	}

	if _, err := store.AddMember(ctx, created.ID, recruiterSubject, organizations.RoleAdmin); err == nil {
		t.Fatal("adding the same member twice must conflict")
	}

	// The partial unique index keeps exactly one owner per organisation.
	if _, err := store.AddMember(ctx, created.ID, strangerSubject, organizations.RoleOwner); err == nil {
		t.Fatal("a second owner must be rejected")
	}

	members, err := store.Members(ctx, created.ID)
	if err != nil {
		t.Fatalf("Members: %v", err)
	}
	if len(members) != 2 || members[0].MemberRole != organizations.RoleOwner {
		t.Fatalf("members = %+v", members)
	}

	updated, err := store.Update(ctx, created.ID, organizations.UpdateInput{ShortName: stringPointer("甲院")})
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if updated.ShortName != "甲院" || updated.Name != "甲医院" {
		t.Fatalf("update did not merge: %+v", updated)
	}

	listed, total, err := store.List(ctx, organizations.Filter{Limit: 20})
	if err != nil || total != 0 || len(listed) != 0 {
		t.Fatalf("unverified organisations must not be listed: %d, %v", total, err)
	}

	if _, err := store.Review(ctx, created.ID, organizations.ReviewInput{Status: organizations.StatusVerified}, strangerSubject); err != nil {
		t.Fatalf("Review: %v", err)
	}
	listed, total, err = store.List(ctx, organizations.Filter{Limit: 20})
	if err != nil || total != 1 || len(listed) != 1 {
		t.Fatalf("verified organisation was not listed: %d, %v", total, err)
	}

	byCity, total, err := store.List(ctx, organizations.Filter{City: "上海", Limit: 20})
	if err != nil || total != 1 || len(byCity) != 1 {
		t.Fatalf("city filter = %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, organizations.Filter{City: "北京", Limit: 20}); err != nil || total != 0 {
		t.Fatalf("city filter leaked: %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, organizations.Filter{Query: "医疗", Limit: 20}); err != nil || total != 1 {
		t.Fatalf("metadata industry search failed: %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, organizations.Filter{OrgType: "医院", Limit: 20}); err != nil || total != 1 {
		t.Fatalf("org_type filter failed: %d, %v", total, err)
	}
	if page, total, err := store.List(ctx, organizations.Filter{Limit: 1, Offset: 5}); err != nil || total != 1 || len(page) != 0 {
		t.Fatalf("offset paging = %d records, total %d, %v", len(page), total, err)
	}

	memberships, err := store.ListForSubject(ctx, recruiterSubject)
	if err != nil || len(memberships) != 1 {
		t.Fatalf("ListForSubject = %d, %v", len(memberships), err)
	}

	if err := store.RemoveMember(ctx, created.ID, ownerSubject); err == nil {
		t.Fatal("the owner must not be removable")
	}
	if err := store.RemoveMember(ctx, created.ID, recruiterSubject); err != nil {
		t.Fatalf("RemoveMember: %v", err)
	}
}

func TestPositionLifecycle(t *testing.T) {
	pool := openPool(t)
	reset(t, pool)
	ctx := context.Background()
	orgStore := organizations.NewStore(pool)
	store := positions.NewStore(pool)

	organization, err := createOrganization(ctx, orgStore, organizations.CreateInput{Name: "乙学校", City: "杭州"}, ownerSubject)
	if err != nil {
		t.Fatalf("create organisation: %v", err)
	}
	if _, err := orgStore.Review(ctx, organization.ID, organizations.ReviewInput{Status: organizations.StatusVerified}, strangerSubject); err != nil {
		t.Fatalf("review: %v", err)
	}

	draftInput, err := (positions.CreateInput{
		OrganizationID:   organization.ID,
		Title:            "护理教师",
		EmploymentType:   "全职",
		WorkMode:         "坐班",
		SalaryMin:        intPointer(9000),
		SalaryMax:        intPointer(12000),
		SalaryUnit:       "月",
		Education:        "本科",
		Experience:       "1-3年",
		City:             "杭州",
		SalaryNegotiable: false,
		Metadata: positions.Metadata{
			Description: "讲授护理课程",
			Apply:       positions.Apply{Method: "email", Value: "hr@example.com"},
		},
	}).Validate()
	if err != nil {
		t.Fatalf("validate position: %v", err)
	}
	draft, err := store.Create(ctx, draftInput, ownerSubject)
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	if draft.Status != positions.StatusDraft || draft.PublishedAt != nil {
		t.Fatalf("draft = %+v", draft)
	}
	if draft.OrganizationName != "乙学校" {
		t.Fatalf("organisation name = %q", draft.OrganizationName)
	}

	if _, total, err := store.List(ctx, positions.Filter{Limit: 20}); err != nil || total != 0 {
		t.Fatalf("drafts must not be listed publicly: %d, %v", total, err)
	}

	published, err := store.SetStatus(ctx, draft.ID, positions.StatusOpen)
	if err != nil {
		t.Fatalf("SetStatus: %v", err)
	}
	if published.PublishedAt == nil {
		t.Fatal("publishing must stamp published_at")
	}

	listed, total, err := store.List(ctx, positions.Filter{City: "杭州", Limit: 20})
	if err != nil || total != 1 || len(listed) != 1 {
		t.Fatalf("published posting was not listed: %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, positions.Filter{City: "上海", Limit: 20}); err != nil || total != 0 {
		t.Fatalf("city filter leaked: %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, positions.Filter{MinimumSalary: 11000, Limit: 20}); err != nil || total != 1 {
		t.Fatalf("salary filter should match the upper bound: %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, positions.Filter{MinimumSalary: 50000, Limit: 20}); err != nil || total != 0 {
		t.Fatalf("salary filter should exclude low ranges: %d, %v", total, err)
	}
	if _, total, err := store.List(ctx, positions.Filter{Query: "护理", Limit: 20}); err != nil || total != 1 {
		t.Fatalf("full-text filter failed: %d, %v", total, err)
	}

	views, err := store.IncreaseViewCount(ctx, draft.ID)
	if err != nil || views != 1 {
		t.Fatalf("IncreaseViewCount = %d, %v", views, err)
	}

	// A patch that sends only the upper bound must keep the lower bound.
	updated, err := store.Update(ctx, draft.ID, positions.UpdateInput{SalaryMax: intPointer(15000)})
	if err != nil {
		t.Fatalf("Update: %v", err)
	}
	if updated.SalaryMin == nil || *updated.SalaryMin != 9000 || updated.SalaryMax == nil || *updated.SalaryMax != 15000 {
		t.Fatalf("salary = %v..%v", updated.SalaryMin, updated.SalaryMax)
	}

	// Switching to 面议 clears the range.
	negotiable := true
	updated, err = store.Update(ctx, draft.ID, positions.UpdateInput{SalaryNegotiable: &negotiable})
	if err != nil {
		t.Fatalf("Update to negotiable: %v", err)
	}
	if updated.SalaryMin != nil || updated.SalaryMax != nil {
		t.Fatalf("range was not cleared: %v..%v", updated.SalaryMin, updated.SalaryMax)
	}

	// Metadata replacement must round-trip.
	metadata, err := (positions.Metadata{Description: "更新后的职责", Tags: []string{"急招"}}).Validate()
	if err != nil {
		t.Fatalf("validate metadata: %v", err)
	}
	updated, err = store.Update(ctx, draft.ID, positions.UpdateInput{Metadata: &metadata})
	if err != nil {
		t.Fatalf("Update metadata: %v", err)
	}
	if updated.Metadata.Description != "更新后的职责" {
		t.Fatalf("metadata = %+v", updated.Metadata)
	}

	if _, err := store.Update(ctx, draft.ID, positions.UpdateInput{SalaryMin: intPointer(0), SalaryMax: intPointer(0)}); err != nil {
		t.Fatalf("zero range should be allowed once priced: %v", err)
	}

	owned, total, err := store.ListByOrganization(ctx, organization.ID, 20, 0)
	if err != nil || total != 1 || len(owned) != 1 {
		t.Fatalf("ListByOrganization = %d, %v", total, err)
	}

	if err := store.Delete(ctx, draft.ID); err != nil {
		t.Fatalf("Delete: %v", err)
	}
	if _, err := store.Get(ctx, draft.ID); err == nil {
		t.Fatal("a deleted posting must not be readable")
	}
}

func TestPositionRecruitmentWindow(t *testing.T) {
	pool := openPool(t)
	reset(t, pool)
	ctx := context.Background()
	orgStore := organizations.NewStore(pool)
	store := positions.NewStore(pool)

	organization, err := createOrganization(ctx, orgStore, organizations.CreateInput{Name: "丁医院", City: "成都"}, ownerSubject)
	if err != nil {
		t.Fatalf("create organisation: %v", err)
	}
	if _, err := orgStore.Review(ctx, organization.ID, organizations.ReviewInput{Status: organizations.StatusVerified}, strangerSubject); err != nil {
		t.Fatalf("review: %v", err)
	}

	draftInput, err := (positions.CreateInput{
		OrganizationID: organization.ID,
		Title:          "夜班护士",
		SalaryMin:      intPointer(8000),
	}).Validate()
	if err != nil {
		t.Fatalf("validate position: %v", err)
	}
	draft, err := store.Create(ctx, draftInput, ownerSubject)
	if err != nil {
		t.Fatalf("Create: %v", err)
	}

	published, err := store.SetStatus(ctx, draft.ID, positions.StatusOpen)
	if err != nil {
		t.Fatalf("SetStatus open: %v", err)
	}
	if published.OpenUntil == nil {
		t.Fatal("publishing must open a recruitment window")
	}
	if left := time.Until(*published.OpenUntil); left < 13*24*time.Hour || left > 14*24*time.Hour {
		t.Fatalf("first window = %v, want about 14 days", left)
	}

	// Move the window into the past: the next read stops the posting.
	if _, err := pool.Exec(ctx,
		`UPDATE job_positions SET open_until = NOW() - INTERVAL '1 minute' WHERE id = $1`, draft.ID); err != nil {
		t.Fatalf("expire window: %v", err)
	}
	stopped, err := store.Get(ctx, draft.ID)
	if err != nil {
		t.Fatalf("Get: %v", err)
	}
	if stopped.Status != positions.StatusStopped {
		t.Fatalf("status = %q, want 已停招", stopped.Status)
	}
	if _, total, err := store.List(ctx, positions.Filter{Limit: 20}); err != nil || total != 0 {
		t.Fatalf("stopped posting must leave the feed: %d, %v", total, err)
	}

	restored, err := store.SetStatus(ctx, draft.ID, positions.StatusOpen)
	if err != nil {
		t.Fatalf("SetStatus restore: %v", err)
	}
	if restored.Status != positions.StatusOpen || restored.OpenUntil == nil {
		t.Fatalf("restored = %+v", restored)
	}
	if left := time.Until(*restored.OpenUntil); left < 6*24*time.Hour || left > 7*24*time.Hour {
		t.Fatalf("restored window = %v, want about 7 days", left)
	}
	if _, total, err := store.List(ctx, positions.Filter{Limit: 20}); err != nil || total != 1 {
		t.Fatalf("restored posting must return to the feed: %d, %v", total, err)
	}

	closed, err := store.SetStatus(ctx, draft.ID, positions.StatusClosed)
	if err != nil {
		t.Fatalf("SetStatus close: %v", err)
	}
	if closed.OpenUntil != nil {
		t.Fatalf("closed posting kept open_until = %v", closed.OpenUntil)
	}

	reopened, err := store.SetStatus(ctx, draft.ID, positions.StatusOpen)
	if err != nil {
		t.Fatalf("SetStatus reopen closed: %v", err)
	}
	if reopened.Status != positions.StatusOpen || reopened.OpenUntil == nil {
		t.Fatalf("reopened = %+v", reopened)
	}
	if left := time.Until(*reopened.OpenUntil); left < 6*24*time.Hour || left > 7*24*time.Hour {
		t.Fatalf("reopened window = %v, want about 7 days", left)
	}
}

func TestPositionConstraints(t *testing.T) {
	pool := openPool(t)
	reset(t, pool)
	ctx := context.Background()
	orgStore := organizations.NewStore(pool)
	store := positions.NewStore(pool)

	organization, err := createOrganization(ctx, orgStore, organizations.CreateInput{Name: "丙公司", City: "北京"}, ownerSubject)
	if err != nil {
		t.Fatalf("create organisation: %v", err)
	}

	// The database rejects a priced posting without a lower bound, so the store
	// would surface a constraint error rather than storing nonsense.
	if _, err := pool.Exec(ctx, `
		INSERT INTO job_positions (id, organization_id, title, created_by)
		VALUES ('J1', $1, '测试', $2)`, organization.ID, ownerSubject); err == nil {
		t.Fatal("a priced posting without salary_min must violate the CHECK constraint")
	}

	if _, err := pool.Exec(ctx, `
		INSERT INTO job_positions (id, organization_id, title, created_by, status)
		VALUES ('J2', $1, '测试', $2, '招聘中')`, organization.ID, ownerSubject); err == nil {
		t.Fatal("出版中 without published_at must violate the CHECK constraint")
	}

	if _, err := pool.Exec(ctx, `
		INSERT INTO job_positions (id, organization_id, title, created_by, metadata)
		VALUES ('J3', $1, '测试', $2, '[]'::jsonb)`, organization.ID, ownerSubject); err == nil {
		t.Fatal("a non-object metadata must violate the CHECK constraint")
	}

	negotiableInput, err := (positions.CreateInput{
		OrganizationID:   organization.ID,
		Title:            "测试",
		SalaryNegotiable: true,
	}).Validate()
	if err != nil {
		t.Fatalf("validate position: %v", err)
	}
	if _, err := store.Create(ctx, negotiableInput, ownerSubject); err != nil {
		t.Fatalf("Create with a negotiable salary must succeed: %v", err)
	}
}

func TestSubjectIntentsPersistLastChoice(t *testing.T) {
	pool := openPool(t)
	reset(t, pool)
	ctx := context.Background()
	store := intents.NewStore(pool)

	if err := store.Set(ctx, ownerSubject, intents.JobSeeker); err != nil {
		t.Fatalf("Set: %v", err)
	}
	values, err := store.BySubjects(ctx, []string{ownerSubject, strangerSubject})
	if err != nil {
		t.Fatalf("BySubjects: %v", err)
	}
	if len(values) != 1 || values[ownerSubject] != intents.JobSeeker {
		t.Fatalf("values = %v", values)
	}

	if err := store.Ensure(ctx, strangerSubject); err != nil {
		t.Fatalf("Ensure: %v", err)
	}
	ids, total, err := store.List(ctx, 10, 0)
	if err != nil {
		t.Fatalf("List: %v", err)
	}
	if total != 2 || len(ids) != 2 {
		t.Fatalf("registry = %v, total = %d", ids, total)
	}
	values, err = store.BySubjects(ctx, []string{ownerSubject, strangerSubject})
	if err != nil {
		t.Fatalf("BySubjects after Ensure: %v", err)
	}
	if len(values) != 1 || values[ownerSubject] != intents.JobSeeker {
		t.Fatalf("untyped registry row leaked a type: %v", values)
	}

	if err := store.Set(ctx, ownerSubject, intents.Recruiter); err != nil {
		t.Fatalf("Set update: %v", err)
	}
	values, err = store.BySubjects(ctx, []string{ownerSubject})
	if err != nil {
		t.Fatalf("BySubjects after update: %v", err)
	}
	if values[ownerSubject] != intents.Recruiter {
		t.Fatalf("updated value = %v", values)
	}
}

func stringPointer(value string) *string { return &value }

// createOrganization mirrors the handler: validate before persisting.
func createOrganization(ctx context.Context, store *organizations.Store, input organizations.CreateInput, owner string) (organizations.Organization, error) {
	input, err := input.Validate()
	if err != nil {
		return organizations.Organization{}, err
	}
	return store.Create(ctx, input, owner)
}
