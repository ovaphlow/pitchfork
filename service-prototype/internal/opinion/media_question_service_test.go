package opinion

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// inProgressMediaQuestionService returns a service over a store and a
// single 进行中 run named run-1, plus the store for direct assertions.
func inProgressMediaQuestionService() (*Service, *InMemoryStore) {
	return newTestService(run("run-1", drills.RunStatusInProgress))
}

// ─── CreateMediaQuestion ─────────────────────────────────────────────

// 合法创建：id 为 26 位 Crockford Base32 ULID、run_id 注入、media_name/
// question 必填透传、reporter 缺省 ”、question_type 缺省 事实类、answer
// 缺省 ”、status 缺省 未回答、answered_at nil、metadata {}、created_by ”、
// created_at/updated_at 服务端时间且相等；显式字段原样保留。
func TestCreateMediaQuestionDefaults(t *testing.T) {
	service, store := inProgressMediaQuestionService()

	question, err := service.CreateMediaQuestion(context.Background(), "run-1", MediaQuestionInput{
		MediaName: "新华网",
		Question:  "请问本次事件的起因是什么？",
	})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	if !crockford26.MatchString(question.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", question.ID)
	}
	if question.RunID != "run-1" {
		t.Fatalf("run_id = %q, want run-1", question.RunID)
	}
	if question.MediaName != "新华网" || question.Question != "请问本次事件的起因是什么？" {
		t.Fatalf("media_name/question = %q / %q, want the provided values", question.MediaName, question.Question)
	}
	if question.Reporter != "" || question.QuestionType != QuestionTypeFactual ||
		question.Answer != "" || question.Status != AnswerStatusPending ||
		question.AnsweredAt != nil || question.Metadata == nil || len(question.Metadata) != 0 ||
		question.CreatedBy != "" {
		t.Fatalf("question = %+v, want the defaults", question)
	}
	if question.CreatedAt.IsZero() || !question.CreatedAt.Equal(question.UpdatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want server time and equal", question.CreatedAt, question.UpdatedAt)
	}
	// 已写入 store。
	stored, err := store.GetMediaQuestion(context.Background(), "run-1", question.ID)
	if err != nil || stored.Question != question.Question {
		t.Fatalf("stored = %+v, err = %v; want the created question", stored, err)
	}
}

// 显式字段透传；显式 已回答 在创建时拒绝（400 语义）。
func TestCreateMediaQuestionExplicitFieldsAndRejectsAnswered(t *testing.T) {
	service, _ := inProgressMediaQuestionService()
	ctx := context.Background()

	question, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{
		MediaName:    "澎湃新闻",
		Reporter:     "记者小王",
		Question:     "网上流传的视频是否属实？",
		QuestionType: QuestionTypeSharp,
		Answer:       "该视频经核实存在断章取义……",
		Metadata:     map[string]any{"platform": "press"},
		CreatedBy:    "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	if question.MediaName != "澎湃新闻" || question.Reporter != "记者小王" ||
		question.QuestionType != QuestionTypeSharp || question.Answer != "该视频经核实存在断章取义……" ||
		question.Metadata["platform"] != "press" || question.CreatedBy != "u-admin" {
		t.Fatalf("question = %+v, want the provided values", question)
	}

	_, err = service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A", Status: AnswerStatusAnswered})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("explicit 已回答 on create: err = %v, want a ValidationError", err)
	}
}

// 写门控与 run 存在性：run 不存在 → ErrRunNotFound；未开始/已完成/已终止
// → ValidationError（400 语义）。
func TestCreateMediaQuestionRunChecks(t *testing.T) {
	service, _ := newTestService(
		run("not-started", drills.RunStatusNotStarted),
		run("completed", drills.RunStatusCompleted),
		run("terminated", drills.RunStatusTerminated),
	)
	ctx := context.Background()

	_, err := service.CreateMediaQuestion(ctx, "missing", MediaQuestionInput{MediaName: "新华网", Question: "A"})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for _, runID := range []string{"not-started", "completed", "terminated"} {
		_, err := service.CreateMediaQuestion(ctx, runID, MediaQuestionInput{MediaName: "新华网", Question: "A"})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("run %s: err = %v, want a ValidationError", runID, err)
		}
	}
}

