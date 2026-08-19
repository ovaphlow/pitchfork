package dispatch

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ─── CreateOrder ─────────────────────────────────────────────────────

// 首次创建：完整对象，id 为 26 位 Crockford Base32 ULID，run_id 回显，
// title/content/target_type/target_name 透传，priority 缺省 普通、status
// 缺省 待接收、feedback 缺省空串，deadline 缺省 null，issued_at 服务端
// 创建时设置非空、completed_at 为 null，created_by 缺省空串，
// created_at/updated_at 为服务端时间且相等。
func TestCreateOrderCreatesWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	order, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title:      "疏散东区游客",
		Content:    "引导东区游客经 3 号出口疏散",
		TargetType: TargetTypeDepartment,
		TargetName: "疏散组",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	if !crockford26.MatchString(order.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", order.ID)
	}
	if order.RunID != "run-1" {
		t.Fatalf("run_id = %q, want the run from the caller", order.RunID)
	}
	if order.Title != "疏散东区游客" || order.Content != "引导东区游客经 3 号出口疏散" {
		t.Fatalf("title/content = %q / %q, want the input echoed", order.Title, order.Content)
	}
	if order.Priority != DefaultPriority {
		t.Fatalf("priority = %q, want %q (default)", order.Priority, DefaultPriority)
	}
	if order.TargetType != TargetTypeDepartment || order.TargetName != "疏散组" {
		t.Fatalf("target = %q / %q, want the input echoed", order.TargetType, order.TargetName)
	}
	if order.Status != DefaultOrderStatus {
		t.Fatalf("status = %q, want %q (default)", order.Status, DefaultOrderStatus)
	}
	if order.Feedback != "" {
		t.Fatalf("feedback = %q, want an empty default", order.Feedback)
	}
	if order.Deadline != nil {
		t.Fatalf("deadline = %v, want null when omitted", order.Deadline)
	}
	if order.IssuedAt == nil || order.IssuedAt.IsZero() {
		t.Fatalf("issued_at = %v, want a non-empty server-set instant", order.IssuedAt)
	}
	if order.CompletedAt != nil {
		t.Fatalf("completed_at = %v, want null for a fresh order", order.CompletedAt)
	}
	if order.CreatedBy != "" {
		t.Fatalf("created_by = %q, want an empty default", order.CreatedBy)
	}
	if order.CreatedAt.IsZero() || !order.CreatedAt.Equal(order.UpdatedAt) {
		t.Fatalf("timestamps = %v / %v, want server time and equal", order.CreatedAt, order.UpdatedAt)
	}
}

// 显式字段：priority/status/feedback/deadline/created_by 透传（status 仅
// 接受 待接收；deadline 原样写入）。
func TestCreateOrderExplicitFields(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	deadline := fixedTime.Add(2 * time.Hour)
	order, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title:      "封控西区",
		Content:    "对西区实施临时封控",
		Priority:   PriorityCritical,
		TargetType: TargetTypeGroup,
		TargetName: "安保一组",
		Status:     OrderStatusPending,
		Feedback:   "已转达",
		Deadline:   &deadline,
		CreatedBy:  "u-commander",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	if order.Priority != PriorityCritical || order.TargetType != TargetTypeGroup ||
		order.TargetName != "安保一组" || order.Status != OrderStatusPending ||
		order.Feedback != "已转达" || order.CreatedBy != "u-commander" {
		t.Fatalf("order = %+v, want the explicit fields echoed", order)
	}
	if order.Deadline == nil || !order.Deadline.Equal(deadline) {
		t.Fatalf("deadline = %v, want %v", order.Deadline, deadline)
	}
}

