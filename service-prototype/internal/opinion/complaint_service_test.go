package opinion

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// inProgressComplaintService returns a service over a store and a
// single 进行中 run named run-1, plus the store for direct assertions.
func inProgressComplaintService() (*Service, *InMemoryStore) {
	return newTestService(run("run-1", drills.RunStatusInProgress))
}

// ─── CreateComplaint ─────────────────────────────────────────────────

// 合法创建：id 为 26 位 Crockford Base32 ULID、run_id 注入、缺省 channel
// 现场 / complaint_type 入馆受阻 / status 待受理 / handling、handler ”、
// closed_at nil、metadata {}、created_by ”、created_at/updated_at 服务端
// 时间且相等；显式字段原样保留。
func TestCreateComplaintDefaults(t *testing.T) {
	service, store := inProgressComplaintService()

	complaint, err := service.CreateComplaint(context.Background(), "run-1", ComplaintInput{Complainant: "观众甲", Content: "入馆排队受阻"})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	if !crockford26.MatchString(complaint.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", complaint.ID)
	}
	if complaint.RunID != "run-1" {
		t.Fatalf("run_id = %q, want run-1", complaint.RunID)
	}
	if complaint.Complainant != "观众甲" || complaint.Content != "入馆排队受阻" {
		t.Fatalf("complainant/content = %q/%q, want the provided values", complaint.Complainant, complaint.Content)
	}
	if complaint.Channel != ComplaintChannelOnSite || complaint.ComplaintType != ComplaintTypeEntryBlocked ||
		complaint.Status != ComplaintStatusPending || complaint.Handling != "" || complaint.Handler != "" ||
		complaint.ClosedAt != nil || complaint.Metadata == nil || len(complaint.Metadata) != 0 ||
		complaint.CreatedBy != "" {
		t.Fatalf("complaint = %+v, want the defaults", complaint)
	}
	if complaint.CreatedAt.IsZero() || !complaint.CreatedAt.Equal(complaint.UpdatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want server time and equal", complaint.CreatedAt, complaint.UpdatedAt)
	}
	// 已写入 store。
	stored, err := store.GetComplaint(context.Background(), "run-1", complaint.ID)
	if err != nil || stored.Complainant != complaint.Complainant {
		t.Fatalf("stored = %+v, err = %v; want the created complaint", stored, err)
	}
}

// 显式字段原样保留（channel 五种 / complaint_type 五种 / handling /
// handler / metadata / created_by 透传）；创建显式非 待受理 status →
// ValidationError。
func TestCreateComplaintExplicitFieldsAndRejectsNonPending(t *testing.T) {
	service, _ := inProgressComplaintService()
	ctx := context.Background()

	for _, channel := range validComplaintChannels {
		complaint, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "A", Content: "C", Channel: channel})
		if err != nil {
			t.Fatalf("CreateComplaint(%s): %v", channel, err)
		}
		if complaint.Channel != channel {
			t.Fatalf("channel = %q, want %q", complaint.Channel, channel)
		}
	}
	for _, complaintType := range validComplaintTypes {
		complaint, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "A", Content: "C", ComplaintType: complaintType})
		if err != nil {
			t.Fatalf("CreateComplaint(%s): %v", complaintType, err)
		}
		if complaint.ComplaintType != complaintType {
			t.Fatalf("complaint_type = %q, want %q", complaint.ComplaintType, complaintType)
		}
	}
	complaint, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{
		Complainant:   "观众乙",
		Content:       "展品区域拥挤",
		Channel:       ComplaintChannelPhone,
		ComplaintType: ComplaintTypeVisitLimited,
		Handling:      "安排专人疏导",
		Handler:       "值班员小李",
		Metadata:      map[string]any{"platform": "call"},
		CreatedBy:     "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	if complaint.Channel != ComplaintChannelPhone || complaint.ComplaintType != ComplaintTypeVisitLimited ||
		complaint.Handling != "安排专人疏导" || complaint.Handler != "值班员小李" ||
		complaint.Metadata["platform"] != "call" || complaint.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", complaint)
	}

	for _, status := range []ComplaintStatus{ComplaintStatusProcessing, ComplaintStatusClosed} {
		_, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "A", Content: "C", Status: status})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("explicit %s on create: err = %v, want a ValidationError", status, err)
		}
	}
}

