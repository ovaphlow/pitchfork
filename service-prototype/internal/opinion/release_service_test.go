package opinion

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// inProgressReleaseService returns a service over a store and a single
// 进行中 run named run-1, plus the store for direct assertions.
func inProgressReleaseService() (*Service, *InMemoryStore) {
	return newTestService(run("run-1", drills.RunStatusInProgress))
}

// ─── CreateRelease ───────────────────────────────────────────────────

// 合法创建：id 为 26 位 Crockford Base32 ULID、run_id 注入、缺省 channel
// 官网公告 / status 草稿 / media_name ''、published_at nil、metadata {}、
// created_by ''、created_at/updated_at 服务端时间且相等；显式字段原样保留。
func TestCreateReleaseDefaults(t *testing.T) {
	service, store := inProgressReleaseService()

	release, err := service.CreateRelease(context.Background(), "run-1", ReleaseInput{Title: "情况说明", Content: "正文"})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	if !crockford26.MatchString(release.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", release.ID)
	}
	if release.RunID != "run-1" {
		t.Fatalf("run_id = %q, want run-1", release.RunID)
	}
	if release.Channel != ChannelOfficialWebsite || release.Title != "情况说明" || release.Content != "正文" ||
		release.MediaName != "" || release.Status != ReleaseStatusDraft || release.PublishedAt != nil ||
		release.Metadata == nil || len(release.Metadata) != 0 || release.CreatedBy != "" {
		t.Fatalf("release = %+v, want the defaults", release)
	}
	if release.CreatedAt.IsZero() || !release.CreatedAt.Equal(release.UpdatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want server time and equal", release.CreatedAt, release.UpdatedAt)
	}
	// 已写入 store。
	stored, err := store.GetRelease(context.Background(), "run-1", release.ID)
	if err != nil || stored.Title != release.Title {
		t.Fatalf("stored = %+v, err = %v; want the created release", stored, err)
	}
}

// 显式字段原样保留（channel 四种枚举 / media_name / metadata / created_by
// 透传）；创建显式非 草稿 status → ValidationError。
func TestCreateReleaseExplicitFieldsAndRejectsNonDraft(t *testing.T) {
	service, _ := inProgressReleaseService()
	ctx := context.Background()

	for _, channel := range validChannels {
		release, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T", Content: "C", Channel: channel})
		if err != nil {
			t.Fatalf("CreateRelease(%s): %v", channel, err)
		}
		if release.Channel != channel {
			t.Fatalf("channel = %q, want %q", release.Channel, channel)
		}
	}
	release, err := service.CreateRelease(ctx, "run-1", ReleaseInput{
		Title:     "情况说明",
		Content:   "正文",
		MediaName: "新华社",
		Metadata:  map[string]any{"channel": "press"},
		CreatedBy: "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	if release.MediaName != "新华社" || release.Metadata["channel"] != "press" || release.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", release)
	}

	for _, status := range []ReleaseStatus{ReleaseStatusPending, ReleaseStatusPublished, ReleaseStatusWithdrawn} {
		_, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T", Content: "C", Status: status})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("explicit %s on create: err = %v, want a ValidationError", status, err)
		}
	}
}

// 写门控与 run 存在性：run 不存在 → ErrRunNotFound；未开始/已完成/已终止
// → ValidationError（400 语义）。
func TestCreateReleaseRunChecks(t *testing.T) {
	service, _ := newTestService(
		run("not-started", drills.RunStatusNotStarted),
		run("completed", drills.RunStatusCompleted),
		run("terminated", drills.RunStatusTerminated),
	)
	ctx := context.Background()

	_, err := service.CreateRelease(ctx, "missing", ReleaseInput{Title: "T", Content: "C"})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for _, runID := range []string{"not-started", "completed", "terminated"} {
		_, err := service.CreateRelease(ctx, runID, ReleaseInput{Title: "T", Content: "C"})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("run %s: err = %v, want a ValidationError", runID, err)
		}
	}
}

// ─── GetRelease / ListReleases ───────────────────────────────────────