// ─── GetMediaQuestion / ListMediaQuestions ───────────────────────────

// GetMediaQuestion：存在 → 200 语义完整对象；不存在 → ErrMediaQuestionNotFound；
// run 不存在 → ErrRunNotFound；GET 不受写门控（已完成 run 仍可读）。
func TestGetMediaQuestion(t *testing.T) {
	service, store := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("done", drills.RunStatusCompleted),
	)
	ctx := context.Background()

	created, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A"})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	got, err := service.GetMediaQuestion(ctx, "run-1", created.ID)
	if err != nil || got.ID != created.ID || got.Question != "A" {
		t.Fatalf("GetMediaQuestion = %+v, err = %v; want the created question", got, err)
	}

	if _, err := service.GetMediaQuestion(ctx, "run-1", "missing"); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("missing question: err = %v, want ErrMediaQuestionNotFound", err)
	}
	if _, err := service.GetMediaQuestion(ctx, "missing", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// GET 不受写门控：已完成 run 直接经 store 注入问题后仍可读。
	if err := store.CreateMediaQuestion(ctx, MediaQuestion{ID: "q-done", RunID: "done", MediaName: "新华网", Question: "B"}); err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	got, err = service.GetMediaQuestion(ctx, "done", "q-done")
	if err != nil || got.Question != "B" {
		t.Fatalf("GetMediaQuestion on completed run = %+v, err = %v; want 200 semantics", got, err)
	}
}

