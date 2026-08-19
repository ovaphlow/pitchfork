package opinion

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ─── normalizeReview ─────────────────────────────────────────────────

// 首次创建：完整对象，id 由调用方传入、run_id 回显、五段文本缺省 ”、
// metadata 缺省 {}、created_by 缺省 ”、created_at/updated_at 为服务端
// 时间且相等；空对象 {} 是全缺省创建（无必填字段）。
func TestNormalizeReviewDefaults(t *testing.T) {
	review := normalizeReview("run-1", ReviewInput{}, fixedTime, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	if review.ID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || review.RunID != "run-1" {
		t.Fatalf("id/run_id = %q / %q, want the caller-provided values", review.ID, review.RunID)
	}
	if review.CaseSummary != "" || review.Highlights != "" || review.Problems != "" ||
		review.Lessons != "" || review.Suggestions != "" || review.CreatedBy != "" {
		t.Fatalf("five sections/created_by = %q / %q / %q / %q / %q / %q, want empty defaults",
			review.CaseSummary, review.Highlights, review.Problems, review.Lessons, review.Suggestions, review.CreatedBy)
	}
	if review.Metadata == nil || len(review.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", review.Metadata)
	}
	if !review.CreatedAt.Equal(fixedTime) || !review.UpdatedAt.Equal(fixedTime) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", review.CreatedAt, review.UpdatedAt, fixedTime)
	}
}

// 显式字段原样保留：五段文本 / metadata / created_by 透传。
func TestNormalizeReviewPassthrough(t *testing.T) {
	review := normalizeReview("run-1", ReviewInput{
		CaseSummary: "事件经过",
		Highlights:  "处置亮点",
		Problems:    "存在问题",
		Lessons:     "经验教训",
		Suggestions: "改进建议",
		Metadata:    map[string]any{"source": "merit"},
		CreatedBy:   "u-admin",
	}, fixedTime, "id-1")
	if review.CaseSummary != "事件经过" || review.Highlights != "处置亮点" || review.Problems != "存在问题" ||
		review.Lessons != "经验教训" || review.Suggestions != "改进建议" ||
		review.Metadata["source"] != "merit" || review.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", review)
	}
}

// ─── UpsertReview：首次创建与原地更新 ───────────────────────────────

// 首次 PUT：完整对象——服务端生成 26 位 Crockford Base32 ULID、五段文本
// 缺省 ”、metadata 缺省 {}、created_by 缺省 ”、created_at/updated_at 服务端
// 时间且相等。
func TestUpsertReviewCreatesWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	review, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{})
	if err != nil {
		t.Fatalf("UpsertReview: %v", err)
	}
	if !crockford26.MatchString(review.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", review.ID)
	}
	if review.RunID != "run-1" {
		t.Fatalf("run_id = %q, want run-1", review.RunID)
	}
	if review.CaseSummary != "" || review.Highlights != "" || review.Problems != "" ||
		review.Lessons != "" || review.Suggestions != "" || review.CreatedBy != "" {
		t.Fatalf("five sections/created_by = %+v, want empty defaults", review)
	}
	if review.Metadata == nil || len(review.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", review.Metadata)
	}
	if review.CreatedAt.IsZero() || !review.CreatedAt.Equal(review.UpdatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want server time and equal", review.CreatedAt, review.UpdatedAt)
	}
}

