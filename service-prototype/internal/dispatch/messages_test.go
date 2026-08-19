package dispatch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ─── CreateMessage ───────────────────────────────────────────────────

// 首次发送：完整对象，id 为 26 位 Crockford Base32 ULID，run_id 回显，
// sender_type/sender_name/content 透传，sent_at 服务端创建时设置非空
// （恒非空回显），created_by 缺省空串，created_at/updated_at 为服务端
// 时间且相等。
func TestCreateMessageSendsWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	message, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeCommand,
		Content:    "东区出现大客流聚集，请现场人员立即疏导",
	})
	if err != nil {
		t.Fatalf("CreateMessage: %v", err)
	}
	if !crockford26.MatchString(message.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", message.ID)
	}
	if message.RunID != "run-1" {
		t.Fatalf("run_id = %q, want the run from the caller", message.RunID)
	}
	if message.SenderType != SenderTypeCommand {
		t.Fatalf("sender_type = %q, want the input echoed", message.SenderType)
	}
	if message.Content != "东区出现大客流聚集，请现场人员立即疏导" {
		t.Fatalf("content = %q, want the input echoed", message.Content)
	}
	if message.SenderName != "" {
		t.Fatalf("sender_name = %q, want an empty default", message.SenderName)
	}
	if message.SentAt == nil {
		t.Fatalf("sent_at = nil, want the server-set creation instant")
	}
	if message.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", message.CreatedBy)
	}
	if message.CreatedAt.IsZero() || !message.CreatedAt.Equal(message.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want server time and equal", message.CreatedAt, message.UpdatedAt)
	}
}

// 显式字段：sender_type=现场人员、sender_name、content、created_by 原样
// 写入；sent_at 由服务端设置（与创建时刻一致，请求无法覆盖）。
func TestCreateMessageExplicitFields(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	message, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeField,
		SenderName: "安保一组 张伟",
		Content:    "收到，3 号出口已就位",
		CreatedBy:  "u-commander",
	})
	if err != nil {
		t.Fatalf("CreateMessage: %v", err)
	}
	if message.SenderType != SenderTypeField || message.SenderName != "安保一组 张伟" ||
		message.Content != "收到，3 号出口已就位" || message.CreatedBy != "u-commander" {
		t.Fatalf("message = %+v, want the explicit fields echoed", message)
	}
	if message.SentAt == nil {
		t.Fatalf("sent_at = nil, want the server-set creation instant")
	}
}

// 失败路径：缺 sender_type（含空白）、非法 sender_type（非 指挥中心/现场
// 人员）、缺 content（含空白）→ ValidationError。
func TestCreateMessageValidation(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	cases := []struct {
		name  string
		input MessageInput
	}{
		{"missing sender_type", MessageInput{Content: "内容"}},
		{"blank sender_type", MessageInput{SenderType: SenderType(" "), Content: "内容"}},
		{"invalid sender_type", MessageInput{SenderType: SenderType("游客"), Content: "内容"}},
		{"missing content", MessageInput{SenderType: SenderTypeCommand}},
		{"blank content", MessageInput{SenderType: SenderTypeCommand, Content: "  "}},
	}
	for _, testCase := range cases {
		_, err := service.CreateMessage(context.Background(), "run-1", testCase.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}
}

// 状态约束：run 不存在 → ErrRunNotFound；仅 进行中 可 POST（未开始/已完
// 成/已终止 400）；GET 列表与单条不受门控。
func TestCreateMessageRunState(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusNotStarted),
		run("run-3", drills.RunStatusCompleted),
		run("run-4", drills.RunStatusTerminated),
	)

	// run 不存在 → ErrRunNotFound。
	_, err := service.CreateMessage(context.Background(), "run-9", MessageInput{
		SenderType: SenderTypeCommand, Content: "内容",
	})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// 非 进行中 → ValidationError。
	for _, runID := range []string{"run-2", "run-3", "run-4"} {
		_, err := service.CreateMessage(context.Background(), runID, MessageInput{
			SenderType: SenderTypeCommand, Content: "内容",
		})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", runID, err)
		}
	}

	// 进行中 → 成功。
	if _, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeField, Content: "已到位",
	}); err != nil {
		t.Fatalf("in-progress run: %v", err)
	}
}

// ─── GetMessage / DeleteMessage ──────────────────────────────────────

