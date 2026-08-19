package dispatch

import (
	"context"
	"errors"
	"regexp"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// fixedTime is a deterministic clock for the normalize tests.
var fixedTime = time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)

// crockford26 matches a 26-character Crockford Base32 ULID (the alphabet
// omits I, L, O and U).
var crockford26 = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// fakeRunSource is a RunSource test double backed by a map of runs; a
// missing id answers ErrRunNotFound.
type fakeRunSource struct {
	runs map[string]drills.Run
}

func (f *fakeRunSource) GetRun(_ context.Context, id string) (drills.Run, error) {
	run, ok := f.runs[id]
	if !ok {
		return drills.Run{}, ErrRunNotFound
	}
	return run, nil
}

// newTestService builds a dispatch service over a fresh in-memory store
// and a run source holding the given runs.
func newTestService(runs ...drills.Run) (*Service, *InMemoryStore) {
	store := NewInMemoryStore()
	source := &fakeRunSource{runs: map[string]drills.Run{}}
	for _, run := range runs {
		source.runs[run.ID] = run
	}
	return NewService(store, source), store
}

func run(id string, status drills.RunStatus) drills.Run {
	return drills.Run{ID: id, Status: status}
}

// ─── UpsertSession ───────────────────────────────────────────────────

// 首次 PUT：完整对象，id 为 26 位 Crockford Base32 ULID，mode 缺省
// 实战演练、joint_venues 缺省 []、metadata 缺省 {}、main_venue/created_by
// 缺省空串，created_at/updated_at 为服务端时间且相等。
func TestUpsertSessionCreatesWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))

	session, err := service.UpsertSession(context.Background(), "run-1", SessionInput{})
	if err != nil {
		t.Fatalf("UpsertSession: %v", err)
	}
	if !crockford26.MatchString(session.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", session.ID)
	}
	if session.RunID != "run-1" {
		t.Fatalf("run_id = %q, want the run from the caller", session.RunID)
	}
	if session.Mode != DefaultMode {
		t.Fatalf("mode = %q, want %q", session.Mode, DefaultMode)
	}
	if session.MainVenue != "" || session.CreatedBy != "" {
		t.Fatalf("main_venue/created_by = %q / %q, want empty defaults", session.MainVenue, session.CreatedBy)
	}
	if session.JointVenues == nil || len(session.JointVenues) != 0 {
		t.Fatalf("joint_venues = %#v, want an empty array", session.JointVenues)
	}
	if session.Metadata == nil || len(session.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", session.Metadata)
	}
	if session.CreatedAt.IsZero() || !session.CreatedAt.Equal(session.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want server time and equal", session.CreatedAt, session.UpdatedAt)
	}
}

// 再次 PUT 原地更新：id/created_at 保留、updated_at 刷新，全量覆盖
// （缺省字段重置为默认值）。
func TestUpsertSessionUpdatesInPlace(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	created, err := service.UpsertSession(context.Background(), "run-1", SessionInput{
		Mode:        ModeRemote,
		MainVenue:   "主场馆A",
		JointVenues: []string{"联训场馆B"},
		Metadata:    map[string]any{"source": "merit"},
		CreatedBy:   "u-admin",
	})
	if err != nil {
		t.Fatalf("first UpsertSession: %v", err)
	}
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	updated, err := service.UpsertSession(context.Background(), "run-1", SessionInput{})
	if err != nil {
		t.Fatalf("second UpsertSession: %v", err)
	}
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at %v changed to %v on update", createdAt, updated.CreatedAt)
	}
	if updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at %v must be refreshed on update", updated.UpdatedAt)
	}
	if updated.Mode != DefaultMode || updated.MainVenue != "" || updated.CreatedBy != "" ||
		len(updated.JointVenues) != 0 || len(updated.Metadata) != 0 {
		t.Fatalf("full replacement semantics = %+v", updated)
	}
}

// run 不存在 → ErrRunNotFound（不写门控、不落库）。
func TestUpsertSessionRunNotFound(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusNotStarted))

	_, err := service.UpsertSession(context.Background(), "run-missing", SessionInput{})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("err = %v, want ErrRunNotFound", err)
	}
	if _, err := store.GetSession(context.Background(), "run-missing"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("store after failed upsert: err = %v, want ErrSessionNotFound", err)
	}
}

// 写门控：未开始/进行中 可写；已完成/已终止 → ValidationError。
func TestUpsertSessionWriteGate(t *testing.T) {
	for _, status := range []drills.RunStatus{drills.RunStatusCompleted, drills.RunStatusTerminated} {
		service, _ := newTestService(run("run-1", status))
		_, err := service.UpsertSession(context.Background(), "run-1", SessionInput{Mode: ModeTabletop})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", status, err)
		}
	}

	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusInProgress} {
		service, _ := newTestService(run("run-1", status))
		if _, err := service.UpsertSession(context.Background(), "run-1", SessionInput{}); err != nil {
			t.Fatalf("%s: UpsertSession: %v", status, err)
		}
	}
}

// ─── GetSession ──────────────────────────────────────────────────────