// 缺必填 → ValidationError：title/content/target_type/target_name（
// 空白串与缺省等价）。
func TestCreateOrderRequiresFields(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	cases := []struct {
		name  string
		input OrderInput
	}{
		{"missing title", OrderInput{Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组"}},
		{"blank title", OrderInput{Title: "  ", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组"}},
		{"missing content", OrderInput{Title: "标题", TargetType: TargetTypeDepartment, TargetName: "疏散组"}},
		{"blank content", OrderInput{Title: "标题", Content: "\t", TargetType: TargetTypeDepartment, TargetName: "疏散组"}},
		{"missing target_type", OrderInput{Title: "标题", Content: "内容", TargetName: "疏散组"}},
		{"missing target_name", OrderInput{Title: "标题", Content: "内容", TargetType: TargetTypeDepartment}},
		{"blank target_name", OrderInput{Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: " "}},
	}
	for _, testCase := range cases {
		_, err := service.CreateOrder(context.Background(), "run-1", testCase.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}
}

// 非法枚举 → ValidationError：priority/target_type/status 非法值，以及
// POST 显式 status 非 待接收（已接收 也拒绝）。
func TestCreateOrderInvalidEnums(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	base := OrderInput{Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组"}
	cases := []struct {
		name  string
		input OrderInput
	}{
		{"invalid priority", withOrderPriority(base, "加急")},
		{"invalid target_type", withOrderTargetType(base, "班组")},
		{"invalid status", withOrderStatus(base, "草稿")},
		{"status 已接收 at creation", withOrderStatus(base, OrderStatusReceived)},
		{"status 执行中 at creation", withOrderStatus(base, OrderStatusExecuting)},
		{"status 已完成 at creation", withOrderStatus(base, OrderStatusCompleted)},
	}
	for _, testCase := range cases {
		_, err := service.CreateOrder(context.Background(), "run-1", testCase.input)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}
}

func withOrderPriority(input OrderInput, priority Priority) OrderInput {
	input.Priority = priority
	return input
}

func withOrderTargetType(input OrderInput, targetType TargetType) OrderInput {
	input.TargetType = targetType
	return input
}

func withOrderStatus(input OrderInput, status OrderStatus) OrderInput {
	input.Status = status
	return input
}

// run 不存在 → ErrRunNotFound（不写门控、不落库）。
func TestCreateOrderRunNotFound(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress))

	_, err := service.CreateOrder(context.Background(), "run-missing", OrderInput{
		Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("err = %v, want ErrRunNotFound", err)
	}
	if _, err := store.GetOrder(context.Background(), "run-missing", "any"); !errors.Is(err, ErrOrderNotFound) {
		t.Fatalf("store after failed create: err = %v, want ErrOrderNotFound", err)
	}
}

// 写门控：仅 进行中 可创建；未开始/已完成/已终止 → ValidationError。
func TestCreateOrderWriteGate(t *testing.T) {
	base := OrderInput{Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组"}
	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusCompleted, drills.RunStatusTerminated} {
		service, _ := newTestService(run("run-1", status))
		_, err := service.CreateOrder(context.Background(), "run-1", base)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", status, err)
		}
	}

	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	if _, err := service.CreateOrder(context.Background(), "run-1", base); err != nil {
		t.Fatalf("进行中: CreateOrder: %v", err)
	}
}

// ─── GetOrder ────────────────────────────────────────────────────────

// 已创建 → 返回完整对象（含 issued_at/completed_at）；run 不存在 →
// ErrRunNotFound；order 不存在 → ErrOrderNotFound；GET 不受写门控限制
// （已完成 run 的指令仍可读）。
func TestGetOrder(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}

	got, err := service.GetOrder(context.Background(), "run-1", created.ID)
	if err != nil {
		t.Fatalf("GetOrder: %v", err)
	}
	if got.ID != created.ID || got.RunID != "run-1" || got.Title != "标题" {
		t.Fatalf("order = %+v, want the created values", got)
	}
	if got.IssuedAt == nil || !got.IssuedAt.Equal(*created.IssuedAt) {
		t.Fatalf("issued_at = %v, want the creation instant", got.IssuedAt)
	}

	_, err = service.GetOrder(context.Background(), "run-missing", created.ID)
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("run missing: err = %v, want ErrRunNotFound", err)
	}
	_, err = service.GetOrder(context.Background(), "run-1", "order-missing")
	if !errors.Is(err, ErrOrderNotFound) {
		t.Fatalf("order missing: err = %v, want ErrOrderNotFound", err)
	}

	// 已完成 run 已创建的指令 → 仍可读（GET 不写门控）。
	service2, _ := newTestService(run("run-2", drills.RunStatusCompleted))
	order, err := normalizeOrder("run-2", OrderInput{
		Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	}, fixedTime, "id-2")
	if err != nil {
		t.Fatalf("normalizeOrder: %v", err)
	}
	store := service2.store.(*InMemoryStore)
	if err := store.CreateOrder(context.Background(), order); err != nil {
		t.Fatalf("store create: %v", err)
	}
	fetched, err := service2.GetOrder(context.Background(), "run-2", "id-2")
	if err != nil {
		t.Fatalf("GET on 已完成 with an order: %v", err)
	}
	if fetched.Title != "标题" {
		t.Fatalf("fetched title = %q, want 标题", fetched.Title)
	}
}

// ─── ListOrders ──────────────────────────────────────────────────────

// 空列表 → 空切片与 0；run 不存在 → ErrRunNotFound；其他 run 的指令不
// 混入；排序 created_at DESC, id DESC；status/priority/target_type 筛选
// 生效；limit/offset 分页生效（meta.total 保持筛选后总数）。
func TestListOrdersSortedFilteredAndPaginated(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))

	records, total, err := service.ListOrders(context.Background(), "run-1", OrderFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders (empty): %v", err)
	}
	if len(records) != 0 || total != 0 {
		t.Fatalf("empty list = %d records / %d total, want 0 / 0", len(records), total)
	}

	// run-2 先建 1 条，run-1 再建 3 条（不同 status/priority/target_type），
	// 确保列表按 run 隔离且排序正确。
	other, err := service.CreateOrder(context.Background(), "run-2", OrderInput{
		Title: "其他", Content: "内容", TargetType: TargetTypePerson, TargetName: "个人甲",
	})
	if err != nil {
		t.Fatalf("CreateOrder run-2: %v", err)
	}
	first, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "一号", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
		Priority: PriorityNormal,
	})
	if err != nil {
		t.Fatalf("CreateOrder first: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	second, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "二号", Content: "内容", TargetType: TargetTypeGroup, TargetName: "安保一组",
		Priority: PriorityUrgent,
	})
	if err != nil {
		t.Fatalf("CreateOrder second: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	third, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "三号", Content: "内容", TargetType: TargetTypePerson, TargetName: "讲解员乙",
		Priority: PriorityCritical,
	})
	if err != nil {
		t.Fatalf("CreateOrder third: %v", err)
	}

	records, total, err = service.ListOrders(context.Background(), "run-1", OrderFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("all: records = %d, total = %d; want 3 / 3", len(records), total)
	}
	// created_at DESC：三号、二号、一号。
	if records[0].ID != third.ID || records[1].ID != second.ID || records[2].ID != first.ID {
		t.Fatalf("records not in created_at DESC order: %+v", records)
	}

	// 筛选。
	records, total, err = service.ListOrders(context.Background(), "run-1", OrderFilter{Priority: PriorityUrgent, Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders (priority): %v", err)
	}
	if total != 1 || len(records) != 1 || records[0].ID != second.ID {
		t.Fatalf("priority filter: records = %+v, total = %d; want 二号 only", records, total)
	}
	records, total, err = service.ListOrders(context.Background(), "run-1", OrderFilter{TargetType: TargetTypeDepartment, Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders (target_type): %v", err)
	}
	if total != 1 || records[0].ID != first.ID {
		t.Fatalf("target_type filter: records = %+v, total = %d; want 一号 only", records, total)
	}
	records, total, err = service.ListOrders(context.Background(), "run-1", OrderFilter{Status: OrderStatusPending, Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders (status): %v", err)
	}
	if total != 3 {
		t.Fatalf("status filter: total = %d, want 3 (all pending)", total)
	}
	records, total, err = service.ListOrders(context.Background(), "run-1", OrderFilter{Status: OrderStatusCompleted, Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders (no match): %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("no match: records = %d, total = %d; want 0 / 0", len(records), total)
	}

	// 分页。
	records, total, err = service.ListOrders(context.Background(), "run-1", OrderFilter{Limit: 1, Offset: 1})
	if err != nil {
		t.Fatalf("ListOrders (paginated): %v", err)
	}
	if total != 3 || len(records) != 1 || records[0].ID != second.ID {
		t.Fatalf("limit=1 offset=1: records = %+v, total = %d; want 二号", records, total)
	}

	// run 隔离与缺失。
	records, total, err = service.ListOrders(context.Background(), "run-2", OrderFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders run-2: %v", err)
	}
	if total != 1 || records[0].ID != other.ID {
		t.Fatalf("run-2: records = %+v, total = %d; want the other run's order only", records, total)
	}
	_, _, err = service.ListOrders(context.Background(), "run-missing", OrderFilter{Limit: 50})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("run missing: err = %v, want ErrRunNotFound", err)
	}
}

// ─── UpdateOrder ─────────────────────────────────────────────────────

// 部分更新：省略字段保持现值；title/content/target_type/target_name 提供
// 时不得为空；priority/feedback 可改；deadline 省略保持、显式 null 清空、
// 提供值覆盖；id/issued_at/created_at 保留、updated_at 刷新。
func TestUpdateOrderPartialUpdate(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))

	deadline := fixedTime.Add(2 * time.Hour)
	created, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "一号", Content: "内容一", Priority: PriorityUrgent,
		TargetType: TargetTypeDepartment, TargetName: "疏散组",
		Feedback: "初始反馈", Deadline: &deadline, CreatedBy: "u-commander",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}
	createdAt := created.CreatedAt
	issuedAt := created.IssuedAt
	time.Sleep(5 * time.Millisecond)

	newDeadline := fixedTime.Add(24 * time.Hour)
	title := "一号（更新）"
	content := "内容一（更新）"
	priority := PriorityCritical
	targetType := TargetTypeGroup
	targetName := "安保一组"
	feedback := ""
	updated, err := service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{
		Title: &title, Content: &content, Priority: &priority,
		TargetType: &targetType, TargetName: &targetName, Feedback: &feedback,
		HasDeadline: true, Deadline: &newDeadline,
	})
	if err != nil {
		t.Fatalf("UpdateOrder: %v", err)
	}
	if updated.ID != created.ID {
		t.Fatalf("id %q changed to %q on update", created.ID, updated.ID)
	}
	if updated.Title != title || updated.Content != content || updated.Priority != priority ||
		updated.TargetType != targetType || updated.TargetName != targetName {
		t.Fatalf("updated fields not applied: %+v", updated)
	}
	if updated.Feedback != "" {
		t.Fatalf("feedback = %q, want the explicitly provided empty string", updated.Feedback)
	}
	if updated.Deadline == nil || !updated.Deadline.Equal(newDeadline) {
		t.Fatalf("deadline = %v, want %v", updated.Deadline, newDeadline)
	}
	if !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("created_at must be preserved: %+v", updated)
	}
	if issuedAt == nil || updated.IssuedAt == nil || !updated.IssuedAt.Equal(*issuedAt) {
		t.Fatalf("issued_at must be preserved: %+v", updated)
	}
	if updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at must be refreshed: %+v", updated)
	}

	// deadline 显式 null → 清空；其余省略字段保持现值。
	nullDeadline := (*time.Time)(nil)
	updated, err = service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{HasDeadline: true, Deadline: nullDeadline})
	if err != nil {
		t.Fatalf("UpdateOrder (clear deadline): %v", err)
	}
	if updated.Deadline != nil {
		t.Fatalf("deadline = %v, want null after explicit clear", updated.Deadline)
	}
	if updated.Title != title || updated.Priority != priority {
		t.Fatalf("omitted fields must keep their values: %+v", updated)
	}

	// deadline 省略 → 保持 null；再次设置后再省略 → 保持现值。
	updated, err = service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{HasDeadline: true, Deadline: &newDeadline})
	if err != nil {
		t.Fatalf("UpdateOrder (set deadline): %v", err)
	}
	updated, err = service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{})
	if err != nil {
		t.Fatalf("UpdateOrder (no-op): %v", err)
	}
	if updated.Deadline == nil || !updated.Deadline.Equal(newDeadline) {
		t.Fatalf("deadline = %v, want %v (omitted keeps current)", updated.Deadline, newDeadline)
	}
}