// GetRelease：存在 → 完整对象；不存在 → ErrReleaseNotFound；run 不存在 →
// ErrRunNotFound；GET 不受写门控（已完成 run 仍可读）。
func TestGetRelease(t *testing.T) {
	service, store := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("done", drills.RunStatusCompleted),
	)
	ctx := context.Background()

	created, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T", Content: "C"})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	got, err := service.GetRelease(ctx, "run-1", created.ID)
	if err != nil || got.ID != created.ID || got.Title != "T" {
		t.Fatalf("GetRelease = %+v, err = %v; want the created release", got, err)
	}

	if _, err := service.GetRelease(ctx, "run-1", "missing"); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("missing release: err = %v, want ErrReleaseNotFound", err)
	}
	if _, err := service.GetRelease(ctx, "missing", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// GET 不受写门控：已完成 run 直接经 store 注入发布记录后仍可读。
	if err := store.CreateRelease(ctx, Release{ID: "r-done", RunID: "done", Title: "T", Content: "C"}); err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	got, err = service.GetRelease(ctx, "done", "r-done")
	if err != nil || got.Title != "T" {
		t.Fatalf("GetRelease on completed run = %+v, err = %v; want 200 semantics", got, err)
	}
}

// ListReleases：run 不存在 → ErrRunNotFound；已完成 run 仍可列表（GET 不
// 受门控）；筛选/分页透传到 store。
func TestListReleases(t *testing.T) {
	service, store := newTestService(run("done", drills.RunStatusCompleted))
	ctx := context.Background()

	if _, _, err := service.ListReleases(ctx, "missing", ReleaseFilter{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for i := 0; i < 3; i++ {
		if err := store.CreateRelease(ctx, Release{ID: "r" + string(rune('a'+i)), RunID: "done", Title: "T", Content: "C"}); err != nil {
			t.Fatalf("CreateRelease: %v", err)
		}
	}
	records, total, err := service.ListReleases(ctx, "done", ReleaseFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListReleases: %v", err)
	}
	if total != 3 || len(records) != 2 {
		t.Fatalf("total/len = %d/%d, want 3/2", total, len(records))
	}
}

// ─── UpdateRelease：部分更新 ─────────────────────────────────────────

// 部分更新语义：title/content 必填（双入口），缺省 channel/media_name/
// status/metadata/created_by 保持原值；显式字段生效；updated_at 刷新、
// id/run_id/created_at 不变；PUT 后 GET 反映更新。
func TestUpdateReleasePartialUpdate(t *testing.T) {
	service, _ := inProgressReleaseService()
	ctx := context.Background()

	created, err := service.CreateRelease(ctx, "run-1", ReleaseInput{
		Title:     "原标题",
		Content:   "原正文",
		Channel:   ChannelNewsRelease,
		MediaName: "新华社",
		Metadata:  map[string]any{"k": "v"},
		CreatedBy: "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	// 只改 title/content：其余字段保持。
	updated, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "新标题", Content: "新正文"})
	if err != nil {
		t.Fatalf("UpdateRelease: %v", err)
	}
	if updated.ID != created.ID || updated.RunID != "run-1" || !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("id/run_id/created_at must be preserved: %+v", updated)
	}
	if updated.Title != "新标题" || updated.Content != "新正文" || updated.Channel != ChannelNewsRelease ||
		updated.MediaName != "新华社" || updated.Status != ReleaseStatusDraft ||
		updated.Metadata["k"] != "v" || updated.CreatedBy != "u-admin" {
		t.Fatalf("partial update did not keep the untouched fields: %+v", updated)
	}
	if updated.UpdatedAt.Before(createdAt) || updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at = %v, want a refreshed value", updated.UpdatedAt)
	}

	// 显式 channel/media_name/metadata（含 {} 边界值）/created_by 生效。
	updated, err = service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{
		Title:       "新标题",
		Content:     "新正文",
		Channel:     ChannelWechat,
		MediaName:   "官方公众号",
		Metadata:    map[string]any{},
		HasMetadata: true,
		CreatedBy:   "u-2",
	})
	if err != nil {
		t.Fatalf("UpdateRelease channel/media_name/metadata/created_by: %v", err)
	}
	if updated.Channel != ChannelWechat || updated.MediaName != "官方公众号" ||
		len(updated.Metadata) != 0 || updated.CreatedBy != "u-2" {
		t.Fatalf("explicit fields not applied: %+v", updated)
	}

	// 更新已持久化：再 GET 反映更新。
	fetched, err := service.GetRelease(ctx, "run-1", created.ID)
	if err != nil || fetched.Title != "新标题" || fetched.Channel != ChannelWechat || fetched.MediaName != "官方公众号" {
		t.Fatalf("GET after PUT = %+v, err = %v; want the updated values", fetched, err)
	}
}

