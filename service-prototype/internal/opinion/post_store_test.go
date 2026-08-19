package opinion

import (
	"context"
	"errors"
	"testing"
	"time"
)

// postAt builds a minimal post row with the given id, run and created
// timestamp for the store-level tests (the store persists rows as-is;
// the service owns the defaults).
func postAt(id, runID string, createdAt time.Time) Post {
	return Post{ID: id, RunID: runID, Content: "内容", CreatedAt: createdAt, UpdatedAt: createdAt}
}

// ─── CreatePost / GetPost / UpdatePost / DeletePost ──────────────────

// 基本 CRUD：CreatePost 追加、GetPost 返回副本（改返回值不影响存储）、
// UpdatePost 按 (run, id) 原地替换、DeletePost 删除；不存在时各自返回
// ErrPostNotFound（含跨 run 的 id 视为不存在）。
func TestInMemoryStorePostCRUD(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	if _, err := store.GetPost(ctx, "run-1", "p-1"); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("GetPost on empty store: err = %v, want ErrPostNotFound", err)
	}
	if err := store.UpdatePost(ctx, Post{ID: "p-1", RunID: "run-1"}); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("UpdatePost on empty store: err = %v, want ErrPostNotFound", err)
	}
	if err := store.DeletePost(ctx, "run-1", "p-1"); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("DeletePost on empty store: err = %v, want ErrPostNotFound", err)
	}

	created := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	first := Post{ID: "p-1", RunID: "run-1", Content: "A", WarnedAt: &created, Metadata: map[string]any{"k": "v"}, CreatedAt: created, UpdatedAt: created}
	if err := store.CreatePost(ctx, first); err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	replacement := Post{ID: "p-1", RunID: "run-1", Content: "B", CreatedAt: created, UpdatedAt: created.Add(time.Hour)}
	if err := store.UpdatePost(ctx, replacement); err != nil {
		t.Fatalf("UpdatePost: %v", err)
	}
	got, err := store.GetPost(ctx, "run-1", "p-1")
	if err != nil {
		t.Fatalf("GetPost: %v", err)
	}
	if got.Content != "B" || got.WarnedAt != nil || got.Metadata == nil || len(got.Metadata) != 0 {
		t.Fatalf("post = %+v, want the replacement", got)
	}

	// 返回副本：修改返回值不影响存储。
	got.Metadata = map[string]any{"mutated": true}
	again, err := store.GetPost(ctx, "run-1", "p-1")
	if err != nil {
		t.Fatalf("GetPost again: %v", err)
	}
	if len(again.Metadata) != 0 {
		t.Fatalf("store was mutated through the returned copy: %+v", again.Metadata)
	}

	// 跨 run 的 id 视为不存在。
	if _, err := store.GetPost(ctx, "run-2", "p-1"); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("GetPost of another run: err = %v, want ErrPostNotFound", err)
	}

	if err := store.DeletePost(ctx, "run-1", "p-1"); err != nil {
		t.Fatalf("DeletePost: %v", err)
	}
	if _, err := store.GetPost(ctx, "run-1", "p-1"); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("GetPost after delete: err = %v, want ErrPostNotFound", err)
	}
}

// 存储副本深度复制 warned_at 指针：修改返回值指针指向的值不影响存储。
func TestInMemoryStoreClonesWarnedAt(t *testing.T) {
	store := NewInMemoryStore()
	warnedAt := time.Date(2026, 8, 2, 13, 0, 0, 0, time.UTC)
	if err := store.CreatePost(context.Background(), Post{ID: "p-1", RunID: "run-1", Content: "A", WarnedAt: &warnedAt}); err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	got, err := store.GetPost(context.Background(), "run-1", "p-1")
	if err != nil {
		t.Fatalf("GetPost: %v", err)
	}
	*got.WarnedAt = time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)
	again, err := store.GetPost(context.Background(), "run-1", "p-1")
	if err != nil {
		t.Fatalf("GetPost again: %v", err)
	}
	if !again.WarnedAt.Equal(warnedAt) {
		t.Fatalf("warned_at = %v, want the stored %v (must be cloned)", *again.WarnedAt, warnedAt)
	}
}

// ─── ListPosts：筛选 / 排序 / 分页 ───────────────────────────────────