// 状态机：待接收→已接收→执行中→已完成 相邻迁移合法，置 已完成 设置
// completed_at；同状态 no-op 合法；跳级、回退（含 已完成 改回）→
// ValidationError；改回其他状态 completed_at 置 null 的代码路径由服务
// 保证（回退本身被状态机拒绝）。
func TestUpdateOrderStatusMachine(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "一号", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}

	// 相邻迁移链。
	status := OrderStatusReceived
	updated, err := service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{Status: &status})
	if err != nil {
		t.Fatalf("待接收→已接收: %v", err)
	}
	if updated.Status != OrderStatusReceived || updated.CompletedAt != nil {
		t.Fatalf("after 已接收: status = %q, completed_at = %v", updated.Status, updated.CompletedAt)
	}

	status = OrderStatusExecuting
	updated, err = service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{Status: &status})
	if err != nil {
		t.Fatalf("已接收→执行中: %v", err)
	}
	if updated.Status != OrderStatusExecuting || updated.CompletedAt != nil {
		t.Fatalf("after 执行中: status = %q, completed_at = %v", updated.Status, updated.CompletedAt)
	}

	status = OrderStatusCompleted
	updated, err = service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{Status: &status})
	if err != nil {
		t.Fatalf("执行中→已完成: %v", err)
	}
	if updated.Status != OrderStatusCompleted || updated.CompletedAt == nil || updated.CompletedAt.IsZero() {
		t.Fatalf("after 已完成: status = %q, completed_at = %v; want set", updated.Status, updated.CompletedAt)
	}

	// 同状态 no-op：已完成→已完成 合法且 completed_at 保留。
	completedAt := updated.CompletedAt
	status = OrderStatusCompleted
	updated, err = service.UpdateOrder(context.Background(), "run-1", created.ID, OrderUpdate{Status: &status})
	if err != nil {
		t.Fatalf("已完成→已完成 no-op: %v", err)
	}
	if updated.Status != OrderStatusCompleted || updated.CompletedAt == nil || !updated.CompletedAt.Equal(*completedAt) {
		t.Fatalf("no-op must keep status and completed_at: %+v", updated)
	}

	// 非法迁移：跳级与回退。
	service2, _ := newTestService(run("run-2", drills.RunStatusInProgress))
	fresh, err := service2.CreateOrder(context.Background(), "run-2", OrderInput{
		Title: "二号", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	})
	if err != nil {
		t.Fatalf("CreateOrder run-2: %v", err)
	}
	skip := OrderStatusExecuting
	if _, err := service2.UpdateOrder(context.Background(), "run-2", fresh.ID, OrderUpdate{Status: &skip}); !errors.As(err, &validationErrorType) {
		t.Fatalf("待接收→执行中 skip: err = %v, want ValidationError", err)
	}
	// 先合法推进到 已接收，再尝试回退到 待接收。
	received := OrderStatusReceived
	if _, err := service2.UpdateOrder(context.Background(), "run-2", fresh.ID, OrderUpdate{Status: &received}); err != nil {
		t.Fatalf("待接收→已接收: %v", err)
	}
	back := OrderStatusPending
	if _, err := service2.UpdateOrder(context.Background(), "run-2", fresh.ID, OrderUpdate{Status: &back}); !errors.As(err, &validationErrorType) {
		t.Fatalf("已接收→待接收 back: err = %v, want ValidationError", err)
	}

	// 已完成 改回 → ValidationError。
	executing := OrderStatusExecuting
	if _, err := service2.UpdateOrder(context.Background(), "run-2", fresh.ID, OrderUpdate{Status: &executing}); err != nil {
		t.Fatalf("已接收→执行中: %v", err)
	}
	completed := OrderStatusCompleted
	reverted, err := service2.UpdateOrder(context.Background(), "run-2", fresh.ID, OrderUpdate{Status: &completed})
	if err != nil {
		t.Fatalf("→已完成: %v", err)
	}
	if _, err := service2.UpdateOrder(context.Background(), "run-2", fresh.ID, OrderUpdate{Status: &executing}); !errors.As(err, &validationErrorType) {
		t.Fatalf("已完成→执行中 revert: err = %v, want ValidationError", err)
	}
	if reverted.CompletedAt == nil {
		t.Fatalf("completed_at must stay set after a rejected revert")
	}
}