// 发布状态机（PUT 入口）：草稿→待审核→已发布→已撤回 相邻迁移合法；置
// 已发布 时服务端设 published_at；同值 no-op 合法且 published_at 保持
// 原值（不重置）；已撤回 时 published_at 重置 null；跳级/回退（含已发布
// →待审核、已撤回改回）400；PUT 未涉及 status 时 published_at 保持原值。
func TestUpdateReleaseStatusStateMachine(t *testing.T) {
	service, _ := inProgressReleaseService()
	ctx := context.Background()

	created, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T", Content: "C"})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	if created.PublishedAt != nil {
		t.Fatalf("published_at = %v at creation, want nil", created.PublishedAt)
	}

	// 跳级 草稿 → 已发布：400。
	_, err = service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: ReleaseStatusPublished})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("skip transition: err = %v, want a ValidationError", err)
	}

	// 草稿 → 待审核：published_at 保持 nil。
	time.Sleep(5 * time.Millisecond)
	pending, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: ReleaseStatusPending})
	if err != nil {
		t.Fatalf("draft -> pending: %v", err)
	}
	if pending.Status != ReleaseStatusPending || pending.PublishedAt != nil {
		t.Fatalf("pending = %+v, want 待审核 with nil published_at", pending)
	}

	// 待审核 → 已发布：published_at 被服务端设置。
	time.Sleep(5 * time.Millisecond)
	published, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: ReleaseStatusPublished})
	if err != nil {
		t.Fatalf("pending -> published: %v", err)
	}
	if published.Status != ReleaseStatusPublished || published.PublishedAt == nil {
		t.Fatalf("published = %+v, want 已发布 with a server-set published_at", published)
	}
	publishedAt := *published.PublishedAt

	// 同值 no-op：已发布 → 已发布 200，published_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	again, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: ReleaseStatusPublished})
	if err != nil {
		t.Fatalf("same-value no-op: %v", err)
	}
	if again.Status != ReleaseStatusPublished || again.PublishedAt == nil || !again.PublishedAt.Equal(publishedAt) {
		t.Fatalf("no-op published_at = %v, want the unchanged %v", again.PublishedAt, publishedAt)
	}

	// PUT 未涉及 status：published_at 保持原值。
	untouched, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "新标题", Content: "新正文"})
	if err != nil {
		t.Fatalf("update without status: %v", err)
	}
	if untouched.PublishedAt == nil || !untouched.PublishedAt.Equal(publishedAt) {
		t.Fatalf("published_at after unrelated update = %v, want %v", untouched.PublishedAt, publishedAt)
	}

	// 回退 已发布 → 待审核：400。
	_, err = service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: ReleaseStatusPending})
	if !errors.As(err, &validationError) {
		t.Fatalf("backward transition: err = %v, want a ValidationError", err)
	}

	// 已发布 → 已撤回：合法，published_at 重置 null。
	withdrawn, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: ReleaseStatusWithdrawn})
	if err != nil {
		t.Fatalf("published -> withdrawn: %v", err)
	}
	if withdrawn.Status != ReleaseStatusWithdrawn || withdrawn.PublishedAt != nil {
		t.Fatalf("withdrawn = %+v, want 已撤回 with nil published_at", withdrawn)
	}

	// 已撤回改回 草稿/待审核/已发布：400。
	for _, status := range []ReleaseStatus{ReleaseStatusDraft, ReleaseStatusPending, ReleaseStatusPublished} {
		_, err := service.UpdateRelease(ctx, "run-1", created.ID, ReleaseUpdate{Title: "T", Content: "C", Status: status})
		if !errors.As(err, &validationError) {
			t.Fatalf("away from 已撤回 (%s): err = %v, want a ValidationError", status, err)
		}
	}

	// 同值 草稿 no-op（草稿 发布记录）：200 且 published_at 保持 nil。
	draft, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T2", Content: "C2"})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	draftAgain, err := service.UpdateRelease(ctx, "run-1", draft.ID, ReleaseUpdate{Title: "T2", Content: "C2", Status: ReleaseStatusDraft})
	if err != nil {
		t.Fatalf("草稿 no-op: %v", err)
	}
	if draftAgain.Status != ReleaseStatusDraft || draftAgain.PublishedAt != nil {
		t.Fatalf("draft no-op = %+v, want 草稿 with nil published_at", draftAgain)
	}
}

