package dispatch

import (
	"context"
	"errors"
	"testing"
)

// UpsertSession 按 run_id 幂等：首次插入、再次替换（不产生第二行）；
// GetSession/DeleteSession 按 run_id 定位；返回值为副本（外部修改不
// 影响存储）。
func TestInMemoryStoreSessionLifecycle(t *testing.T) {
	store := NewInMemoryStore()

	sessionA, err := normalizeSession("run-1", SessionInput{Mode: ModeRemote, JointVenues: []string{"场馆B"}}, fixedTime, "id-1")
	if err != nil {
		t.Fatalf("normalizeSession: %v", err)
	}
	if err := store.UpsertSession(context.Background(), sessionA); err != nil {
		t.Fatalf("first UpsertSession: %v", err)
	}
	sessionB, err := normalizeSession("run-1", SessionInput{Mode: ModeTabletop, JointVenues: []string{"场馆C"}}, fixedTime, "id-2")
	if err != nil {
		t.Fatalf("normalizeSession: %v", err)
	}
	if err := store.UpsertSession(context.Background(), sessionB); err != nil {
		t.Fatalf("second UpsertSession: %v", err)
	}

	fetched, err := store.GetSession(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetSession: %v", err)
	}
	if fetched.ID != "id-2" || fetched.Mode != ModeTabletop {
		t.Fatalf("upsert did not replace: %+v", fetched)
	}

	// 返回值为副本：外部修改切片/映射不影响存储。
	fetched.JointVenues[0] = "篡改"
	fetched.Metadata["x"] = 1
	again, err := store.GetSession(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetSession again: %v", err)
	}
	if again.Mode != ModeTabletop || len(again.JointVenues) != 1 || again.JointVenues[0] != "场馆C" || len(again.Metadata) != 0 {
		t.Fatalf("store leaked caller mutations: %+v", again)
	}

	if err := store.DeleteSession(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteSession: %v", err)
	}
	if _, err := store.GetSession(context.Background(), "run-1"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("GetSession after delete: err = %v, want ErrSessionNotFound", err)
	}
	if err := store.DeleteSession(context.Background(), "run-1"); !errors.Is(err, ErrSessionNotFound) {
		t.Fatalf("double delete: err = %v, want ErrSessionNotFound", err)
	}
}

// 不同 run 的会话互不影响；GetSession 按 run_id 而非 id 定位。
func TestInMemoryStoreSessionsAreKeyedByRun(t *testing.T) {
	store := NewInMemoryStore()
	modes := []Mode{ModeTabletop, ModeRemote}
	for index, runID := range []string{"run-1", "run-2"} {
		session, err := normalizeSession(runID, SessionInput{Mode: modes[index]}, fixedTime, "id-"+runID)
		if err != nil {
			t.Fatalf("normalizeSession: %v", err)
		}
		if err := store.UpsertSession(context.Background(), session); err != nil {
			t.Fatalf("UpsertSession %s: %v", runID, err)
		}
	}

	first, err := store.GetSession(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GetSession run-1: %v", err)
	}
	second, err := store.GetSession(context.Background(), "run-2")
	if err != nil {
		t.Fatalf("GetSession run-2: %v", err)
	}
	if first.ID == second.ID || first.Mode == second.Mode {
		t.Fatalf("sessions of different runs must be independent: %+v / %+v", first, second)
	}
}
