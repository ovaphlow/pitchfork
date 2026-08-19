package opinion

import (
	"context"
	"errors"
	"testing"
	"time"
)

// releaseAt builds a minimal release row with the given id, run and
// created timestamp for the store-level tests (the store persists rows
// as-is; the service owns the defaults).
func releaseAt(id, runID string, createdAt time.Time) Release {
	return Release{ID: id, RunID: runID, Title: "标题", Content: "内容", CreatedAt: createdAt, UpdatedAt: createdAt}
}

// ─── CreateRelease / GetRelease / UpdateRelease / DeleteRelease ──────

// 基本 CRUD：CreateRelease 追加、GetRelease 返回副本（改返回值不影响存
// 储）、UpdateRelease 按 (run, id) 原地替换、DeleteRelease 删除；不存在
// 时各自返回 ErrReleaseNotFound（含跨 run 的 id 视为不存在）。
func TestInMemoryStoreReleaseCRUD(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	if _, err := store.GetRelease(ctx, "run-1", "r-1"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("GetRelease on empty store: err = %v, want ErrReleaseNotFound", err)
	}
	if err := store.UpdateRelease(ctx, Release{ID: "r-1", RunID: "run-1"}); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("UpdateRelease on empty store: err = %v, want ErrReleaseNotFound", err)
	}
	if err := store.DeleteRelease(ctx, "run-1", "r-1"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("DeleteRelease on empty store: err = %v, want ErrReleaseNotFound", err)
	}

	created := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	first := Release{ID: "r-1", RunID: "run-1", Title: "A", Content: "a", PublishedAt: &created, Metadata: map[string]any{"k": "v"}, CreatedAt: created, UpdatedAt: created}
	if err := store.CreateRelease(ctx, first); err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	replacement := Release{ID: "r-1", RunID: "run-1", Title: "B", Content: "b", CreatedAt: created, UpdatedAt: created.Add(time.Hour)}
	if err := store.UpdateRelease(ctx, replacement); err != nil {
		t.Fatalf("UpdateRelease: %v", err)
	}
	got, err := store.GetRelease(ctx, "run-1", "r-1")
	if err != nil {
		t.Fatalf("GetRelease: %v", err)
	}
	if got.Title != "B" || got.Content != "b" || got.PublishedAt != nil || got.Metadata == nil || len(got.Metadata) != 0 {
		t.Fatalf("release = %+v, want the replacement", got)
	}

	// 返回副本：修改返回值不影响存储。
	got.Metadata = map[string]any{"mutated": true}
	again, err := store.GetRelease(ctx, "run-1", "r-1")
	if err != nil {
		t.Fatalf("GetRelease again: %v", err)
	}
	if len(again.Metadata) != 0 {
		t.Fatalf("store was mutated through the returned copy: %+v", again.Metadata)
	}

	// 跨 run 的 id 视为不存在。
	if _, err := store.GetRelease(ctx, "run-2", "r-1"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("cross-run GetRelease: err = %v, want ErrReleaseNotFound", err)
	}
	if err := store.DeleteRelease(ctx, "run-2", "r-1"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("cross-run DeleteRelease: err = %v, want ErrReleaseNotFound", err)
	}
	if err := store.DeleteRelease(ctx, "run-1", "r-1"); err != nil {
		t.Fatalf("DeleteRelease: %v", err)
	}
	if _, err := store.GetRelease(ctx, "run-1", "r-1"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("GetRelease after delete: err = %v, want ErrReleaseNotFound", err)
	}
}

// 克隆语义：PublishedAt 指针按值复制，修改返回值的指针不影响存储。
func TestInMemoryStoreClonesPublishedAt(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	created := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	if err := store.CreateRelease(ctx, Release{ID: "r-1", RunID: "run-1", Title: "T", Content: "C", PublishedAt: &created}); err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	got, err := store.GetRelease(ctx, "run-1", "r-1")
	if err != nil {
		t.Fatalf("GetRelease: %v", err)
	}
	*got.PublishedAt = time.Date(2020, 1, 1, 0, 0, 0, 0, time.UTC)
	again, err := store.GetRelease(ctx, "run-1", "r-1")
	if err != nil {
		t.Fatalf("GetRelease again: %v", err)
	}
	if again.PublishedAt == nil || !again.PublishedAt.Equal(created) {
		t.Fatalf("PublishedAt mutated through the returned copy: %v", again.PublishedAt)
	}
}

// ─── ListReleases ────────────────────────────────────────────────────