// 更新失败路径：run 不存在 404 语义、非进行中 400 语义（门控先于存在性判
// 定）、发布记录不存在 404 语义、缺 title/content 400、非法枚举 400。
func TestUpdateReleaseFailures(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	ctx := context.Background()

	if _, err := service.UpdateRelease(ctx, "missing", "r", ReleaseUpdate{Title: "T", Content: "C"}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpdateRelease(ctx, "run-1", "missing", ReleaseUpdate{Title: "T", Content: "C"}); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("missing release: err = %v, want ErrReleaseNotFound", err)
	}

	// 门控先于发布记录存在性判定：非进行中 run 更新缺失发布记录 → 400。
	locked, _ := newTestService(run("done", drills.RunStatusCompleted))
	if _, err := locked.UpdateRelease(ctx, "done", "r", ReleaseUpdate{Title: "T", Content: "C"}); err == nil {
		t.Fatal("missing release on locked run: err = nil, want a ValidationError (gate first)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("missing release on locked run: err = %v, want a ValidationError (gate first)", err)
		}
	}

	created, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T", Content: "C"})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}

	for name, update := range map[string]ReleaseUpdate{
		"missing title":   {Content: "C"},
		"empty title":     {Title: "", Content: "C"},
		"missing content": {Title: "T"},
		"empty content":   {Title: "T", Content: ""},
		"invalid channel": {Title: "T", Content: "C", Channel: "电视台"},
		"invalid status":  {Title: "T", Content: "C", Status: "审核中"},
	} {
		_, err := service.UpdateRelease(ctx, "run-1", created.ID, update)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
}

// ─── DeleteRelease ───────────────────────────────────────────────────

// 成功删除（再次删除 404）；run 不存在 404；非进行中 400（门控先于发布
// 记录存在性判定）。
func TestDeleteRelease(t *testing.T) {
	service, _ := inProgressReleaseService()
	ctx := context.Background()

	created, err := service.CreateRelease(ctx, "run-1", ReleaseInput{Title: "T", Content: "C"})
	if err != nil {
		t.Fatalf("CreateRelease: %v", err)
	}
	if err := service.DeleteRelease(ctx, "run-1", created.ID); err != nil {
		t.Fatalf("DeleteRelease: %v", err)
	}
	if err := service.DeleteRelease(ctx, "run-1", created.ID); !errors.Is(err, ErrReleaseNotFound) {
		t.Fatalf("delete again: err = %v, want ErrReleaseNotFound", err)
	}
	if err := service.DeleteRelease(ctx, "missing", "r"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	locked, _ := newTestService(run("done", drills.RunStatusCompleted))
	if err := locked.DeleteRelease(ctx, "done", "r"); err == nil {
		t.Fatal("delete missing release on locked run: err = nil, want a ValidationError (gate first)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("delete missing release on locked run: err = %v, want a ValidationError (gate first)", err)
		}
	}
}