var validationErrorType = &ValidationError{}

// PUT 失败路径：提供空 title/content/target_name、非法 priority/
// target_type/status → ValidationError；run 不存在 / order 不存在 →
// ErrRunNotFound / ErrOrderNotFound；写门控（非 进行中）→ ValidationError。
func TestUpdateOrderFailures(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "一号", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}

	blank := "   "
	badPriority := Priority("加急")
	badTargetType := TargetType("班组")
	badStatus := OrderStatus("草稿")
	cases := []struct {
		name   string
		update OrderUpdate
	}{
		{"blank title", OrderUpdate{Title: &blank}},
		{"blank content", OrderUpdate{Content: &blank}},
		{"blank target_name", OrderUpdate{TargetName: &blank}},
		{"invalid priority", OrderUpdate{Priority: &badPriority}},
		{"invalid target_type", OrderUpdate{TargetType: &badTargetType}},
		{"invalid status", OrderUpdate{Status: &badStatus}},
	}
	for _, testCase := range cases {
		_, err := service.UpdateOrder(context.Background(), "run-1", created.ID, testCase.update)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", testCase.name, err)
		}
	}

	// run 不存在 → ErrRunNotFound。
	_, err = service.UpdateOrder(context.Background(), "run-missing", created.ID, OrderUpdate{})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("run missing: err = %v, want ErrRunNotFound", err)
	}
	// order 不存在 → ErrOrderNotFound。
	_, err = service.UpdateOrder(context.Background(), "run-1", "order-missing", OrderUpdate{})
	if !errors.Is(err, ErrOrderNotFound) {
		t.Fatalf("order missing: err = %v, want ErrOrderNotFound", err)
	}
	// 写门控。
	service2, _ := newTestService(run("run-3", drills.RunStatusCompleted))
	store := service2.store.(*InMemoryStore)
	order, err := normalizeOrder("run-3", OrderInput{
		Title: "三号", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	}, fixedTime, "id-3")
	if err != nil {
		t.Fatalf("normalizeOrder: %v", err)
	}
	if err := store.CreateOrder(context.Background(), order); err != nil {
		t.Fatalf("store create: %v", err)
	}
	if _, err := service2.UpdateOrder(context.Background(), "run-3", "id-3", OrderUpdate{}); !errors.As(err, &validationErrorType) {
		t.Fatalf("已完成 run: err = %v, want ValidationError", err)
	}
}