// GetMessage：存在 → 完整对象；消息不存在 → ErrMessageNotFound；run 不
// 存在 → ErrRunNotFound；GET 不受门控（已完成 run 的消息仍可读）。
func TestGetMessage(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-3", drills.RunStatusCompleted),
	)
	created, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeCommand, Content: "消息一",
	})
	if err != nil {
		t.Fatalf("CreateMessage: %v", err)
	}

	got, err := service.GetMessage(context.Background(), "run-1", created.ID)
	if err != nil {
		t.Fatalf("GetMessage: %v", err)
	}
	if got.ID != created.ID || got.Content != "消息一" || got.SentAt == nil {
		t.Fatalf("GetMessage = %+v, want the created message", got)
	}
	if _, err := service.GetMessage(context.Background(), "run-1", "no-such-id"); !errors.Is(err, ErrMessageNotFound) {
		t.Fatalf("missing message: err = %v, want ErrMessageNotFound", err)
	}
	if _, err := service.GetMessage(context.Background(), "run-9", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// 已完成 run 的 GET 不受门控。
	if _, err := service.GetMessage(context.Background(), "run-3", "no-such-id"); !errors.Is(err, ErrMessageNotFound) {
		t.Fatalf("completed run GET: err = %v, want ErrMessageNotFound (not a gate error)", err)
	}
}

// DeleteMessage：成功删除；消息不存在 → ErrMessageNotFound；run 不存在 →
// ErrRunNotFound（优先于门控）；非 进行中 → ValidationError。
func TestDeleteMessage(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusCompleted),
	)
	created, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeCommand, Content: "待删除",
	})
	if err != nil {
		t.Fatalf("CreateMessage: %v", err)
	}

	if err := service.DeleteMessage(context.Background(), "run-1", created.ID); err != nil {
		t.Fatalf("DeleteMessage: %v", err)
	}
	if _, err := service.GetMessage(context.Background(), "run-1", created.ID); !errors.Is(err, ErrMessageNotFound) {
		t.Fatalf("message after delete: err = %v, want ErrMessageNotFound", err)
	}
	if err := service.DeleteMessage(context.Background(), "run-1", created.ID); !errors.Is(err, ErrMessageNotFound) {
		t.Fatalf("delete again: err = %v, want ErrMessageNotFound", err)
	}
	if err := service.DeleteMessage(context.Background(), "run-9", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// 非 进行中 → ValidationError（门控先于消息存在性检查，同 orders）。
	if err := service.DeleteMessage(context.Background(), "run-2", created.ID); !errors.Is(err, ErrMessageNotFound) {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("completed run delete: err = %v, want ValidationError (gate precedes message check)", err)
		}
	}
}

// ─── ListMessages ────────────────────────────────────────────────────

// 空列表：records 为 nil/空、total 为 0；run 不存在 → ErrRunNotFound；
// GET 不受门控（已完成 run 仍可列出）。
func TestListMessagesEmptyAndRunState(t *testing.T) {
	service, _ := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-3", drills.RunStatusCompleted),
	)

	records, total, err := service.ListMessages(context.Background(), "run-1", MessageFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMessages: %v", err)
	}
	if len(records) != 0 || total != 0 {
		t.Fatalf("empty run: %d records / %d total, want 0 / 0", len(records), total)
	}
	if _, _, err := service.ListMessages(context.Background(), "run-9", MessageFilter{Limit: 50}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	// 已完成 run 的 GET 不受门控。
	if _, _, err := service.ListMessages(context.Background(), "run-3", MessageFilter{Limit: 50}); err != nil {
		t.Fatalf("completed run list: %v, want no gate error", err)
	}
}

