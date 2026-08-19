package opinion

import (
	"context"
	"errors"
	"testing"
	"time"
)

// InMemoryStore 的投诉单 CRUD：创建追加、按 (run, id) 取/改/删、其他 run
// 的投诉单隔离、clone 防止外部修改泄漏。
func TestInMemoryStoreComplaintCRUD(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	complaint := Complaint{
		ID: "c-1", RunID: "run-1", Complainant: "观众甲", Channel: ComplaintChannelOnSite,
		ComplaintType: ComplaintTypeEntryBlocked, Content: "入馆排队受阻", Status: ComplaintStatusPending,
		Handling: "安抚并引导", Handler: "值班员小李", ClosedAt: nil,
		Metadata: map[string]any{"k": "v"}, CreatedBy: "u-admin", CreatedAt: base, UpdatedAt: base,
	}
	if err := store.CreateComplaint(ctx, complaint); err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	// 写入的是副本：修改外部值不泄漏进 store。
	complaint.Complainant = "被篡改"
	complaint.Metadata["k"] = "tampered"
	complaint.ClosedAt = &base

	got, err := store.GetComplaint(ctx, "run-1", "c-1")
	if err != nil {
		t.Fatalf("GetComplaint: %v", err)
	}
	if got.Complainant != "观众甲" || got.Metadata["k"] != "v" || got.ClosedAt != nil {
		t.Fatalf("store leaked caller mutation: %+v", got)
	}
	// 读取的也是副本：修改返回值不泄漏进 store。
	got.Complainant = "被篡改2"

	// 重新取一份干净副本作为更新基准。
	clean, err := store.GetComplaint(ctx, "run-1", "c-1")
	if err != nil {
		t.Fatalf("GetComplaint clean: %v", err)
	}
	// 其他 run 的 id 视为不存在。
	if _, err := store.GetComplaint(ctx, "run-2", "c-1"); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("GetComplaint other run: err = %v, want ErrComplaintNotFound", err)
	}
	if _, err := store.GetComplaint(ctx, "run-1", "missing"); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("GetComplaint missing: err = %v, want ErrComplaintNotFound", err)
	}

	// 更新：替换同 (run, id) 行。
	closedAt := base.Add(time.Hour)
	updated := clean
	updated.ClosedAt = &closedAt
	updated.Status = ComplaintStatusClosed
	if err := store.UpdateComplaint(ctx, updated); err != nil {
		t.Fatalf("UpdateComplaint: %v", err)
	}
	fetched, err := store.GetComplaint(ctx, "run-1", "c-1")
	if err != nil || fetched.Complainant != "观众甲" || fetched.Status != ComplaintStatusClosed ||
		fetched.ClosedAt == nil || !fetched.ClosedAt.Equal(closedAt) {
		t.Fatalf("after update = %+v, err = %v", fetched, err)
	}
	if err := store.UpdateComplaint(ctx, Complaint{ID: "missing", RunID: "run-1"}); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("UpdateComplaint missing: err = %v, want ErrComplaintNotFound", err)
	}

	// 删除：删后再取 404，其他行不受影响。
	if err := store.CreateComplaint(ctx, Complaint{ID: "c-2", RunID: "run-1", Complainant: "观众乙", Content: "C2"}); err != nil {
		t.Fatalf("CreateComplaint c-2: %v", err)
	}
	if err := store.DeleteComplaint(ctx, "run-1", "c-1"); err != nil {
		t.Fatalf("DeleteComplaint: %v", err)
	}
	if _, err := store.GetComplaint(ctx, "run-1", "c-1"); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("GetComplaint after delete: err = %v, want ErrComplaintNotFound", err)
	}
	if _, err := store.GetComplaint(ctx, "run-1", "c-2"); err != nil {
		t.Fatalf("GetComplaint c-2: err = %v; want the untouched row", err)
	}
	if err := store.DeleteComplaint(ctx, "run-1", "c-1"); !errors.Is(err, ErrComplaintNotFound) {
		t.Fatalf("delete again: err = %v, want ErrComplaintNotFound", err)
	}
}

// cloneComplaint 复制 ClosedAt 指针与 metadata 映射，防止共享引用。
func TestInMemoryStoreClonesClosedAt(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	closedAt := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	complaint := Complaint{
		ID: "c-1", RunID: "run-1", Complainant: "A", Content: "C",
		ClosedAt: &closedAt, Metadata: map[string]any{"k": "v"},
	}
	if err := store.CreateComplaint(ctx, complaint); err != nil {
		t.Fatalf("CreateComplaint: %v", err)
	}
	got, err := store.GetComplaint(ctx, "run-1", "c-1")
	if err != nil {
		t.Fatalf("GetComplaint: %v", err)
	}
	if got.ClosedAt == nil || !got.ClosedAt.Equal(closedAt) {
		t.Fatalf("closed_at = %v, want %v", got.ClosedAt, closedAt)
	}
	// 修改返回值的指针内容不泄漏进 store。
	*got.ClosedAt = got.ClosedAt.Add(time.Hour)
	got.Metadata["k"] = "tampered"
	fetched, err := store.GetComplaint(ctx, "run-1", "c-1")
	if err != nil || fetched.ClosedAt == nil || !fetched.ClosedAt.Equal(closedAt) || fetched.Metadata["k"] != "v" {
		t.Fatalf("store leaked clone mutation: %+v, err = %v", fetched, err)
	}
}