// ListMediaQuestions：run 不存在 → ErrRunNotFound；已完成 run 仍可列表
// （GET 不受门控）；筛选/分页透传到 store。
func TestListMediaQuestions(t *testing.T) {
	service, store := newTestService(run("done", drills.RunStatusCompleted))
	ctx := context.Background()

	if _, _, err := service.ListMediaQuestions(ctx, "missing", MediaQuestionFilter{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for i := 0; i < 3; i++ {
		if err := store.CreateMediaQuestion(ctx, MediaQuestion{ID: "q" + string(rune('a'+i)), RunID: "done", MediaName: "新华网", Question: "C"}); err != nil {
			t.Fatalf("CreateMediaQuestion: %v", err)
		}
	}
	records, total, err := service.ListMediaQuestions(ctx, "done", MediaQuestionFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListMediaQuestions: %v", err)
	}
	if total != 3 || len(records) != 2 {
		t.Fatalf("total/len = %d/%d, want 3/2", total, len(records))
	}
}

// 列表排序 created_at ASC, id ASC（发布会提问顺序）可断言：依次创建三条
// （间隔 sleep 保证毫秒级时间可区分），列表按创建正序返回。
func TestListMediaQuestionsSortedQuestionOrder(t *testing.T) {
	service, _ := inProgressMediaQuestionService()
	ctx := context.Background()

	first, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "第一条"})
	if err != nil {
		t.Fatalf("create first: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	second, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "第二条"})
	if err != nil {
		t.Fatalf("create second: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	third, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "第三条"})
	if err != nil {
		t.Fatalf("create third: %v", err)
	}

	records, total, err := service.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMediaQuestions: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", total, len(records))
	}
	wantOrder := []MediaQuestion{first, second, third}
	for i, want := range wantOrder {
		if records[i].ID != want.ID {
			t.Fatalf("records[%d] = %q, want %q (created_at ASC)", i, records[i].ID, want.ID)
		}
		if i > 0 && records[i].CreatedAt.Before(records[i-1].CreatedAt) {
			t.Fatalf("created_at not ascending: %s then %s", records[i-1].CreatedAt, records[i].CreatedAt)
		}
	}
}

// ─── UpdateMediaQuestion：部分更新与回答状态机 ───────────────────────

// 部分更新语义：缺省字段保持原值（reporter/question_type/answer/status/
// metadata/created_by）；media_name/question 双入口必填；显式字段生效；
// updated_at 刷新、id/run_id/created_at 不变；PUT 后 GET 反映更新。
func TestUpdateMediaQuestionPartialUpdate(t *testing.T) {
	service, _ := inProgressMediaQuestionService()
	ctx := context.Background()

	created, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{
		MediaName:    "新华网",
		Reporter:     "记者小王",
		Question:     "原问题",
		QuestionType: QuestionTypeFactual,
		Answer:       "原回答",
		Metadata:     map[string]any{"k": "v"},
		CreatedBy:    "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	// 只改 question：其余字段保持。
	updated, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网",
		Question:  "新问题",
	})
	if err != nil {
		t.Fatalf("UpdateMediaQuestion: %v", err)
	}
	if updated.ID != created.ID || updated.RunID != "run-1" || !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("id/run_id/created_at must be preserved: %+v", updated)
	}
	if updated.Question != "新问题" || updated.MediaName != "新华网" || updated.Reporter != "记者小王" ||
		updated.QuestionType != QuestionTypeFactual || updated.Answer != "原回答" ||
		updated.Status != AnswerStatusPending || updated.AnsweredAt != nil ||
		updated.Metadata["k"] != "v" || updated.CreatedBy != "u-admin" {
		t.Fatalf("partial update did not keep the untouched fields: %+v", updated)
	}
	if updated.UpdatedAt.Before(createdAt) || updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at = %v, want a refreshed value", updated.UpdatedAt)
	}

	// 显式 reporter/question_type/answer/metadata（含 {} 边界值）生效；
	// answer 可随时修订。
	updated, err = service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName:    "新华网",
		Question:     "新问题",
		Reporter:     "记者小李",
		QuestionType: QuestionTypeSharp,
		Answer:       "修订后的回答",
		Metadata:     map[string]any{},
		HasMetadata:  true,
	})
	if err != nil {
		t.Fatalf("UpdateMediaQuestion fields: %v", err)
	}
	if updated.Reporter != "记者小李" || updated.QuestionType != QuestionTypeSharp ||
		updated.Answer != "修订后的回答" || len(updated.Metadata) != 0 {
		t.Fatalf("explicit fields not applied: %+v", updated)
	}
	updated, err = service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网",
		Question:  "新问题",
		CreatedBy: "u-2",
	})
	if err != nil {
		t.Fatalf("UpdateMediaQuestion created_by: %v", err)
	}
	if updated.CreatedBy != "u-2" {
		t.Fatalf("explicit created_by not applied: %+v", updated)
	}

	// 更新已持久化：再 GET 反映更新。
	fetched, err := service.GetMediaQuestion(ctx, "run-1", created.ID)
	if err != nil || fetched.Question != "新问题" || fetched.Reporter != "记者小李" ||
		fetched.Answer != "修订后的回答" || fetched.CreatedBy != "u-2" {
		t.Fatalf("GET after PUT = %+v, err = %v; want the updated values", fetched, err)
	}
}

// 双入口必填：media_name/question 缺省 → ValidationError；非法
// question_type/status → ValidationError；已回答→未回答 回退 400。
func TestUpdateMediaQuestionValidation(t *testing.T) {
	service, _ := inProgressMediaQuestionService()
	ctx := context.Background()

	created, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A"})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}

	if _, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{Question: "A"}); !errors.As(err, &validationError) {
		t.Fatalf("missing media_name: err = %v, want a ValidationError", err)
	}
	if _, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{MediaName: "新华网"}); !errors.As(err, &validationError) {
		t.Fatalf("missing question: err = %v, want a ValidationError", err)
	}
	if _, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "A", QuestionType: "诱导类",
	}); !errors.As(err, &validationError) {
		t.Fatalf("invalid question_type: err = %v, want a ValidationError", err)
	}
	if _, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "A", Status: "回答中",
	}); !errors.As(err, &validationError) {
		t.Fatalf("invalid status: err = %v, want a ValidationError", err)
	}

	// 已回答 → 未回答 回退 400。
	if _, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "A", Status: AnswerStatusAnswered,
	}); err != nil {
		t.Fatalf("answer transition: %v", err)
	}
	if _, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "A", Status: AnswerStatusPending,
	}); !errors.As(err, &validationError) {
		t.Fatalf("已回答 -> 未回答: err = %v, want a ValidationError", err)
	}
}

