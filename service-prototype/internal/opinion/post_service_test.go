package opinion

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// inProgressPostService returns a service over a store and a single
// 进行中 run named run-1, plus the store for direct assertions.
func inProgressPostService() (*Service, *InMemoryStore) {
	return newTestService(run("run-1", drills.RunStatusInProgress))
}

// ─── CreatePost ──────────────────────────────────────────────────────

// 合法创建：id 为 26 位 Crockford Base32 ULID、run_id 注入、缺省 source
// 微博 / sentiment 负面 / heat 0 / warn_status 未预警、warned_at nil、
// metadata {}、created_by ”、created_at/updated_at 服务端时间且相等；
// 显式字段原样保留。
func TestCreatePostDefaults(t *testing.T) {
	service, store := inProgressPostService()

	post, err := service.CreatePost(context.Background(), "run-1", PostInput{Content: "展厅入口聚集大量游客"})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	if !crockford26.MatchString(post.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", post.ID)
	}
	if post.RunID != "run-1" {
		t.Fatalf("run_id = %q, want run-1", post.RunID)
	}
	if post.Content != "展厅入口聚集大量游客" || post.Source != SourceWeibo ||
		post.Sentiment != SentimentNegative || post.Heat != 0 ||
		post.WarnStatus != WarnStatusPending || post.WarnedAt != nil ||
		post.Metadata == nil || len(post.Metadata) != 0 || post.CreatedBy != "" {
		t.Fatalf("post = %+v, want the defaults", post)
	}
	if post.CreatedAt.IsZero() || !post.CreatedAt.Equal(post.UpdatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want server time and equal", post.CreatedAt, post.UpdatedAt)
	}
	// 已写入 store。
	stored, err := store.GetPost(context.Background(), "run-1", post.ID)
	if err != nil || stored.Content != post.Content {
		t.Fatalf("stored = %+v, err = %v; want the created post", stored, err)
	}
}

// 显式字段透传；显式 已预警 在创建时拒绝（400 语义）。
func TestCreatePostExplicitFieldsAndRejectsWarned(t *testing.T) {
	service, _ := inProgressPostService()
	ctx := context.Background()

	post, err := service.CreatePost(ctx, "run-1", PostInput{
		Content:    "展厅出口出现踩踏风险",
		Source:     SourceNews,
		Sentiment:  SentimentPositive,
		Heat:       88,
		Metadata:   map[string]any{"platform": "news"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	if post.Source != SourceNews || post.Sentiment != SentimentPositive || post.Heat != 88 ||
		post.Metadata["platform"] != "news" || post.CreatedBy != "u-admin" {
		t.Fatalf("post = %+v, want the provided values", post)
	}

	_, err = service.CreatePost(ctx, "run-1", PostInput{Content: "A", WarnStatus: WarnStatusWarned})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("explicit 已预警 on create: err = %v, want a ValidationError", err)
	}
}

// 写门控与 run 存在性：run 不存在 → ErrRunNotFound；未开始/已完成/已终止
// → ValidationError（400 语义）。
func TestCreatePostRunChecks(t *testing.T) {
	service, _ := newTestService(
		run("not-started", drills.RunStatusNotStarted),
		run("completed", drills.RunStatusCompleted),
		run("terminated", drills.RunStatusTerminated),
	)
	ctx := context.Background()

	_, err := service.CreatePost(ctx, "missing", PostInput{Content: "A"})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for _, runID := range []string{"not-started", "completed", "terminated"} {
		_, err := service.CreatePost(ctx, runID, PostInput{Content: "A"})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("run %s: err = %v, want a ValidationError", runID, err)
		}
	}
}

// ─── GetPost / ListPosts ─────────────────────────────────────────────

// GetPost：存在 → 200 语义完整对象；不存在 → ErrPostNotFound；run 不存在
// → ErrRunNotFound；GET 不受写门控（已完成 run 仍可读）。
func TestGetPost(t *testing.T) {
	service, store := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("done", drills.RunStatusCompleted),
	)
	ctx := context.Background()

	created, err := service.CreatePost(ctx, "run-1", PostInput{Content: "A"})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	got, err := service.GetPost(ctx, "run-1", created.ID)
	if err != nil || got.ID != created.ID || got.Content != "A" {
		t.Fatalf("GetPost = %+v, err = %v; want the created post", got, err)
	}

	if _, err := service.GetPost(ctx, "run-1", "missing"); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("missing post: err = %v, want ErrPostNotFound", err)
	}
	if _, err := service.GetPost(ctx, "missing", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// GET 不受写门控：已完成 run 直接经 store 注入帖子后仍可读。
	if err := store.CreatePost(ctx, Post{ID: "p-done", RunID: "done", Content: "B"}); err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	got, err = service.GetPost(ctx, "done", "p-done")
	if err != nil || got.Content != "B" {
		t.Fatalf("GetPost on completed run = %+v, err = %v; want 200 semantics", got, err)
	}
}