// 筛选 sender_type 生效；排序 created_at ASC、同刻 id ASC（聊天顺序）；
// limit/offset 分页生效。
func TestListMessagesFilterSortAndPagination(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	// 同一 created_at 的并列：id ASC → ...0A 在前。
	messages := []Message{
		{
			ID: "0000000000000000000000000C", RunID: "run-1", SenderType: SenderTypeCommand,
			SenderName: "指挥中心", Content: "第三条", SentAt: &now, CreatedAt: now.Add(2 * time.Minute), UpdatedAt: now.Add(2 * time.Minute),
		},
		{
			ID: "0000000000000000000000000B", RunID: "run-1", SenderType: SenderTypeField,
			SenderName: "张伟", Content: "第二条", SentAt: &now, CreatedAt: now.Add(time.Minute), UpdatedAt: now.Add(time.Minute),
		},
		{
			ID: "0000000000000000000000000A", RunID: "run-1", SenderType: SenderTypeCommand,
			SenderName: "指挥中心", Content: "第一条", SentAt: &now, CreatedAt: now, UpdatedAt: now,
		},
		{
			ID: "0000000000000000000000000D", RunID: "run-2", SenderType: SenderTypeCommand,
			SenderName: "指挥中心", Content: "别的 run", SentAt: &now, CreatedAt: now, UpdatedAt: now,
		},
	}
	for _, message := range messages {
		if err := store.CreateMessage(context.Background(), message); err != nil {
			t.Fatalf("CreateMessage %s: %v", message.ID, err)
		}
	}

	// 全量：created_at ASC（第一条→第二条→第三条），total=3（run-2 排除）。
	records, total, err := store.ListMessages(context.Background(), "run-1", MessageFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMessages: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("records = %d, total = %d; want 3 / 3", len(records), total)
	}
	if records[0].ID != "0000000000000000000000000A" || records[1].ID != "0000000000000000000000000B" ||
		records[2].ID != "0000000000000000000000000C" {
		t.Fatalf("chat order broken: %+v", records)
	}

	// 筛选 sender_type=现场人员：仅第二条，total=1。
	records, total, err = store.ListMessages(context.Background(), "run-1", MessageFilter{SenderType: SenderTypeField, Limit: 50})
	if err != nil {
		t.Fatalf("ListMessages filtered: %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].Content != "第二条" {
		t.Fatalf("sender_type filter: %d records / %d total, want 1 / 1 with 第二条", len(records), total)
	}

	// 分页 limit=2&offset=1：第二条+第三条，total 仍为 3。
	records, total, err = store.ListMessages(context.Background(), "run-1", MessageFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListMessages paginated: %v", err)
	}
	if total != 3 || len(records) != 2 || records[0].Content != "第二条" || records[1].Content != "第三条" {
		t.Fatalf("pagination: %d records / %d total, want 2 / 3 (第二条, 第三条)", len(records), total)
	}
}

// 同一 created_at 的消息按 id ASC 排序（并列次序，聊天顺序的稳定尾键）。
func TestStoreListMessagesTieSortsByIDAscending(t *testing.T) {
	store := NewInMemoryStore()
	now := fixedTime
	first := Message{
		ID: "0000000000000000000000000Z", RunID: "run-1", SenderType: SenderTypeCommand,
		Content: "后", SentAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	second := Message{
		ID: "0000000000000000000000000A", RunID: "run-1", SenderType: SenderTypeCommand,
		Content: "前", SentAt: &now, CreatedAt: now, UpdatedAt: now,
	}
	if err := store.CreateMessage(context.Background(), first); err != nil {
		t.Fatalf("CreateMessage first: %v", err)
	}
	if err := store.CreateMessage(context.Background(), second); err != nil {
		t.Fatalf("CreateMessage second: %v", err)
	}

	records, total, err := store.ListMessages(context.Background(), "run-1", MessageFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMessages: %v", err)
	}
	if total != 2 || len(records) != 2 {
		t.Fatalf("records = %d, total = %d; want 2 / 2", len(records), total)
	}
	// 相同的 created_at：id ASC → second（...0A）在前。
	if records[0].ID != second.ID || records[1].ID != first.ID {
		t.Fatalf("ties must sort by id ASC: %+v", records)
	}
}

// ─── 级联清理入口 ────────────────────────────────────────────────────

// DeleteMessagesByRun 只删除该 run 的消息；删除不存在的 run 不是错误；
// 与其他 run 的消息互不影响。
func TestStoreDeleteMessagesByRun(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	if _, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeCommand, Content: "消息一",
	}); err != nil {
		t.Fatalf("CreateMessage run-1: %v", err)
	}
	if _, err := service.CreateMessage(context.Background(), "run-1", MessageInput{
		SenderType: SenderTypeField, Content: "消息二",
	}); err != nil {
		t.Fatalf("CreateMessage run-1 second: %v", err)
	}
	other, err := service.CreateMessage(context.Background(), "run-2", MessageInput{
		SenderType: SenderTypeCommand, Content: "别的 run",
	})
	if err != nil {
		t.Fatalf("CreateMessage run-2: %v", err)
	}

	if err := store.DeleteMessagesByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteMessagesByRun: %v", err)
	}
	records, total, err := service.ListMessages(context.Background(), "run-1", MessageFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMessages run-1 after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("run-1 after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if message, err := store.GetMessage(context.Background(), "run-2", other.ID); err != nil || message.ID != other.ID {
		t.Fatalf("run-2 after cascade: message = %+v, err = %v; want untouched", message, err)
	}

	// 无消息可删不是错误。
	if err := store.DeleteMessagesByRun(context.Background(), "run-9"); err != nil {
		t.Fatalf("DeleteMessagesByRun on empty: %v", err)
	}
}