// 再次 PUT：id 与 created_at 保留不变、updated_at 刷新；全量覆盖语义
// （body 缺省字段重置为默认值——五段文本/metadata/created_by 重置）。
func TestUpsertReviewUpdatesInPlace(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	created, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{
		CaseSummary: "事件经过", Highlights: "处置亮点", Problems: "存在问题",
		Lessons: "经验教训", Suggestions: "改进建议",
		Metadata: map[string]any{"source": "merit"}, CreatedBy: "u-admin",
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	createdAt := created.CreatedAt

	updated, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{})
	if err != nil {
		t.Fatalf("update: %v", err)
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
	if updated.CaseSummary != "" || updated.Highlights != "" || updated.Problems != "" ||
		updated.Lessons != "" || updated.Suggestions != "" || updated.CreatedBy != "" ||
		len(updated.Metadata) != 0 {
		t.Fatalf("replacement semantics = %+v", updated)
	}
}

// ─── run 不存在 / 未配置 ─────────────────────────────────────────────

// run 不存在：GET/PUT/DELETE 均 ErrRunNotFound。
func TestReviewRunNotFound(t *testing.T) {
	service, _ := newTestService()
	if _, err := service.GetReview(context.Background(), "missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("GET: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpsertReview(context.Background(), "missing", ReviewInput{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("PUT: err = %v, want ErrRunNotFound", err)
	}
	if err := service.DeleteReview(context.Background(), "missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("DELETE: err = %v, want ErrRunNotFound", err)
	}
}

// run 存在但复盘未写：GET/DELETE 均 ErrReviewNotFound；PUT 创建（upsert，
// 无 404 分支）。
func TestReviewNotConfigured(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	if _, err := service.GetReview(context.Background(), "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("GET: err = %v, want ErrReviewNotFound", err)
	}
	if err := service.DeleteReview(context.Background(), "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("DELETE: err = %v, want ErrReviewNotFound", err)
	}
	if _, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{}); err != nil {
		t.Fatalf("PUT: %v", err)
	}
}

// ─── 写门控 ──────────────────────────────────────────────────────────

// 写门控：run 未开始/已终止 时 PUT/DELETE → ValidationError（复盘已存在）；
// 进行中/已完成 可写；GET 不受门控（已写即返回）。判定顺序：复盘未写 404
// 先于写门控 400（未开始 run 未写 → DELETE ErrReviewNotFound）。
func TestReviewWriteGate(t *testing.T) {
	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusTerminated} {
		service, _ := newTestService(run("run-1", status))
		if _, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{}); !errors.As(err, &validationError) {
			t.Fatalf("%s PUT: err = %v, want a ValidationError", status, err)
		}
		if err := service.DeleteReview(context.Background(), "run-1"); !errors.Is(err, ErrReviewNotFound) {
			t.Fatalf("%s DELETE without review: err = %v, want ErrReviewNotFound (existence checked first)", status, err)
		}
	}

	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusTerminated} {
		service, store := newTestService(run("run-1", status))
		store.reviews = append(store.reviews, Review{RunID: "run-1"})
		if _, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{}); !errors.As(err, &validationError) {
			t.Fatalf("%s PUT with review: err = %v, want a ValidationError", status, err)
		}
		if err := service.DeleteReview(context.Background(), "run-1"); !errors.As(err, &validationError) {
			t.Fatalf("%s DELETE with review: err = %v, want a ValidationError", status, err)
		}
		// GET 不受写门控。
		review, err := service.GetReview(context.Background(), "run-1")
		if err != nil || review.RunID != "run-1" {
			t.Fatalf("%s GET: review = %+v, err = %v; want the written review", status, review, err)
		}
	}

	for _, status := range []drills.RunStatus{drills.RunStatusInProgress, drills.RunStatusCompleted} {
		service, _ := newTestService(run("run-1", status))
		review, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{CaseSummary: "经过"})
		if err != nil || review.CaseSummary != "经过" {
			t.Fatalf("%s PUT: review = %+v, err = %v; want a created review", status, review, err)
		}
		if err := service.DeleteReview(context.Background(), "run-1"); err != nil {
			t.Fatalf("%s DELETE: %v", status, err)
		}
	}
}

// ─── GetReview / DeleteReview ────────────────────────────────────────