// 写门控与 run 存在性：run 不存在 → ErrRunNotFound；未开始/已完成/已终止
// → ValidationError（400 语义，仅 进行中 可写）。
func TestCreateComplaintRunChecks(t *testing.T) {
	service, _ := newTestService(
		run("not-started", drills.RunStatusNotStarted),
		run("completed", drills.RunStatusCompleted),
		run("terminated", drills.RunStatusTerminated),
	)
	ctx := context.Background()

	_, err := service.CreateComplaint(ctx, "missing", ComplaintInput{Complainant: "A", Content: "C"})
	if !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for _, runID := range []string{"not-started", "completed", "terminated"} {
		_, err := service.CreateComplaint(ctx, runID, ComplaintInput{Complainant: "A", Content: "C"})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("run %s: err = %v, want a ValidationError", runID, err)
		}
	}
}

// ─── GetComplaint / ListComplaints ───────────────────────────────────

// GetComplaint：存在 → 完整对象；不存在 → ErrComplaintNotFound；run 不存
// 在 → ErrRunNotFound；GET 不受写门控（已完成 run 仍可读）。
func TestGetComplaint(t *testing.T) {
	service, store := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("done", drills.RunStatusCompleted),
	)
	ctx := context.Background()

	created, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众甲", Content: "入馆排队受阻"})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	got, err := service.GetComplaint(ctx, "run-1", created.ID)
	if err != nil || got.ID != created.ID || got.Complainant != "观众甲" {
		t.Fatalf("GetComplaint = %+v, err = %v; want the created complaint", got, err)
	}

	if _, err := service.GetComplaint(ctx, "run-1", "missing"); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("missing complaint: err = %v, want ErrComplaintNotFound", err)
	}
	if _, err := service.GetComplaint(ctx, "missing", created.ID); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}

	// GET 不受写门控：已完成 run 直接经 store 注入投诉记录后仍可读。
	if err := store.CreateComplaint(ctx, Complaint{ID: "c-done", RunID: "done", Complainant: "A", Content: "C"}); err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	got, err = service.GetComplaint(ctx, "done", "c-done")
	if err != nil || got.Complainant != "A" {
		t.Fatalf("GetComplaint on completed run = %+v, err = %v; want 200 semantics", got, err)
	}
}