// ListPosts 只返回本 run 的帖子，source/sentiment/warn_status 精确筛选
// 生效（可组合），排序 created_at DESC、同时间 id DESC，total 为匹配数，
// limit/offset 分页生效。
func TestInMemoryStoreListPosts(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	// run-1 四条：created_at 升序插入；p2 与 p3 同一时刻（断言 id DESC
	// 决胜）。
	rows := []Post{
		postAt("p1", "run-1", base),                       // 负面 微博 未预警
		postAt("p2", "run-1", base.Add(2*time.Hour)),      // 同 created_at
		postAt("p3", "run-1", base.Add(2*time.Hour)),      // 同 created_at
		postAt("p4", "run-1", base.Add(3*time.Hour)),      // 负面 新闻媒体 已预警
		postAt("px", "run-2", base.Add(4*time.Hour)),      // 其他 run，不应出现
	}
	rows[3].Source = SourceNews
	rows[3].Sentiment = SentimentPositive
	rows[3].WarnStatus = WarnStatusWarned
	rows[4].Source = SourceForum
	rows[1].Sentiment = SentimentNeutral
	rows[0].Sentiment = SentimentNegative
	rows[2].Sentiment = SentimentNegative
	for _, row := range rows {
		if err := store.CreatePost(ctx, row); err != nil {
			t.Fatalf("CreatePost: %v", err)
		}
	}

	// 全量：created_at DESC，p2/p3 同一时刻按 id DESC（p3 在前）。
	all, total, err := store.ListPosts(ctx, "run-1", PostFilter{Limit: 100})
	if err != nil {
		t.Fatalf("ListPosts: %v", err)
	}
	if total != 4 || len(all) != 4 {
		t.Fatalf("total/len = %d/%d, want 4/4", total, len(all))
	}
	wantOrder := []string{"p4", "p3", "p2", "p1"}
	for i, want := range wantOrder {
		if all[i].ID != want {
			t.Fatalf("order[%d] = %q, want %q (created_at DESC, id DESC)", i, all[i].ID, want)
		}
	}

	// 单一筛选：source=新闻媒体 → p4；sentiment=中性 → p2；
	// warn_status=已预警 → p4。
	cases := []struct {
		name   string
		filter PostFilter
		want   []string
	}{
		{"source", PostFilter{Source: SourceNews, Limit: 100}, []string{"p4"}},
		{"sentiment", PostFilter{Sentiment: SentimentNeutral, Limit: 100}, []string{"p2"}},
		{"warn_status", PostFilter{WarnStatus: WarnStatusWarned, Limit: 100}, []string{"p4"}},
	}
	for _, testCase := range cases {
		records, total, err := store.ListPosts(ctx, "run-1", testCase.filter)
		if err != nil {
			t.Fatalf("%s filter: %v", testCase.name, err)
		}
		if total != len(testCase.want) || len(records) != len(testCase.want) {
			t.Fatalf("%s filter: total/len = %d/%d, want %d", testCase.name, total, len(records), len(testCase.want))
		}
		for i, want := range testCase.want {
			if records[i].ID != want {
				t.Fatalf("%s filter: records[%d] = %q, want %q", testCase.name, i, records[i].ID, want)
			}
		}
	}

	// 组合筛选 + 分页：负面（p1/p3，按 DESC 序为 p3, p1），limit=1 offset=1
	// → 取 p1，total 保持 2。
	combined, total, err := store.ListPosts(ctx, "run-1", PostFilter{
		Sentiment: SentimentNegative,
		Limit:     1,
		Offset:    1,
	})
	if err != nil {
		t.Fatalf("combined: %v", err)
	}
	if total != 2 || len(combined) != 1 {
		t.Fatalf("combined: total/len = %d/%d, want 2/1", total, len(combined))
	}
	if combined[0].ID != "p1" {
		t.Fatalf("combined page = %q, want p1", combined[0].ID)
	}

	// 无匹配筛选 → 空页、total 0。
	none, total, err := store.ListPosts(ctx, "run-1", PostFilter{Source: SourceForum, Limit: 100})
	if err != nil {
		t.Fatalf("no-match filter: %v", err)
	}
	if total != 0 || len(none) != 0 {
		t.Fatalf("no-match filter: total/len = %d/%d, want 0/0", total, len(none))
	}

	// 其他 run 的帖子不受本 run 列表影响（run-1 全量不含 px）。
	other, total, err := store.ListPosts(ctx, "run-2", PostFilter{Limit: 100})
	if err != nil {
		t.Fatalf("run-2 list: %v", err)
	}
	if total != 1 || len(other) != 1 || other[0].ID != "px" {
		t.Fatalf("run-2 list = %+v, want only px", other)
	}
}

// ─── DeleteByRun：级联清理 ───────────────────────────────────────────

// DeleteByRun 同时清空该 run 的 opinion_events 与 opinion_posts，其他
// run 的对象保留；无对象可清时不是错误。
func TestInMemoryStoreDeleteByRunCleansPosts(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for _, runID := range []string{"run-1", "run-1", "run-2"} {
		if err := store.UpsertEvent(ctx, Event{RunID: runID, EventName: "E"}); err != nil {
			t.Fatalf("UpsertEvent(%s): %v", runID, err)
		}
		if err := store.CreatePost(ctx, Post{ID: "p-" + runID, RunID: runID, Content: "C"}); err != nil {
			t.Fatalf("CreatePost(%s): %v", runID, err)
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
	if event, err := store.GetEvent(ctx, "run-2"); err != nil || event.EventName != "E" {
		t.Fatalf("event of run-2: event = %+v, err = %v; want the untouched event", event, err)
	}
	if post, err := store.GetPost(ctx, "run-2", "p-run-2"); err != nil || post.Content != "C" {
		t.Fatalf("post of run-2: post = %+v, err = %v; want the untouched post", post, err)
	}
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