// 回答状态机（PUT 入口）：未回答 → 已回答 设置 answered_at；同值 已回答
// no-op 合法且 answered_at 保持不变（不重置）；同值 未回答 no-op 合法且
// answered_at 保持 nil；PUT 未涉及 status 时 answered_at 保持原值。
func TestUpdateMediaQuestionAnswerStateMachine(t *testing.T) {
	service, _ := inProgressMediaQuestionService()
	ctx := context.Background()

	created, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A"})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	if created.AnsweredAt != nil {
		t.Fatalf("answered_at = %v at creation, want nil", created.AnsweredAt)
	}

	// 未回答 → 已回答：answered_at 被服务端设置。
	time.Sleep(5 * time.Millisecond)
	answered, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "A", Status: AnswerStatusAnswered,
	})
	if err != nil {
		t.Fatalf("answer transition: %v", err)
	}
	if answered.Status != AnswerStatusAnswered || answered.AnsweredAt == nil {
		t.Fatalf("answered = %+v, want 已回答 with a server-set answered_at", answered)
	}
	answeredAt := *answered.AnsweredAt

	// 同值 no-op：已回答 → 已回答 200，answered_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	again, err := service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "A", Status: AnswerStatusAnswered,
	})
	if err != nil {
		t.Fatalf("no-op: %v", err)
	}
	if again.Status != AnswerStatusAnswered || again.AnsweredAt == nil || !again.AnsweredAt.Equal(answeredAt) {
		t.Fatalf("no-op answered_at = %v, want the unchanged %v", again.AnsweredAt, answeredAt)
	}

	// PUT 未涉及 status：answered_at 保持原值。
	again, err = service.UpdateMediaQuestion(ctx, "run-1", created.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "B",
	})
	if err != nil {
		t.Fatalf("update without status: %v", err)
	}
	if again.AnsweredAt == nil || !again.AnsweredAt.Equal(answeredAt) {
		t.Fatalf("answered_at after unrelated update = %v, want %v", again.AnsweredAt, answeredAt)
	}

	// 同值 未回答 no-op：200 且 answered_at 保持 nil。
	pending, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "B"})
	if err != nil {
		t.Fatalf("create pending: %v", err)
	}
	noop, err := service.UpdateMediaQuestion(ctx, "run-1", pending.ID, MediaQuestionUpdate{
		MediaName: "新华网", Question: "B", Status: AnswerStatusPending,
	})
	if err != nil {
		t.Fatalf("pending no-op: %v", err)
	}
	if noop.Status != AnswerStatusPending || noop.AnsweredAt != nil {
		t.Fatalf("pending no-op = %+v, want 未回答 with nil answered_at", noop)
	}
}

// ─── DeleteMediaQuestion ─────────────────────────────────────────────

// DELETE 删除；再次 DELETE → ErrMediaQuestionNotFound（删除生效）；
// run 不存在 → ErrRunNotFound。
func TestDeleteMediaQuestion(t *testing.T) {
	service, _ := inProgressMediaQuestionService()
	ctx := context.Background()

	created, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A"})
	if err != nil {
		t.Fatalf("CreateMediaQuestion: %v", err)
	}
	if err := service.DeleteMediaQuestion(ctx, "run-1", created.ID); err != nil {
		t.Fatalf("DELETE: %v", err)
	}
	if _, err := service.GetMediaQuestion(ctx, "run-1", created.ID); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("GET after DELETE: err = %v, want ErrMediaQuestionNotFound", err)
	}
	if err := service.DeleteMediaQuestion(ctx, "run-1", created.ID); !errors.Is(err, ErrMediaQuestionNotFound) {
		t.Fatalf("DELETE again: err = %v, want ErrMediaQuestionNotFound", err)
	}
	if err := service.DeleteMediaQuestion(ctx, "missing", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("DELETE on missing run: err = %v, want ErrRunNotFound", err)
	}
}

// ─── run 不存在 / 写门控（全方法）────────────────────────────────────