// ─── run 删除级联（与 drills.DeleteRun 接线）──────────────────────────

// 把 dispatch store 作为 drills 服务的 RunSessionCleaner 接线后，删除
// run 会级联清空其消息（与 DB 的 ON DELETE CASCADE 一致）；其他 run 的
// 消息保留。
func TestDeleteRunCascadesToMessages(t *testing.T) {
	drillStore := drills.NewInMemoryStore()
	drillService := drills.NewService(drillStore)
	dispatchStore := NewInMemoryStore()
	drillService.SetRunSessionCleaner(dispatchStore)

	scenario, err := drillService.CreateScenario(context.Background(), drills.ScenarioInput{
		Name: "火警疏散", Category: drills.CategoryFire, Background: "背景",
	})
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	makeRun := func(title string) drills.Run {
		run, err := drillService.CreateRun(context.Background(), drills.RunInput{ScenarioID: scenario.ID, Title: title})
		if err != nil {
			t.Fatalf("CreateRun %s: %v", title, err)
		}
		if _, err := drillService.StartRun(context.Background(), run.ID); err != nil {
			t.Fatalf("StartRun %s: %v", title, err)
		}
		return run
	}
	runA := makeRun("演练A")
	runB := makeRun("演练B")

	dispatchService := NewService(dispatchStore, NewRunSource(drillStore))
	if _, err := dispatchService.CreateMessage(context.Background(), runA.ID, MessageInput{
		SenderType: SenderTypeCommand, Content: "现场情况如何",
	}); err != nil {
		t.Fatalf("CreateMessage runA: %v", err)
	}
	if _, err := dispatchService.CreateMessage(context.Background(), runA.ID, MessageInput{
		SenderType: SenderTypeField, Content: "一切正常",
	}); err != nil {
		t.Fatalf("CreateMessage runA second: %v", err)
	}
	messageB, err := dispatchService.CreateMessage(context.Background(), runB.ID, MessageInput{
		SenderType: SenderTypeCommand, Content: "B 的消息",
	})
	if err != nil {
		t.Fatalf("CreateMessage runB: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), runA.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}

	// runA 的消息清空（run 已删除，直接用 store 断言）。
	records, total, err := dispatchStore.ListMessages(context.Background(), runA.ID, MessageFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListMessages runA after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("runA after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if _, err := dispatchStore.GetMessage(context.Background(), runA.ID, "any"); !errors.Is(err, ErrMessageNotFound) {
		t.Fatalf("runA message after cascade: err = %v, want ErrMessageNotFound", err)
	}

	// runB 的消息保留。
	if message, err := dispatchStore.GetMessage(context.Background(), runB.ID, messageB.ID); err != nil || message.ID != messageB.ID {
		t.Fatalf("runB after cascade: message = %+v, err = %v; want untouched", message, err)
	}
}

// 未接线 cleaner 时删除 run 不触碰消息（最小增量扩展，未接线行为不变）。
func TestDeleteRunWithoutCleanerKeepsMessages(t *testing.T) {
	drillStore := drills.NewInMemoryStore()
	drillService := drills.NewService(drillStore)
	dispatchStore := NewInMemoryStore()

	scenario, err := drillService.CreateScenario(context.Background(), drills.ScenarioInput{
		Name: "火警疏散", Category: drills.CategoryFire, Background: "背景",
	})
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}
	run, err := drillService.CreateRun(context.Background(), drills.RunInput{ScenarioID: scenario.ID, Title: "演练"})
	if err != nil {
		t.Fatalf("CreateRun: %v", err)
	}
	if _, err := drillService.StartRun(context.Background(), run.ID); err != nil {
		t.Fatalf("StartRun: %v", err)
	}
	dispatchService := NewService(dispatchStore, NewRunSource(drillStore))
	message, err := dispatchService.CreateMessage(context.Background(), run.ID, MessageInput{
		SenderType: SenderTypeCommand, Content: "保留的消息",
	})
	if err != nil {
		t.Fatalf("CreateMessage: %v", err)
	}

	if err := drillService.DeleteRun(context.Background(), run.ID); err != nil {
		t.Fatalf("DeleteRun: %v", err)
	}
	if got, err := dispatchStore.GetMessage(context.Background(), run.ID, message.ID); err != nil || got.ID != message.ID {
		t.Fatalf("message after un-wired delete: got = %+v, err = %v; want untouched", got, err)
	}
}