// 列表：按 run 隔离、筛选 channel/status 精确匹配、排序 created_at DESC
// 且 id DESC 决胜、分页生效、返回副本。
func TestInMemoryStoreListReleases(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)

	// 同一 run 三条，created_at 相同 → id DESC 决胜；另一 run 一条。
	rows := []Release{
		releaseAt("r-1", "run-1", base), // run-1，channel/status 缺省
		releaseAt("r-2", "run-1", base),
		releaseAt("r-3", "run-1", base),
		releaseAt("rx", "run-2", base),
	}
	for _, row := range rows {
		if err := store.CreateRelease(ctx, row); err != nil {
			t.Fatalf("CreateRelease: %v", err)
		}
	}

	// 全部（含缺省 channel/status 匹配与另一 run 隔离）。
	all, total, err := store.ListReleases(ctx, "run-1", ReleaseFilter{Limit: 100})
	if err != nil {
		t.Fatalf("ListReleases: %v", err)
	}
	if total != 3 || len(all) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", total, len(all))
	}
	wantOrder := []string{"r-3", "r-2", "r-1"}
	for i, want := range wantOrder {
		if all[i].ID != want {
			t.Fatalf("records[%d] = %q, want %q (created_at DESC, id DESC)", i, all[i].ID, want)
		}
	}

	// 分页：limit 2 取前两条（r-3, r-2），total 保持 3；offset 2 取 r-1。
	page, total, err := store.ListReleases(ctx, "run-1", ReleaseFilter{Limit: 2})
	if err != nil || total != 3 || len(page) != 2 || page[0].ID != "r-3" || page[1].ID != "r-2" {
		t.Fatalf("limit page = %+v, total = %d, err = %v", page, total, err)
	}
	page, total, err = store.ListReleases(ctx, "run-1", ReleaseFilter{Limit: 2, Offset: 2})
	if err != nil || total != 3 || len(page) != 1 || page[0].ID != "r-1" {
		t.Fatalf("offset page = %+v, total = %d, err = %v", page, total, err)
	}

	// 筛选 channel 与 status（可单参、可组合）：无匹配返回空页。
	filtered, total, err := store.ListReleases(ctx, "run-1", ReleaseFilter{Channel: ChannelNewsRelease, Limit: 100})
	if err != nil || total != 0 || len(filtered) != 0 {
		t.Fatalf("channel filter = %+v, total = %d, err = %v; want empty", filtered, total, err)
	}
	if err := store.CreateRelease(ctx, Release{ID: "r-4", RunID: "run-1", Channel: ChannelNewsRelease, Status: ReleaseStatusPending, Title: "T", Content: "C", CreatedAt: base}); err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	filtered, total, err = store.ListReleases(ctx, "run-1", ReleaseFilter{Channel: ChannelNewsRelease, Limit: 100})
	if err != nil || total != 1 || len(filtered) != 1 || filtered[0].ID != "r-4" {
		t.Fatalf("channel filter = %+v, total = %d, err = %v; want r-4", filtered, total, err)
	}
	filtered, total, err = store.ListReleases(ctx, "run-1", ReleaseFilter{Status: ReleaseStatusPending, Limit: 100})
	if err != nil || total != 1 || filtered[0].ID != "r-4" {
		t.Fatalf("status filter = %+v, total = %d, err = %v; want r-4", filtered, total, err)
	}
	filtered, total, err = store.ListReleases(ctx, "run-1", ReleaseFilter{Channel: ChannelNewsRelease, Status: ReleaseStatusDraft, Limit: 100})
	if err != nil || total != 0 || len(filtered) != 0 {
		t.Fatalf("combined filter = %+v, total = %d, err = %v; want empty", filtered, total, err)
	}

	// 返回副本：修改页面元素不影响存储。
	all, _, err = store.ListReleases(ctx, "run-1", ReleaseFilter{Limit: 100})
	if err != nil {
		t.Fatalf("ListReleases: %v", err)
	}
	all[0].Metadata = map[string]any{"mutated": true}
	again, _, err := store.ListReleases(ctx, "run-1", ReleaseFilter{Limit: 100})
	if err != nil {
		t.Fatalf("ListReleases again: %v", err)
	}
	for _, item := range again {
		if len(item.Metadata) != 0 {
			t.Fatalf("store was mutated through the returned page: %+v", item.Metadata)
		}
	}
}

// ─── DeleteByRun：级联清理 ───────────────────────────────────────────

// DeleteByRun 同时清空该 run 的 opinion_events、opinion_posts 与
// opinion_releases，其他 run 的对象保留；无对象可清时不是错误。
func TestInMemoryStoreDeleteByRunCleansReleases(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for _, runID := range []string{"run-1", "run-2"} {
		if err := store.UpsertEvent(ctx, Event{RunID: runID, EventName: "E"}); err != nil {
			t.Fatalf("UpsertEvent(%s): %v", runID, err)
		}
		if err := store.CreatePost(ctx, Post{ID: "p-" + runID, RunID: runID, Content: "C"}); err != nil {
			t.Fatalf("CreatePost(%s): %v", runID, err)
		}
		if err := store.CreateRelease(ctx, Release{ID: "r-" + runID, RunID: runID, Title: "T", Content: "C"}); err != nil {
			t.Fatalf("CreateRelease(%s): %v", runID, err)
		}
	}
	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if _, err := store.GetEvent(ctx, "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("event of run-1: err = %v, want ErrEventNotFound", err)
	}
	if _, err := store.GetPost(ctx, "run-1", "p-run-1"); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("post of run-1: err = %v, want ErrPostNotFound", err)
	}
	if _, err := store.GetRelease(ctx, "run-1", "r-run-1"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("release of run-1: err = %v, want ErrReleaseNotFound", err)
	}
	// run-2 的对象原样保留。
	if event, err := store.GetEvent(ctx, "run-2"); err != nil || event.EventName != "E" {
		t.Fatalf("event of run-2: event = %+v, err = %v; want the untouched event", event, err)
	}
	if post, err := store.GetPost(ctx, "run-2", "p-run-2"); err != nil || post.Content != "C" {
		t.Fatalf("post of run-2: post = %+v, err = %v; want the untouched post", post, err)
	}
	if release, err := store.GetRelease(ctx, "run-2", "r-run-2"); err != nil || release.Title != "T" {
		t.Fatalf("release of run-2: release = %+v, err = %v; want the untouched release", release, err)
	}
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
