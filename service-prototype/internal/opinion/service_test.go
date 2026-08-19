package opinion

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// validationError is the shared target of errors.As assertions across
// the service tests.
var validationError *ValidationError

// ─── UpsertEvent：首次创建 ───────────────────────────────────────────

// 首次 PUT：200 语义的完整对象——服务端生成 26 位 Crockford Base32 ULID、
// level 缺省 中热、status 缺省 监测中、occurred_at 缺省 nil、metadata 缺省
// {}、created_by 缺省 ”、created_at/updated_at 服务端时间且相等；显式
// 字段（event_name/subject/summary/occurred_at/level/metadata/created_by）
// 原样保留。
func TestUpsertEventCreatesWithDefaults(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))

	occurredAt := time.Date(2026, 8, 1, 8, 30, 0, 0, time.UTC)
	event, err := service.UpsertEvent(context.Background(), "run-1", EventInput{
		EventName:  "展厅舆情事件",
		Subject:    "涉事主体",
		Summary:    "事件概述",
		OccurredAt: &occurredAt,
		Metadata:   map[string]any{"source": "merit"},
		CreatedBy:  "u-admin",
	})
	if err != nil {
		t.Fatalf("UpsertEvent: %v", err)
	}
	if !crockford26.MatchString(event.ID) {
		t.Fatalf("id = %q, want a 26-character Crockford Base32 ULID", event.ID)
	}
	if event.RunID != "run-1" {
		t.Fatalf("run_id = %q, want run-1", event.RunID)
	}
	if event.EventName != "展厅舆情事件" || event.Subject != "涉事主体" || event.Summary != "事件概述" ||
		event.Level != LevelMedium || event.Status != StatusMonitoring ||
		event.Metadata["source"] != "merit" || event.CreatedBy != "u-admin" {
		t.Fatalf("event = %+v, want the provided values with level 中热 and status 监测中 defaults", event)
	}
	if event.OccurredAt == nil || !event.OccurredAt.Equal(occurredAt) {
		t.Fatalf("occurred_at = %v, want %v", event.OccurredAt, occurredAt)
	}
	if event.CreatedAt.IsZero() || !event.CreatedAt.Equal(event.UpdatedAt) {
		t.Fatalf("created_at/updated_at = %v / %v, want server time and equal", event.CreatedAt, event.UpdatedAt)
	}
}

// 首次创建仅接受 监测中：显式 已预警/已处置 → ValidationError。
func TestUpsertEventCreateRejectsAdvancedStatus(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	for _, status := range []Status{StatusWarning, StatusHandled} {
		_, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: status})
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("status %s: err = %v, want a ValidationError", status, err)
		}
	}
}

// ─── UpsertEvent：原地更新与状态机 ───────────────────────────────────

// 再次 PUT：id 与 created_at 保留不变、updated_at 刷新；全量覆盖语义
// （body 缺省字段重置为默认值——subject/summary/occurred_at/metadata/
// created_by 重置，level 重置 中热）；status 同值 no-op 合法。
func TestUpsertEventUpdatesInPlace(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))

	created, err := service.UpsertEvent(context.Background(), "run-1", EventInput{
		EventName: "A", Subject: "主体", Summary: "概述", Level: LevelHigh,
		Metadata: map[string]any{"source": "merit"}, CreatedBy: "u-admin",
	})
	if err != nil {
		t.Fatalf("create: %v", err)
	}
	createdAt := created.CreatedAt

	updated, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "B"})
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
	if updated.EventName != "B" {
		t.Fatalf("event_name = %q, want the replacement value", updated.EventName)
	}
	if updated.Subject != "" || updated.Summary != "" || updated.OccurredAt != nil ||
		updated.Level != LevelMedium || updated.CreatedBy != "" || len(updated.Metadata) != 0 {
		t.Fatalf("replacement semantics = %+v", updated)
	}
	// status 同值 no-op（监测中 → 监测中）合法。
	if updated.Status != StatusMonitoring {
		t.Fatalf("status = %q, want 监测中", updated.Status)
	}
}