// ListComplaints：run 不存在 → ErrRunNotFound；已完成 run 仍可列表（GET
// 不受门控）；筛选/分页透传到 store。
func TestListComplaints(t *testing.T) {
	service, store := newTestService(run("done", drills.RunStatusCompleted))
	ctx := context.Background()

	if _, _, err := service.ListComplaints(ctx, "missing", ComplaintFilter{}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	for i := 0; i < 3; i++ {
		if err := store.CreateComplaint(ctx, Complaint{ID: "c" + string(rune('a'+i)), RunID: "done", Complainant: "A", Content: "C"}); err != nil {
			t.Fatalf("CreateComplaint: %v", err)
		}
	}
	records, total, err := service.ListComplaints(ctx, "done", ComplaintFilter{Limit: 2, Offset: 1})
	if err != nil {
		t.Fatalf("ListComplaints: %v", err)
	}
	if total != 3 || len(records) != 2 {
		t.Fatalf("total/len = %d/%d, want 3/2", total, len(records))
	}
}

// 列表排序 created_at ASC, id ASC（受理顺序）可断言：依次创建三条（间隔
// sleep 保证毫秒级时间可区分），列表按创建正序返回。
func TestListComplaintsSortedIntakeOrder(t *testing.T) {
	service, _ := inProgressComplaintService()
	ctx := context.Background()

	first, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众甲", Content: "第一条"})
	if err != nil {
		t.Fatalf("create first: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	second, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众乙", Content: "第二条"})
	if err != nil {
		t.Fatalf("create second: %v", err)
	}
	time.Sleep(5 * time.Millisecond)
	third, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众丙", Content: "第三条"})
	if err != nil {
		t.Fatalf("create third: %v", err)
	}

	records, total, err := service.ListComplaints(ctx, "run-1", ComplaintFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListComplaints: %v", err)
	}
	if total != 3 || len(records) != 3 {
		t.Fatalf("total/len = %d/%d, want 3/3", total, len(records))
	}
	wantOrder := []Complaint{first, second, third}
	for i, want := range wantOrder {
		if records[i].ID != want.ID {
			t.Fatalf("records[%d] = %q, want %q (created_at ASC)", i, records[i].ID, want.ID)
		}
		if i > 0 && records[i].CreatedAt.Before(records[i-1].CreatedAt) {
			t.Fatalf("created_at not ascending: %s then %s", records[i-1].CreatedAt, records[i].CreatedAt)
		}
	}
}

// ─── UpdateComplaint：部分更新 ───────────────────────────────────────

// 部分更新语义：complainant/content 必填（双入口），缺省 channel /
// complaint_type / status / handling / handler / metadata / created_by
// 保持原值；显式字段生效；updated_at 刷新、id/run_id/created_at 不变；
// PUT 后 GET 反映更新。
func TestUpdateComplaintPartialUpdate(t *testing.T) {
	service, _ := inProgressComplaintService()
	ctx := context.Background()

	created, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{
		Complainant:   "观众甲",
		Content:       "入馆排队受阻",
		Channel:       ComplaintChannelTransfer,
		ComplaintType: ComplaintTypeEntryBlocked,
		Handling:      "安抚并引导",
		Handler:       "值班员小李",
		Metadata:      map[string]any{"k": "v"},
		CreatedBy:     "u-admin",
	})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	createdAt := created.CreatedAt
	time.Sleep(5 * time.Millisecond)

	// 只改 complainant/content：其余字段保持。
	updated, err := service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{Complainant: "观众甲（更正）", Content: "更新后的投诉内容"})
	if err != nil {
		t.Fatalf("UpdateComplaint: %v", err)
	}
	if updated.ID != created.ID || updated.RunID != "run-1" || !updated.CreatedAt.Equal(createdAt) {
		t.Fatalf("id/run_id/created_at must be preserved: %+v", updated)
	}
	if updated.Complainant != "观众甲（更正）" || updated.Content != "更新后的投诉内容" ||
		updated.Channel != ComplaintChannelTransfer || updated.ComplaintType != ComplaintTypeEntryBlocked ||
		updated.Status != ComplaintStatusPending || updated.Handling != "安抚并引导" ||
		updated.Handler != "值班员小李" || updated.Metadata["k"] != "v" || updated.CreatedBy != "u-admin" {
		t.Fatalf("partial update did not keep the untouched fields: %+v", updated)
	}
	if updated.UpdatedAt.Before(createdAt) || updated.UpdatedAt.Equal(createdAt) {
		t.Fatalf("updated_at = %v, want a refreshed value", updated.UpdatedAt)
	}

	// 显式 channel/complaint_type/handling/handler/metadata（含 {} 边界
	// 值）/created_by 生效。
	updated, err = service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{
		Complainant:   "观众甲（更正）",
		Content:       "更新后的投诉内容",
		Channel:       ComplaintChannelOnline,
		ComplaintType: ComplaintTypeService,
		Handling:      "致歉并解释",
		Handler:       "值班员小王",
		Metadata:      map[string]any{},
		HasMetadata:   true,
		CreatedBy:     "u-2",
	})
	if err != nil {
		t.Fatalf("UpdateComplaint channel/complaint_type/handling/handler/metadata/created_by: %v", err)
	}
	if updated.Channel != ComplaintChannelOnline || updated.ComplaintType != ComplaintTypeService ||
		updated.Handling != "致歉并解释" || updated.Handler != "值班员小王" ||
		len(updated.Metadata) != 0 || updated.CreatedBy != "u-2" {
		t.Fatalf("explicit fields not applied: %+v", updated)
	}

	// 更新已持久化：再 GET 反映更新。
	fetched, err := service.GetComplaint(ctx, "run-1", created.ID)
	if err != nil || fetched.Complainant != "观众甲（更正）" || fetched.Channel != ComplaintChannelOnline ||
		fetched.ComplaintType != ComplaintTypeService || fetched.Handling != "致歉并解释" || fetched.Handler != "值班员小王" {
		t.Fatalf("GET after PUT = %+v, err = %v; want the updated values", fetched, err)
	}
}