// 列表：created_at ASC, id ASC（受理顺序）；channel / complaint_type /
// status 筛选；分页；越界 offset 空页且 total 保持；其他 run 隔离。
func TestInMemoryStoreListComplaints(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()

	base := time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)
	complaints := []Complaint{
		{ID: "c-1", RunID: "run-1", Complainant: "观众甲", Channel: ComplaintChannelOnSite, ComplaintType: ComplaintTypeEntryBlocked, Status: ComplaintStatusPending, CreatedAt: base},
		{ID: "c-2", RunID: "run-1", Complainant: "观众乙", Channel: ComplaintChannelPhone, ComplaintType: ComplaintTypeService, Status: ComplaintStatusProcessing, CreatedAt: base.Add(time.Second)},
		{ID: "c-3", RunID: "run-1", Complainant: "观众丙", Channel: ComplaintChannelOnline, ComplaintType: ComplaintTypeVisitLimited, Status: ComplaintStatusClosed, CreatedAt: base.Add(2 * time.Second)},
		{ID: "c-4", RunID: "run-1", Complainant: "观众丁", Channel: ComplaintChannelTransfer, ComplaintType: ComplaintTypeFacility, Status: ComplaintStatusPending, CreatedAt: base.Add(2 * time.Second)},
		{ID: "c-x", RunID: "run-2", Complainant: "观众戊", Channel: ComplaintChannelOther, ComplaintType: ComplaintTypeOther, Status: ComplaintStatusPending, CreatedAt: base},
	}
	for _, complaint := range complaints {
		if err := store.CreateComplaint(ctx, complaint); err != nil {
			t.Fatalf("CreateComplaint(%s): %v", complaint.ID, err)
		}
	}

	// 全部（run-1）：created_at ASC，同刻按 id ASC。
	records, total, err := store.ListComplaints(ctx, "run-1", ComplaintFilter{Limit: 50})
	if err != nil {
		t.Fatalf("ListComplaints: %v", err)
	}
	if total != 4 || len(records) != 4 {
		t.Fatalf("total/len = %d/%d, want 4/4", total, len(records))
	}
	wantIDs := []string{"c-1", "c-2", "c-3", "c-4"} // c-3 与 c-4 同刻，id 升序（"c-3" < "c-4"）
	for i, want := range wantIDs {
		if records[i].ID != want {
			t.Fatalf("records[%d] = %q, want %q (created_at ASC, id ASC)", i, records[i].ID, want)
		}
	}

	// channel 筛选。
	records, total, err = store.ListComplaints(ctx, "run-1", ComplaintFilter{Channel: ComplaintChannelPhone, Limit: 50})
	if err != nil {
		t.Fatalf("ListComplaints channel filter: %v", err)
	}
	if total != 1 || records[0].ID != "c-2" {
		t.Fatalf("phone filter = %+v (total %d), want c-2", records, total)
	}

	// complaint_type 筛选。
	records, total, err = store.ListComplaints(ctx, "run-1", ComplaintFilter{ComplaintType: ComplaintTypeEntryBlocked, Limit: 50})
	if err != nil {
		t.Fatalf("ListComplaints type filter: %v", err)
	}
	if total != 1 || records[0].ID != "c-1" {
		t.Fatalf("entry-blocked filter = %+v (total %d), want c-1", records, total)
	}

	// status 筛选。
	records, total, err = store.ListComplaints(ctx, "run-1", ComplaintFilter{Status: ComplaintStatusPending, Limit: 50})
	if err != nil {
		t.Fatalf("ListComplaints status filter: %v", err)
	}
	if total != 2 || records[0].ID != "c-1" || records[1].ID != "c-4" {
		t.Fatalf("pending filter = %+v (total %d), want c-1, c-4", records, total)
	}

	// 筛选与分页组合：limit=1 offset=1 取第二条（c-4），total 保持 2。
	records, total, err = store.ListComplaints(ctx, "run-1", ComplaintFilter{
		Status: ComplaintStatusPending, Limit: 1, Offset: 1,
	})
	if err != nil {
		t.Fatalf("ListComplaints combined: %v", err)
	}
	if total != 2 || len(records) != 1 || records[0].ID != "c-4" {
		t.Fatalf("combined page = %+v (total %d), want c-4", records, total)
	}

	// 越界 offset：空页、total 保持。
	records, total, err = store.ListComplaints(ctx, "run-1", ComplaintFilter{Offset: 100, Limit: 50})
	if err != nil {
		t.Fatalf("ListComplaints offset: %v", err)
	}
	if total != 4 || len(records) != 0 {
		t.Fatalf("offset page = %+v (total %d), want empty page with total 4", records, total)
	}
}

// DeleteByRun 清空该 run 的全部投诉单（与其他 opinion 对象一并清理），
// 其他 run 的投诉单保留；无投诉单可清时不是错误。
func TestInMemoryStoreDeleteByRunClearsComplaints(t *testing.T) {
	store := NewInMemoryStore()
	ctx := context.Background()
	for _, runID := range []string{"run-1", "run-1", "run-2"} {
		if err := store.CreateComplaint(ctx, Complaint{ID: "c-" + runID, RunID: runID, Complainant: "观众", Content: "C"}); err != nil {
			t.Fatalf("CreateComplaint(%s): %v", runID, err)
		}
	}
	if err := store.DeleteByRun(ctx, "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	records, total, err := store.ListComplaints(ctx, "run-1", ComplaintFilter{})
	if err != nil || total != 0 || len(records) != 0 {
		t.Fatalf("list run-1 after DeleteByRun = %+v (total %d), err = %v; want empty", records, total, err)
	}
	if _, err := store.GetComplaint(ctx, "run-2", "c-run-2"); err != nil {
		t.Fatalf("GET run-2: err = %v; want the untouched complaint", err)
	}
	if err := store.DeleteByRun(ctx, "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