// ListPosts：run 不存在 → ErrRunNotFound；已完成 run 仍可列表（GET 不受
// 门控）；筛选/分页透传到 store。
func TestListPosts(t *testing.T) {
	service, store := newTestService(run("done", drills.RunStatusCompleted))
	ctx := context.Background()

	if _, _, err := service.ListPosts(ctx, "missing", PostFilter{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for i := 0; i < 3; i++ {
		if err := store.CreatePost(ctx, Post{ID: "p" + string(rune('a'+i)), RunID: "done", Content: "C"}); err != nil {
			t.Fatalf("CreatePost: %v", err)
		}
	}
	records, total, err := service.ListPosts(ctx, "done", PostFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListPosts: %v", err)
	}
	if total != 3 || len(records) != 2 {
		t.Fatalf("total/len = %d/%d, want 3/2", total, len(records))
	}
}

// ─── UpdatePost：部分更新 ────────────────────────────────────────────

// 部分更新语义：缺省字段保持原值；显式 content/source/sentiment/heat/
// metadata/created_by 生效；updated_at 刷新、id/run_id/created_at 不变。
func TestUpdatePostPartialUpdate(t *testing.T) {
	service, _ := inProgressPostService()
	ctx := context.Background()

	created, err := service.CreatePost(ctx, "run-1", PostInput{
		Content:    "原内容",
		Source:     SourceForum,
		Sentiment:  SentimentNegative,
		Heat:       30,
		Metadata:   map[string]any{"k": "v"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	// 只改 content：其余字段保持。
	updated, err := service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{Content: "新内容"})
	if err != nil {
		t.Fatalf("UpdatePost: %v", err)
	}
	if updated.ID != created.ID || updated.RunID != "run-1" || !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("id/run_id/created_at must be preserved: %+v", updated)
	}
	if updated.Content != "新内容" || updated.Source != SourceForum || updated.Sentiment != SentimentNegative ||
		updated.Heat != 30 || updated.Metadata["k"] != "v" || updated.CreatedBy != "u-admin" {
		t.Fatalf("partial update did not keep the untouched fields: %+v", updated)
	}
	if updated.UpdatedAt.Before(createdAt) || updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at = %v, want a refreshed value", updated.UpdatedAt)
	}

	// 显式 heat/metadata（含 0 与 {} 边界值）生效。
	updated, err = service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{
		Heat:        0,
		HasHeat:     true,
		Metadata:    map[string]any{},
		HasMetadata: true,
	})
	if err != nil {
		t.Fatalf("UpdatePost heat/metadata: %v", err)
	}
	if updated.Heat != 0 || len(updated.Metadata) != 0 {
		t.Fatalf("explicit heat 0 / metadata {} not applied: %+v", updated)
	}
	updated, err = service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{
		Source:     SourceDouyin,
		Sentiment:  SentimentPositive,
		CreatedBy:  "u-2",
	})
	if err != nil {
		t.Fatalf("UpdatePost source/sentiment/created_by: %v", err)
	}
	if updated.Source != SourceDouyin || updated.Sentiment != SentimentPositive || updated.CreatedBy != "u-2" {
		t.Fatalf("explicit fields not applied: %+v", updated)
	}

	// 更新已持久化：再 GET 反映更新。
	fetched, err := service.GetPost(ctx, "run-1", created.ID)
	if err != nil || fetched.Content != "新内容" || fetched.Source != SourceDouyin || fetched.Heat != 0 {
		t.Fatalf("GET after PUT = %+v, err = %v; want the updated values", fetched, err)
	}
}