// 处理状态机（PUT 入口）：待受理→处理中→已办结 相邻迁移合法；置 已办结
// 时服务端设 closed_at；同值 no-op 合法且 closed_at 保持原值（不重置）；
// 其余状态 closed_at 为 null；跳级/回退（含已办结→处理中）400；PUT 未涉
// 及 status（仅改 handling/handler/content 等业务字段）时 closed_at 保
// 持原值。
func TestUpdateComplaintStatusStateMachine(t *testing.T) {
	service, _ := inProgressComplaintService()
	ctx := context.Background()

	created, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众甲", Content: "入馆排队受阻"})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	if created.ClosedAt != nil {
		t.Fatalf("closed_at = %v at creation, want nil", created.ClosedAt)
	}

	// 跳级 待受理 → 已办结：400。
	_, err = service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{Complainant: "观众甲", Content: "C", Status: ComplaintStatusClosed})
	var validationError *ValidationError
	if !errors.As(err, &validationError) {
		t.Fatalf("skip transition: err = %v, want a ValidationError", err)
	}

	// 待受理 → 处理中：closed_at 保持 nil。
	time.Sleep(5 * time.Millisecond)
	processing, err := service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{
		Complainant: "观众甲", Content: "C", Status: ComplaintStatusProcessing, Handling: "安抚并引导至人工通道", Handler: "值班员小李",
	})
	if err != nil {
		t.Fatalf("pending -> processing: %v", err)
	}
	if processing.Status != ComplaintStatusProcessing || processing.ClosedAt != nil {
		t.Fatalf("processing = %+v, want 处理中 with nil closed_at", processing)
	}
	if processing.Handling != "安抚并引导至人工通道" || processing.Handler != "值班员小李" {
		t.Fatalf("handling/handler not applied during processing: %+v", processing)
	}

	// 处理中 → 已办结：closed_at 被服务端设置。
	time.Sleep(5 * time.Millisecond)
	closed, err := service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{Complainant: "观众甲", Content: "C", Status: ComplaintStatusClosed})
	if err != nil {
		t.Fatalf("processing -> closed: %v", err)
	}
	if closed.Status != ComplaintStatusClosed || closed.ClosedAt == nil {
		t.Fatalf("closed = %+v, want 已办结 with a server-set closed_at", closed)
	}
	closedAt := *closed.ClosedAt

	// 同值 no-op：已办结 → 已办结 200，closed_at 保持原值（不重置）。
	time.Sleep(5 * time.Millisecond)
	again, err := service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{Complainant: "观众甲", Content: "C", Status: ComplaintStatusClosed})
	if err != nil {
		t.Fatalf("same-value no-op: %v", err)
	}
	if again.Status != ComplaintStatusClosed || again.ClosedAt == nil || !again.ClosedAt.Equal(closedAt) {
		t.Fatalf("no-op closed_at = %v, want the unchanged %v", again.ClosedAt, closedAt)
	}

	// PUT 未涉及 status（仅改 handling/handler/content 等业务字段）：
	// closed_at 保持原值。
	untouched, err := service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{
		Complainant: "观众甲", Content: "已办结后的补充说明", Handling: "回访确认满意", Handler: "值班员小王",
	})
	if err != nil {
		t.Fatalf("update without status: %v", err)
	}
	if untouched.ClosedAt == nil || !untouched.ClosedAt.Equal(closedAt) {
		t.Fatalf("closed_at after unrelated update = %v, want %v", untouched.ClosedAt, closedAt)
	}
	if untouched.Content != "已办结后的补充说明" || untouched.Handling != "回访确认满意" || untouched.Handler != "值班员小王" {
		t.Fatalf("business fields not applied: %+v", untouched)
	}

	// 回退 已办结 → 处理中 / 已办结 → 待受理：400。
	for _, status := range []ComplaintStatus{ComplaintStatusProcessing, ComplaintStatusPending} {
		_, err := service.UpdateComplaint(ctx, "run-1", created.ID, ComplaintUpdate{Complainant: "观众甲", Content: "C", Status: status})
		if !errors.As(err, &validationError) {
			t.Fatalf("backward transition %s: err = %v, want a ValidationError", status, err)
		}
	}

	// 同值 待受理 no-op（待受理 投诉单）：200 且 closed_at 保持 nil。
	pending, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众乙", Content: "C2"})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	pendingAgain, err := service.UpdateComplaint(ctx, "run-1", pending.ID, ComplaintUpdate{Complainant: "观众乙", Content: "C2", Status: ComplaintStatusPending})
	if err != nil {
		t.Fatalf("待受理 no-op: %v", err)
	}
	if pendingAgain.Status != ComplaintStatusPending || pendingAgain.ClosedAt != nil {
		t.Fatalf("pending no-op = %+v, want 待受理 with nil closed_at", pendingAgain)
	}
}