// 状态机：相邻前进 监测中→已预警→已处置 合法；跳级（监测中→已处置）与
// 回退（已预警→监测中、已处置→已预警）→ ValidationError。
func TestUpsertEventStatusMachine(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); err != nil {
		t.Fatalf("create: %v", err)
	}

	// 监测中 → 已预警 合法。
	warning, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: StatusWarning})
	if err != nil {
		t.Fatalf("监测中 -> 已预警: %v", err)
	}
	if warning.Status != StatusWarning {
		t.Fatalf("status = %q, want 已预警", warning.Status)
	}
	// 已预警 → 已处置 合法。
	handled, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: StatusHandled})
	if err != nil {
		t.Fatalf("已预警 -> 已处置: %v", err)
	}
	if handled.Status != StatusHandled {
		t.Fatalf("status = %q, want 已处置", handled.Status)
	}
	// 已处置 → 已处置 同值 no-op 合法。
	noop, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: StatusHandled})
	if err != nil {
		t.Fatalf("已处置 -> 已处置 no-op: %v", err)
	}
	if noop.Status != StatusHandled {
		t.Fatalf("status = %q, want 已处置", noop.Status)
	}

	// 回退：已处置 → 已预警 → ValidationError。
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: StatusWarning}); !errors.As(err, &validationError) {
		t.Fatalf("已处置 -> 已预警: err = %v, want a ValidationError", err)
	}
}

// 跳级：监测中 → 已处置 → ValidationError（对既有事件直接 PUT 已处置）。
func TestUpsertEventSkipsStatus(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: StatusHandled}); !errors.As(err, &validationError) {
		t.Fatalf("监测中 -> 已处置: err = %v, want a ValidationError", err)
	}
}

// 缺 event_name、非法 level/status → ValidationError（create 与 update
// 一致覆盖）。
func TestUpsertEventValidation(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{}); !errors.As(err, &validationError) {
		t.Fatalf("missing event_name: err = %v, want a ValidationError", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Level: "爆热"}); !errors.As(err, &validationError) {
		t.Fatalf("invalid level: err = %v, want a ValidationError", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Status: "已结束"}); !errors.As(err, &validationError) {
		t.Fatalf("invalid status: err = %v, want a ValidationError", err)
	}
	// 既有事件更新路径同样校验必填与枚举。
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); err != nil {
		t.Fatalf("create: %v", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{}); !errors.As(err, &validationError) {
		t.Fatalf("update missing event_name: err = %v, want a ValidationError", err)
	}
}

// ─── run 不存在 / 写门控 ─────────────────────────────────────────────

// run 不存在：GET/PUT/DELETE 均 ErrRunNotFound。
func TestEventRunNotFound(t *testing.T) {
	service, _ := newTestService()
	if _, err := service.GetEvent(context.Background(), "missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("GET: err = %v, want ErrRunNotFound", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "missing", EventInput{EventName: "A"}); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("PUT: err = %v, want ErrRunNotFound", err)
	}
	if err := service.DeleteEvent(context.Background(), "missing"); !errors.Is(err, ErrRunNotFound) {
		t.Fatalf("DELETE: err = %v, want ErrRunNotFound", err)
	}
}

// run 存在但事件未配置：GET/DELETE 均 ErrEventNotFound；PUT 创建。
func TestEventNotConfigured(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	if _, err := service.GetEvent(context.Background(), "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GET: err = %v, want ErrEventNotFound", err)
	}
	if err := service.DeleteEvent(context.Background(), "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("DELETE: err = %v, want ErrEventNotFound", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); err != nil {
		t.Fatalf("PUT: %v", err)
	}
}