// GET 返回已写的完整对象；DELETE 后 GET → ErrReviewNotFound（删除生效）；
// 再次 DELETE → ErrReviewNotFound。
func TestGetAndDeleteReview(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{CaseSummary: "事件经过", Highlights: "处置亮点"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}

	fetched, err := service.GetReview(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	if fetched.ID != created.ID || fetched.CaseSummary != "事件经过" || fetched.Highlights != "处置亮点" {
		t.Fatalf("GET = %+v, want the created review", fetched)
	}

	if err := service.DeleteReview(context.Background(), "run-1"); err != nil {
		t.Fatalf("DELETE: %v", err)
	}
	if _, err := service.GetReview(context.Background(), "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("GET after DELETE: err = %v, want ErrReviewNotFound", err)
	}
	if err := service.DeleteReview(context.Background(), "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("DELETE again: err = %v, want ErrReviewNotFound", err)
	}
}

// ─── 级联：删除 run 后复盘随之清空 ───────────────────────────────────

// 接线 DeleteByRun 清理入口：创建复盘后删除 run，复盘消失（内存行为与
// 迁移 ON DELETE CASCADE 一致）；未写复盘的 run 删除不报错。
func TestDeleteByRunCascadesReview(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	if _, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{CaseSummary: "A"}); err != nil {
		t.Fatalf("create run-1: %v", err)
	}
	if _, err := service.UpsertReview(context.Background(), "run-2", ReviewInput{CaseSummary: "B"}); err != nil {
		t.Fatalf("create run-2: %v", err)
	}

	if err := store.DeleteByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if _, err := service.GetReview(context.Background(), "run-1"); !errors.Is(err, ErrReviewNotFound) {
		t.Fatalf("GET run-1 after DeleteByRun: err = %v, want ErrReviewNotFound", err)
	}
	// 其他 run 的复盘不受影响。
	if review, err := service.GetReview(context.Background(), "run-2"); err != nil || review.CaseSummary != "B" {
		t.Fatalf("GET run-2: review = %+v, err = %v; want the untouched review", review, err)
	}
	// 无复盘可清时不是错误。
	if err := store.DeleteByRun(context.Background(), "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}

// ─── reviewWritableRun ───────────────────────────────────────────────

// 写门控判定：进行中/已完成 可写；未开始/已终止 不可写。
func TestReviewWritableRun(t *testing.T) {
	for _, status := range []drills.RunStatus{drills.RunStatusInProgress, drills.RunStatusCompleted} {
		if !reviewWritableRun(status) {
			t.Fatalf("status %s should be writable", status)
		}
	}
	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusTerminated} {
		if reviewWritableRun(status) {
			t.Fatalf("status %s should not be writable", status)
		}
	}
}

// ─── 时间维护 ────────────────────────────────────────────────────────

// created_at 服务端维护（首次创建取服务端当前时间）、updated_at 更新时
// 刷新：把时钟拨快后再次 PUT，created_at 不变、updated_at 为新的服务端
// 时间。
func TestUpsertReviewServerTimestamps(t *testing.T) {
	store := NewInMemoryStore()
	source := &fakeRunSource{runs: map[string]drills.Run{"run-1": {ID: "run-1", Status: drills.RunStatusInProgress}}}
	service := NewService(store, source)

	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	service.now = func() time.Time { return base }
	created, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{CaseSummary: "A"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	if !created.CreatedAt.Equal(base) || !created.UpdatedAt.Equal(base) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", created.CreatedAt, created.UpdatedAt, base)
	}

	later := base.Add(time.Hour)
	service.now = func() time.Time { return later }
	updated, err := service.UpsertReview(context.Background(), "run-1", ReviewInput{CaseSummary: "B"})
	if err != nil {
		t.Fatalf("update: %v", err)
	}
	if !updated.CreatedAt.Equal(base) {
		t.Fatalf("created_at %v changed to %v on update", base, updated.CreatedAt)
	}
	if !updated.UpdatedAt.Equal(later) {
		t.Fatalf("updated_at = %v, want the refreshed server time %v", updated.UpdatedAt, later)
	}
}