// 更新失败路径：run 不存在 404 语义、非进行中 400 语义（门控先于存在性判
// 定）、投诉单不存在 404 语义、缺 complainant/content 400、非法枚举 400。
func TestUpdateComplaintFailures(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusInProgress))
	ctx := context.Background()

	if _, err := service.UpdateComplaint(ctx, "missing", "c", ComplaintUpdate{Complainant: "A", Content: "C"}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpdateComplaint(ctx, "run-1", "missing", ComplaintUpdate{Complainant: "A", Content: "C"}); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("missing complaint: err = %v, want ErrComplaintNotFound", err)
	}

	// 门控先于投诉单存在性判定：非进行中 run 更新缺失投诉单 → 400。
	locked, _ := newTestService(run("done", drills.RunStatusCompleted))
	if _, err := locked.UpdateComplaint(ctx, "done", "c", ComplaintUpdate{Complainant: "A", Content: "C"}); err == nil {
		t.Fatal("missing complaint on locked run: err = nil, want a ValidationError (gate first)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("missing complaint on locked run: err = %v, want a ValidationError (gate first)", err)
		}
	}

	created, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众甲", Content: "C"})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}

	for name, update := range map[string]ComplaintUpdate{
		"missing complainant":    {Content: "C"},
		"empty complainant":      {Complainant: "", Content: "C"},
		"missing content":        {Complainant: "A"},
		"empty content":          {Complainant: "A", Content: ""},
		"invalid channel":        {Complainant: "A", Content: "C", Channel: "邮件"},
		"invalid complaint_type": {Complainant: "A", Content: "C", ComplaintType: "门票"},
		"invalid status":         {Complainant: "A", Content: "C", Status: "已受理"},
	} {
		_, err := service.UpdateComplaint(ctx, "run-1", created.ID, update)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", name, err)
		}
	}
}

// ─── DeleteComplaint ─────────────────────────────────────────────────

// 成功删除（再次删除 404）；run 不存在 404；非进行中 400（门控先于投诉单
// 存在性判定）。
func TestDeleteComplaint(t *testing.T) {
	service, _ := inProgressComplaintService()
	ctx := context.Background()

	created, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众甲", Content: "C"})
	if err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	if err := service.DeleteComplaint(ctx, "run-1", created.ID); err != nil {
		t.Fatalf("DeleteComplaint: %v", err)
	}
	if err := service.DeleteComplaint(ctx, "run-1", created.ID); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("delete again: err = %v, want ErrComplaintNotFound", err)
	}
	if err := service.DeleteComplaint(ctx, "missing", "c"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("missing run: err = %v, want ErrRunNotFound", err)
	}
	locked, _ := newTestService(run("done", drills.RunStatusCompleted))
	if err := locked.DeleteComplaint(ctx, "done", "c"); err == nil {
		t.Fatal("delete missing complaint on locked run: err = nil, want a ValidationError (gate first)")
	} else {
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("delete missing complaint on locked run: err = %v, want a ValidationError (gate first)", err)
		}
	}
}

// ─── 级联：DeleteByRun 清空投诉记录 ──────────────────────────────────

// DeleteByRun 清空该 run 的全部投诉单（与其他 opinion 对象一并清理），
// 其他 run 的投诉单保留；无投诉单可清时不是错误。
func TestDeleteByRunCascadesComplaints(t *testing.T) {
	service, store := newTestService(
		run("run-1", drills.RunStatusInProgress),
		run("run-2", drills.RunStatusInProgress),
	)
	ctx := context.Background()

	if _, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众甲", Content: "A"}); err != nil {
		t.Fatalf("create run-1: %v", err)
	}
	if _, err := service.CreateComplaint(ctx, "run-1", ComplaintInput{Complainant: "观众乙", Content: "B"}); err != nil {
		t.Fatalf("create run-1 again: %v", err)
	}
	run2Complaint, err := service.CreateComplaint(ctx, "run-2", ComplaintInput{Complainant: "观众丙", Content: "C"})
	if err != nil {
		t.Fatalf("create run-2: %v", err)
	}

	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if records, total, err := service.ListComplaints(ctx, "run-1", ComplaintFilter{}); err != nil || total != 0 || len(records) != 0 {
		t.Fatalf("list run-1 after DeleteByRun = %+v (total %d), err = %v; want empty", records, total, err)
	}
	// 其他 run 的投诉单不受影响。
	got, err := service.GetComplaint(ctx, "run-2", run2Complaint.ID)
	if err != nil || got.Complainant != "观众丙" {
		t.Fatalf("GET run-2: complaint = %+v, err = %v; want the untouched complaint", got, err)
	}
	// 无投诉单可清时不是错误。
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