// ─── DeleteOrder ─────────────────────────────────────────────────────

// 成功删除；order 不存在 / run 不存在 → 404 类错误；非 进行中 → 400。
func TestDeleteOrder(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusInProgress))
	created, err := service.CreateOrder(context.Background(), "run-1", OrderInput{
		Title: "一号", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组",
	})
	if err != nil {
		t.Fatalf("CreateOrder: %v", err)
	}

	if err := service.DeleteOrder(context.Background(), "run-1", created.ID); err != nil {
		t.Fatalf("DeleteOrder: %v", err)
	}
	if _, err := store.GetOrder(context.Background(), "run-1", created.ID); !errors.Is(err, ErrOrderNotFound) {
		t.Fatalf("store after delete: err = %v, want ErrOrderNotFound", err)
	}

	// order 不存在 → ErrOrderNotFound。
	if err := service.DeleteOrder(context.Background(), "run-1", "order-missing"); !errors.Is(err, ErrOrderNotFound) {
		t.Fatalf("order missing: err = %v, want ErrOrderNotFound", err)
	}
	// run 不存在 → ErrRunNotFound。
	if err := service.DeleteOrder(context.Background(), "run-missing", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("run missing: err = %v, want ErrRunNotFound", err)
	}
	// 非 进行中 → ValidationError（先于 order 存在性判定，与 sim_events 一致）。
	service2, _ := newTestService(run("run-2", drills.RunStatusCompleted))
	if err := service2.DeleteOrder(context.Background(), "run-2", "any"); !errors.As(err, &validationErrorType) {
		t.Fatalf("已完成 run: err = %v, want ValidationError", err)
	}
}