// 预警状态机（PUT 入口）：未预警 → 已预警 设置 warned_at；同值 已预警
// no-op 合法且 warned_at 保持不变（不重置）；同值 未预警 no-op 合法且
// warned_at 保持 nil；已预警 → 未预警 400；PUT 未涉及 warn_status 时
// warned_at 保持原值。
func TestUpdatePostWarnStateMachine(t *testing.T) {
	service, _ := inProgressPostService()
	ctx := context.Background()

	created, err := service.CreatePost(ctx, "run-1", PostInput{Content: "A"})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	if created.WarnedAt != nil {
		t.Fatalf("warned_at = %v at creation, want nil", created.WarnedAt)
	}

	// 未预警 → 已预警：warned_at 被服务端设置。
	time.Sleep(5 * time.Millisecond)
	warned, err := service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{WarnStatus: WarnStatusWarned})
	if err != nil {
		t.Fatalf("warn transition: %v", err)
	}
	if warned.WarnStatus != WarnStatusWarned || warned.WarnedAt == nil {
		t.Fatalf("warned = %+v, want 已预警 with a server-set warned_at", warned)
	}
	warnedAt := *warned.WarnedAt

	// 同值 no-op：已预警 → 已预警 200，warned_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	again, err := service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{WarnStatus: WarnStatusWarned})
	if err != nil {
		t.Fatalf("same-value no-op: %v", err)
	}
	if again.WarnStatus != WarnStatusWarned || again.WarnedAt == nil || !again.WarnedAt.Equal(warnedAt) {
		t.Fatalf("no-op warned_at = %v, want the unchanged %v", again.WarnedAt, warnedAt)
	}

	// PUT 未涉及 warn_status：warned_at 保持原值。
	untouched, err := service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{Content: "B"})
	if err != nil {
		t.Fatalf("update without warn_status: %v", err)
	}
	if !untouched.WarnedAt.Equal(warnedAt) {
		t.Fatalf("warned_at after unrelated update = %v, want %v", untouched.WarnedAt, warnedAt)
	}

	// 已预警 → 未预警：400。
	_, err = service.UpdatePost(ctx, "run-1", created.ID, PostUpdate{WarnStatus: WarnStatusPending})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("backward transition: err = %v, want a ValidationError", err)
	}

	// 同值 未预警 no-op（未预警 帖子）：200 且 warned_at 保持 nil。
	pending, err := service.CreatePost(ctx, "run-1", PostInput{Content: "B"})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	pendingAgain, err := service.UpdatePost(ctx, "run-1", pending.ID, PostUpdate{WarnStatus: WarnStatusPending})
	if err != nil {
		t.Fatalf("未预警 no-op: %v", err)
	}
	if pendingAgain.WarnStatus != WarnStatusPending || pendingAgain.WarnedAt != nil {
		t.Fatalf("pending no-op = %+v, want 未预警 with nil warned_at", pendingAgain)
	}
}

// 更新失败路径：run 不存在 404 语义、非进行中 400 语义、帖子不存在 404
// 语义（门控先于帖子存在性判定）、非法枚举 / heat 越界 400。
func TestUpdatePostFailures(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	ctx := context.Background()

	if _, err := service.UpdatePost(ctx, "missing", "p", PostUpdate{Content: "A"}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpdatePost(ctx, "run-1", "missing", PostUpdate{Content: "A"}); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("missing post: err = %v, want ErrPostNotFound", err)
	}

	// 门控先于帖子存在性判定：非进行中 run 更新缺失帖子 → 400 语义。
	locked, _ := newTestService(run("done", drills.RunStatusCompleted))
	if _, err := locked.UpdatePost(ctx, "done", "p", PostUpdate{Content: "A"}); err == nil {
		t.Fatal("missing post on locked run: err = nil, want a ValidationError (gate first)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("missing post on locked run: err = %v, want a ValidationError (gate first)", err)
		}
	}

	created, err := service.CreatePost(ctx, "run-1", PostInput{Content: "A"})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	locked, _ = newTestService(run("done", drills.RunStatusCompleted))
	if _, err := locked.UpdatePost(ctx, "done", created.ID, PostUpdate{Content: "B"}); err == nil {
		t.Fatal("update on completed run: err = nil, want a ValidationError")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("update on completed run: err = %v, want a ValidationError", err)
		}
	}

	for name, update := range map[string]PostUpdate{
		"invalid source":    {Source: "微信"},
		"invalid sentiment": {Sentiment: "消极"},
		"invalid warn_status": {WarnStatus: "预警中"},
		"heat below range":  {Heat: -1, HasHeat: true},
		"heat above range":  {Heat: 101, HasHeat: true},
	} {
		_, err := service.UpdatePost(ctx, "run-1", created.ID, update)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
}

// ─── DeletePost ──────────────────────────────────────────────────────

// 成功删除（再次删除 404）；run 不存在 404；非进行中 400（门控先于帖子
// 存在性判定）。
func TestDeletePost(t *testing.T) {
	service, _ := inProgressPostService()
	ctx := context.Background()

	created, err := service.CreatePost(ctx, "run-1", PostInput{Content: "A"})
	if err != nil {
		t.Fatalf("CreatePost: %v", err)
	}
	if err := service.DeletePost(ctx, "run-1", created.ID); err != nil {
		t.Fatalf("DeletePost: %v", err)
	}
	if err := service.DeletePost(ctx, "run-1", created.ID); !errors.Is(err, ErrPostNotFound) {
		t.Fatalf("delete again: err = %v, want ErrPostNotFound", err)
	}
	if err := service.DeletePost(ctx, "missing", "p"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	locked, _ := newTestService(run("done", drills.RunStatusCompleted))
	if err := locked.DeletePost(ctx, "done", "p"); err == nil {
		t.Fatal("delete missing post on locked run: err = nil, want a ValidationError (gate first)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("delete missing post on locked run: err = %v, want a ValidationError (gate first)", err)
		}
	}
}