// 已配置 → 返回对象；run 不存在 → ErrRunNotFound；未配置 → ErrSessionNotFound；
// GET 不受写门控限制（已完成 run 已配置仍可读）。
func TestGetSession(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	if _, err := service.UpsertSession(context.Background(), "run-1", SessionInput{Mode: ModeLive}); err != nil {
		t.Fatalf("UpsertSession: %v", err)
	}

	session, err := service.GetSession(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetSession: %v", err)
	}
	if session.RunID != "run-1" || session.Mode != ModeLive {
		t.Fatalf("session = %+v, want the configured values", session)
	}

	_, err = service.GetSession(context.Background(), "run-missing")
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("run missing: err = %v, want ErrRunNotFound", err)
	}

	_, err = service.GetSession(context.Background(), "run-2")
	if !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("not configured: err = %v, want ErrSessionNotFound", err)
	}

	// 已完成 run 已配置 → 仍 200（GET 不写门控）：直接经 store 预置一条
	// 已配置 session，模拟 run 完成后配置仍可读。
	store := NewInMemoryStore()
	service2 := NewService(store, &fakeRunSource{runs: map[string]drills.Run{"run-3": run("run-3", drills.RunStatusCompleted)}})
	session2, err := normalizeSession("run-3", SessionInput{Mode: ModeTabletop}, fixedTime, "id-3")
	if err != nil {
		t.Fatalf("normalizeSession: %v", err)
	}
	if err := store.UpsertSession(context.Background(), session2); err != nil {
		t.Fatalf("store upsert: %v", err)
	}
	fetched, err := service2.GetSession(context.Background(), "run-3")
	if err != nil {
		t.Fatalf("GET on 已完成 with configured session: %v", err)
	}
	if fetched.Mode != ModeTabletop {
		t.Fatalf("fetched mode = %q, want 桌面推演", fetched.Mode)
	}
}

// ─── DeleteSession ───────────────────────────────────────────────────

// 成功删除；run 不存在 → ErrRunNotFound；未配置 → ErrSessionNotFound
// （先于写门控判定）；已完成/已终止 → ValidationError。
func TestDeleteSession(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusNotStarted))
	if _, err := service.UpsertSession(context.Background(), "run-1", SessionInput{}); err != nil {
		t.Fatalf("UpsertSession: %v", err)
	}

	if err := service.DeleteSession(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteSession: %v", err)
	}
	if _, err := store.GetSession(context.Background(), "run-1"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("store after delete: err = %v, want ErrSessionNotFound", err)
	}

	// run 不存在。
	service2, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	if err := service2.DeleteSession(context.Background(), "run-missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("run missing: err = %v, want ErrRunNotFound", err)
	}

	// 未配置 → 404（先于写门控：已完成 run 未配置仍 404 而非 400）。
	service3, _ := newTestService(run("run-2", drills.RunStatusCompleted))
	if err := service3.DeleteSession(context.Background(), "run-2"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("not configured: err = %v, want ErrSessionNotFound", err)
	}

	// 已完成/已终止 且已配置 → ValidationError。
	for _, status := range []drills.RunStatus{drills.RunStatusCompleted, drills.RunStatusTerminated} {
		store := NewInMemoryStore()
		source := &fakeRunSource{runs: map[string]drills.Run{"run-3": run("run-3", status)}}
		svc := NewService(store, source)
		// 服务不允许对已结束的 run 写，直接经 store 预置一条配置。
		session, err := normalizeSession("run-3", SessionInput{}, fixedTime, "id-3")
		if err != nil {
			t.Fatalf("normalizeSession: %v", err)
		}
		if err := store.UpsertSession(context.Background(), session); err != nil {
			t.Fatalf("store upsert: %v", err)
		}
		var validationError *ValidationError
		if err := svc.DeleteSession(context.Background(), "run-3"); !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", status, err)
		}
	}
}

// ─── 级联清理入口 ─────────────────────────────────────────────────────

// DeleteSessionsByRun 只删除该 run 的会话；删除不存在的 run 不是错误。
func TestStoreDeleteSessionsByRun(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted), run("run-2", drills.RunStatusNotStarted))
	if _, err := service.UpsertSession(context.Background(), "run-1", SessionInput{Mode: ModeTabletop}); err != nil {
		t.Fatalf("UpsertSession run-1: %v", err)
	}
	if _, err := service.UpsertSession(context.Background(), "run-2", SessionInput{Mode: ModeRemote}); err != nil {
		t.Fatalf("UpsertSession run-2: %v", err)
	}
	store := service.store.(*InMemoryStore)

	if err := store.DeleteSessionsByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteSessionsByRun: %v", err)
	}
	if _, err := store.GetSession(context.Background(), "run-1"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("run-1 after cascade: err = %v, want ErrSessionNotFound", err)
	}
	if session, err := store.GetSession(context.Background(), "run-2"); err != nil || session.Mode != ModeRemote {
		t.Fatalf("run-2 after cascade: session = %+v, err = %v; want untouched", session, err)
	}

	// 无会话可删不是错误。
	if err := store.DeleteSessionsByRun(context.Background(), "run-9"); err != nil {
		t.Fatalf("DeleteSessionsByRun on empty: %v", err)
	}
}