// 写门控：run 已完成/已终止 时 PUT/DELETE → ValidationError（事件已配置）；
// 未开始/进行中 可写；GET 不受门控（已配置即返回）。
func TestEventWriteGate(t *testing.T) {
	for _, status := range []drills.RunStatus{drills.RunStatusCompleted, drills.RunStatusTerminated} {
		service, _ := newTestService(run("run-1", status))
		if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); !errors.As(err, &validationError) {
			t.Fatalf("%s PUT: err = %v, want a ValidationError", status, err)
		}
		if err := service.DeleteEvent(context.Background(), "run-1"); !errors.Is(err, ErrEventNotFound) {
			t.Fatalf("%s DELETE without event: err = %v, want ErrEventNotFound (existence checked first)", status, err)
		}
	}

	for _, status := range []drills.RunStatus{drills.RunStatusCompleted, drills.RunStatusTerminated} {
		service, store := newTestService(run("run-1", status))
		store.events = append(store.events, Event{RunID: "run-1", EventName: "A"})
		if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); !errors.As(err, &validationError) {
			t.Fatalf("%s PUT with event: err = %v, want a ValidationError", status, err)
		}
		if err := service.DeleteEvent(context.Background(), "run-1"); !errors.As(err, &validationError) {
			t.Fatalf("%s DELETE with event: err = %v, want a ValidationError", status, err)
		}
		// GET 不受写门控。
		event, err := service.GetEvent(context.Background(), "run-1")
		if err != nil || event.RunID != "run-1" {
			t.Fatalf("%s GET: event = %+v, err = %v; want the configured event", status, event, err)
		}
	}

	for _, status := range []drills.RunStatus{drills.RunStatusNotStarted, drills.RunStatusInProgress} {
		service, _ := newTestService(run("run-1", status))
		event, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"})
		if err != nil || event.EventName != "A" {
			t.Fatalf("%s PUT: event = %+v, err = %v; want a created event", status, event, err)
		}
		if err := service.DeleteEvent(context.Background(), "run-1"); err != nil {
			t.Fatalf("%s DELETE: %v", status, err)
		}
	}
}

// ─── GetEvent / DeleteEvent ──────────────────────────────────────────

// GET 返回已配置的完整对象；DELETE 后 GET → ErrEventNotFound（删除生效）。
func TestGetAndDeleteEvent(t *testing.T) {
	service, _ := newTestService(run("run-1", drills.RunStatusNotStarted))
	created, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A", Subject: "主体"})
	if err != nil {
		t.Fatalf("create: %v", err)
	}

	fetched, err := service.GetEvent(context.Background(), "run-1")
	if err != nil {
		t.Fatalf("GET: %v", err)
	}
	if fetched.ID != created.ID || fetched.EventName != "A" || fetched.Subject != "主体" {
		t.Fatalf("GET = %+v, want the created event", fetched)
	}

	if err := service.DeleteEvent(context.Background(), "run-1"); err != nil {
		t.Fatalf("DELETE: %v", err)
	}
	if _, err := service.GetEvent(context.Background(), "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GET after DELETE: err = %v, want ErrEventNotFound", err)
	}
	// 再次 DELETE → ErrEventNotFound。
	if err := service.DeleteEvent(context.Background(), "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("DELETE again: err = %v, want ErrEventNotFound", err)
	}
}

// ─── 级联：删除 run 后事件随之清空 ───────────────────────────────────

// 接线 DeleteByRun 清理入口：创建事件后删除 run，事件消失（内存行为与
// 迁移 ON DELETE CASCADE 一致）；未配置事件的 run 删除不报错。
func TestDeleteByRunCascadesEvent(t *testing.T) {
	service, store := newTestService(run("run-1", drills.RunStatusNotStarted), run("run-2", drills.RunStatusNotStarted))
	if _, err := service.UpsertEvent(context.Background(), "run-1", EventInput{EventName: "A"}); err != nil {
		t.Fatalf("create run-1: %v", err)
	}
	if _, err := service.UpsertEvent(context.Background(), "run-2", EventInput{EventName: "B"}); err != nil {
		t.Fatalf("create run-2: %v", err)
	}

	if err := store.DeleteByRun(context.Background(), "run-1"); err != nil {
		t.Fatalf("DeleteByRun: %v", err)
	}
	if _, err := service.GetEvent(context.Background(), "run-1"); !errors.Is(err, ErrEventNotFound) {
		t.Fatalf("GET run-1 after DeleteByRun: err = %v, want ErrEventNotFound", err)
	}
	// 其他 run 的事件不受影响。
	if event, err := service.GetEvent(context.Background(), "run-2"); err != nil || event.EventName != "B" {
		t.Fatalf("GET run-2: event = %+v, err = %v; want the untouched event", event, err)
	}
	// 无事件可清时不是错误。
	if err := store.DeleteByRun(context.Background(), "run-missing"); err != nil {
		t.Fatalf("DeleteByRun with nothing to remove: %v", err)
	}
}