// run 不存在：GET（列表与单条）/POST/PUT/DELETE 均 ErrRunNotFound。
func TestMediaQuestionRunNotFound(t *testing.T) {
	service, _ := newTestService()
	ctx := context.Background()

	if _, _, err := service.ListMediaQuestions(ctx, "missing", MediaQuestionFilter{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("GET list: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.CreateMediaQuestion(ctx, "missing", MediaQuestionInput{MediaName: "新华网", Question: "A"}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("POST: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.GetMediaQuestion(ctx, "missing", "q-1"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("GET item: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpdateMediaQuestion(ctx, "missing", "q-1", MediaQuestionUpdate{MediaName: "新华网", Question: "A"}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("PUT: err = %v, want ErrRunNotFound", err)
	}
	if err := service.DeleteMediaQuestion(ctx, "missing", "q-1"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("DELETE: err = %v, want ErrRunNotFound", err)
	}
}

// 写门控：仅 run 进行中 可写——未开始/已完成/已终止 时 POST/PUT/DELETE
// 均 ValidationError；GET（列表与单条）不受门控。
func TestMediaQuestionWriteGate(t *testing.T) {
	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusCompleted, drills.RunStatusTerminated} {
		service, store := newTestService(run("run-1", status))
		ctx := context.Background()

		if _, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A"}); !errors.As(err, &validationError) {
			t.Fatalf("%s POST: err = %v, want a ValidationError", status, err)
		}
		if err := store.CreateMediaQuestion(ctx, MediaQuestion{ID: "q-1", RunID: "run-1", MediaName: "新华网", Question: "A"}); err != nil {
			t.Fatalf("%s store seed: %v", status, err)
		}
		if _, err := service.UpdateMediaQuestion(ctx, "run-1", "q-1", MediaQuestionUpdate{MediaName: "新华网", Question: "B"}); !errors.As(err, &validationError) {
			t.Fatalf("%s PUT: err = %v, want a ValidationError", status, err)
		}
		if err := service.DeleteMediaQuestion(ctx, "run-1", "q-1"); !errors.As(err, &validationError) {
			t.Fatalf("%s DELETE: err = %v, want a ValidationError", status, err)
		}
		// GET 不受写门控。
		question, err := service.GetMediaQuestion(ctx, "run-1", "q-1")
		if err != nil || question.Question != "A" {
			t.Fatalf("%s GET: question = %+v, err = %v; want the seeded question", status, question, err)
		}
		if _, _, err := service.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{}); err != nil {
			t.Fatalf("%s GET list: %v", status, err)
		}
	}
}

// ─── 级联：删除 run 后问答记录随之清空 ───────────────────────────────

// 接线 DeleteByRun 清理入口：创建问答后删除 run，问答消失（内存行为与
// 迁移 ON DELETE CASCADE 一致）；其他 run 的问答不受影响；无问答可清时
// 不是错误。
func TestDeleteByRunCascadesMediaQuestions(t *testing.T) {
	service, store := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusInProgress),
	)
	ctx := context.Background()

	if _, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "新华网", Question: "A"}); err != nil {
		t.Fatalf("create run-1: %v", err)
	}
	if _, err := service.CreateMediaQuestion(ctx, "run-1", MediaQuestionInput{MediaName: "澎湃新闻", Question: "B"}); err != nil {
		t.Fatalf("create run-1 again: %v", err)
	}
	run2Question, err := service.CreateMediaQuestion(ctx, "run-2", MediaQuestionInput{MediaName: "新华网", Question: "C"})
	if err != nil {
		t.Fatalf("create run-2: %v", err)
	}

	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if records, total, err := service.ListMediaQuestions(ctx, "run-1", MediaQuestionFilter{}); err != nil || total != 0 || len(records) != 0 {
		t.Fatalf("list run-1 after DeleteByRun = %+v (total %d), err = %v; want empty", records, total, err)
	}
	// 其他 run 的问答不受影响。
	got, err := service.GetMediaQuestion(ctx, "run-2", run2Question.ID)
	if err != nil || got.Question != "C" {
		t.Fatalf("GET run-2: question = %+v, err = %v; want the untouched question", got, err)
	}
	// 无问答可清时不是错误。
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