// ─── 级联清理入口 ────────────────────────────────────────────────────

// DeleteOrdersByRun 只删除该 run 的指令；删除不存在的 run 不是错误；
// 与会话清理互不影响。
func TestStoreDeleteOrdersByRun(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress), run("run-2", drills.RunStatusInProgress))
	base := OrderInput{Title: "标题", Content: "内容", TargetType: TargetTypeDepartment, TargetName: "疏散组"}
	if _, err := service.CreateOrder(context.Background(), "run-1", base); err != nil {
		t.Fatalf("CreateOrder run-1: %v", err)
	}
	second, err := service.CreateOrder(context.Background(), "run-2", base)
	if err != nil {
		t.Fatalf("CreateOrder run-2: %v", err)
	}
	store := service.store.(*InMemoryStore)

	if err := store.DeleteOrdersByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteOrdersByRun: %v", err)
	}
	records, total, err := service.ListOrders(context.Background(), "run-1", OrderFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListOrders run-1 after cascade: %v", err)
	}
	if total != 0 || len(records) != 0 {
		t.Fatalf("run-1 after cascade: %d records / %d total, want 0 / 0", len(records), total)
	}
	if order, err := store.GetOrder(context.Background(), "run-2", second.ID); err != nil || order.ID != second.ID {
		t.Fatalf("run-2 after cascade: order = %+v, err = %v; want untouched", order, err)
	}

	// 无指令可删不是错误。
	if err := store.DeleteOrdersByRun(context.Background(), "run-9"); err != nil {
		t.Fatalf("DeleteOrdersByRun on empty: %v", err)
	}
}
