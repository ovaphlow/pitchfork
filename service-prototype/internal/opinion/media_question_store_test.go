package opinion

import (
	"context"
	"errors"
	"testing"
	"time"
)

// ─── InMemoryStore: opinion_media_questions ──────────────────────────

// Create/Get/Update/Delete：不存在时各自返回 ErrMediaQuestionNotFound；
// Update 按 (run_id, id) 原地替换；Delete 删除；Get 返回副本（改返回值
// 不影响存储，answered_at 指针深复制）。
func TestInMemoryStoreMediaQuestionCRUD(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	if _, err := store.GetMediaQuestion(ctx, "run-1", "q-1"); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("GetMediaQuestion on empty store: err = %v, want ErrMediaQuestionNotFound", err)
	}
	if err := store.UpdateMediaQuestion(ctx, MediaQuestion{ID: "q-1", RunID: "run-1"}); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("UpdateMediaQuestion on empty store: err = %v, want ErrMediaQuestionNotFound", err)
	}
	if err := store.DeleteMediaQuestion(ctx, "run-1", "q-1"); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("DeleteMediaQuestion on empty store: err = %v, want ErrMediaQuestionNotFound", err)
	}

	answeredAt := time.Date(2026, 8, 2, 12, 30, 0, 0, time.UTC)
	created := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	first := MediaQuestion{
		ID: "q-1", RunID: "run-1", MediaName: "新华网", Question: "A",
		AnsweredAt: &answeredAt, Metadata: map[string]any{"k": "v"},
		CreatedAt: created, UpdatedAt: created,
	}
	if err := store.CreateMediaQuestion(ctx, first); err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	replacement := MediaQuestion{
		ID: "q-1", RunID: "run-1", MediaName: "澎湃新闻", Question: "B",
		Metadata: map[string]any{}, CreatedAt: created, UpdatedAt: created.Add(time.Hour),
	}
	if err := store.UpdateMediaQuestion(ctx, replacement); err != nil {
		t.Fatalf("UpdateMediaQuestion: %v", err)
	}

	got, err := store.GetMediaQuestion(ctx, "run-1", "q-1")
	if err != nil {
		t.Fatalf("GetMediaQuestion: %v", err)
	}
	if got.MediaName != "澎湃新闻" || got.Question != "B" || got.AnsweredAt != nil || len(got.Metadata) != 0 {
		t.Fatalf("question = %+v, want the replacement", got)
	}

	// 返回副本：修改返回值（含 answered_at 指针指向的值）不影响存储。
	got.Metadata = map[string]any{"mutated": true}
	again, err := store.GetMediaQuestion(ctx, "run-1", "q-1")
	if err != nil {
		t.Fatalf("GetMediaQuestion again: %v", err)
	}
	if len(again.Metadata) != 0 {
		t.Fatalf("store was mutated through the returned copy: %+v", again.Metadata)
	}
	clonedAt := time.Date(2026, 8, 2, 13, 0, 0, 0, time.UTC)
	if err := store.CreateMediaQuestion(ctx, MediaQuestion{ID: "q-2", RunID: "run-1", MediaName: "新华网", Question: "C", AnsweredAt: &clonedAt}); err != nil {
		t.Fatalf("CreateMediaQuestion q-2: %v", err)
	}
	got2, err := store.GetMediaQuestion(ctx, "run-1", "q-2")
	if err != nil {
		t.Fatalf("GetMediaQuestion q-2: %v", err)
	}
	*got2.AnsweredAt = time.Date(2000, 1, 1, 0, 0, 0, 0, time.UTC)
	again2, err := store.GetMediaQuestion(ctx, "run-1", "q-2")
	if err != nil {
		t.Fatalf("GetMediaQuestion q-2 again: %v", err)
	}
	if !again2.AnsweredAt.Equal(clonedAt) {
		t.Fatalf("answered_at = %v, want the stored %v (must be cloned)", *again2.AnsweredAt, clonedAt)
	}

	// 其他 run 的问题不属于本 run：Get/Delete 均 ErrMediaQuestionNotFound。
	if _, err := store.GetMediaQuestion(ctx, "run-2", "q-1"); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("GetMediaQuestion of another run: err = %v, want ErrMediaQuestionNotFound", err)
	}
	if err := store.DeleteMediaQuestion(ctx, "run-2", "q-1"); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("DeleteMediaQuestion of another run: err = %v, want ErrMediaQuestionNotFound", err)
	}

	if err := store.DeleteMediaQuestion(ctx, "run-1", "q-1"); err != nil {
		t.Fatalf("DeleteMediaQuestion: %v", err)
	}
	if _, err := store.GetMediaQuestion(ctx, "run-1", "q-1"); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("GetMediaQuestion after delete: err = %v, want ErrMediaQuestionNotFound", err)
	}
}

// ListMediaQuestions：仅返回本 run 的问题；question_type/status 筛选生效；
// 排序 created_at ASC, id ASC（id 决胜）；limit/offset 分页生效，total 为
// 匹配总数。
func TestInMemoryStoreListMediaQuestions(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	questions := []MediaQuestion{
		{ID: "q-1", RunID: "run-1", MediaName: "新华网", Question: "事实问题", QuestionType: QuestionTypeFactual, Status: AnswerStatusPending, CreatedAt: base},
		{ID: "q-2", RunID: "run-1", MediaName: "澎湃新闻", Question: "质疑问题", QuestionType: QuestionTypeChallenging, Status: AnswerStatusAnswered, CreatedAt: base.Add(time.Second)},
		{ID: "q-3", RunID: "run-1", MediaName: "南方都市报", Question: "尖锐问题", QuestionType: QuestionTypeSharp, Status: AnswerStatusPending, CreatedAt: base.Add(2 * time.Second)},
		{ID: "q-4", RunID: "run-1", MediaName: "新华网", Question: "同刻问题", QuestionType: QuestionTypeFactual, Status: AnswerStatusPending, CreatedAt: base.Add(2 * time.Second)},
		{ID: "q-x", RunID: "run-2", MediaName: "新华网", Question: "其他run", Status: AnswerStatusPending, CreatedAt: base},
	}
	for _, question := range questions {
		if err := store.CreateMediaQuestion(ctx, question); err != nil {
			t.Fatalf("CreateMediaQuestion(%s): %v", question.ID, err)
		}
	}

	// 全部（run-1）：created_at ASC，同刻按 id ASC。
	records, total, err := store.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMediaQuestions: %v", err)
	}
	if total != 4 || len(records) != 4 {
		t.Fatalf("total/len = %d/%d, want 4/4", total, len(records))
	}
	wantIDs := []string{"q-1", "q-2", "q-3", "q-4"} // q-3 与 q-4 同刻，id 升序（"q-3" < "q-4"）
	for i, want := range wantIDs {
		if records[i].ID != want {
			t.Fatalf("records[%d] = %q, want %q (created_at ASC, id ASC)", i, records[i].ID, want)
		}
	}

	// question_type 筛选。
	records, total, err = store.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{QuestionType: QuestionTypeFactual, Limit: 50})
	if err != nil {
		t.Fatalf("ListMediaQuestions type filter: %v", err)
	}
	if total != 2 || records[0].ID != "q-1" || records[1].ID != "q-4" {
		t.Fatalf("factual filter = %+v (total %d), want q-1, q-4", records, total)
	}

	// status 筛选。
	records, total, err = store.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{Status: AnswerStatusAnswered, Limit: 50})
	if err != nil {
		t.Fatalf("ListMediaQuestions status filter: %v", err)
	}
	if total != 1 || records[0].ID != "q-2" {
		t.Fatalf("answered filter = %+v (total %d), want q-2", records, total)
	}

	// 筛选与分页组合：limit=1 offset=1 取第二条（q-2），total 保持 2。
	records, total, err = store.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{
		QuestionType: QuestionTypeFactual, Limit: 1, Offset: 1,
	})
	if err != nil {
		t.Fatalf("ListMediaQuestions combined: %v", err)
	}
	if total != 2 || len(records) != 1 || records[0].ID != "q-4" {
		t.Fatalf("combined page = %+v (total %d), want q-4", records, total)
	}

	// 越界 offset：空页、total 保持。
	records, total, err = store.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{Offset: 100, Limit: 50})
	if err != nil {
		t.Fatalf("ListMediaQuestions offset: %v", err)
	}
	if total != 4 || len(records) != 0 {
		t.Fatalf("offset page = %+v (total %d), want empty page with total 4", records, total)
	}
}

// DeleteByRun 清空该 run 的全部问答（与其他 opinion 对象一并清理），其他
// run 的问答保留；无问答可清时不是错误。
func TestInMemoryStoreDeleteByRunClearsMediaQuestions(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for _, runID := range []string{"run-1", "run-1", "run-2"} {
		if err := store.CreateMediaQuestion(ctx, MediaQuestion{ID: "q-" + runID, RunID: runID, MediaName: "新华网", Question: "Q"}); err != nil {
			t.Fatalf("CreateMediaQuestion(%s): %v", runID, err)
		}
	}
	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	records, total, err := store.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{})
	if err != nil || total != 0 || len(records) != 0 {
		t.Fatalf("list run-1 after DeleteByRun = %+v (total %d), err = %v; want empty", records, total, err)
	}
	if _, err := store.GetMediaQuestion(ctx, "run-2", "q-run-2"); err != nil {
		t.Fatalf("GET run-2: err = %v; want the untouched question", err)
	}
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
