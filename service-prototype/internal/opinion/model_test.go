package opinion

import (
	"context"
	"errors"
	"regexp"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// fixedTime is a deterministic clock for the normalize tests.
var fixedTime = time.Date(2026, 8, 2, 12, 0, 0, 0, time.UTC)

// crockford26 matches a 26-character Crockford Base32 ULID (the alphabet
// omits I, L, O and U).
var crockford26 = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// fakeRunSource is a RunSource test double backed by a map of runs; a
// missing id answers ErrRunNotFound.
type fakeRunSource struct {
	runs map[string]drills.Run
}

func (f *fakeRunSource) GetRun(_ context.Context, id string) (drills.Run, error) {
	run, ok := f.runs[id]
	if !ok {
		return drills.Run{}, ErrRunNotFound
	}
	return run, nil
}

// newTestService builds an opinion service over a fresh in-memory store
// and a run source holding the given runs.
func newTestService(runs ...drills.Run) (*Service, *InMemoryStore) {
	store := NewInMemoryStore()
	source := &fakeRunSource{runs: map[string]drills.Run{}}
	for _, run := range runs {
		source.runs[run.ID] = run
	}
	return NewService(store, source), store
}

func run(id string, status drills.RunStatus) drills.Run {
	return drills.Run{ID: id, Status: status}
}

// ─── Level / Status 枚举 ─────────────────────────────────────────────

// 枚举合法性：高热/中热/低热 与 监测中/已预警/已处置 合法，其余值非法。
func TestEnums(t *testing.T) {
	for _, level := range []Level{LevelHigh, LevelMedium, LevelLow} {
		if !level.Valid() {
			t.Fatalf("level %q should be valid", level)
		}
	}
	for _, invalid := range []Level{"", "高", "爆热"} {
		if invalid.Valid() {
			t.Fatalf("level %q should be invalid", invalid)
		}
	}
	for _, status := range []Status{StatusMonitoring, StatusWarning, StatusHandled} {
		if !status.Valid() {
			t.Fatalf("status %q should be valid", status)
		}
	}
	for _, invalid := range []Status{"", "监测", "已结束"} {
		if invalid.Valid() {
			t.Fatalf("status %q should be invalid", invalid)
		}
	}
}

// 状态机迁移规则：同值 no-op 合法；相邻前进 监测中→已预警→已处置 合法；
// 跳级（监测中→已处置）与回退（已预警→监测中、已处置→已预警/监测中）非法。
func TestLegalStatusTransition(t *testing.T) {
	for _, same := range []Status{StatusMonitoring, StatusWarning, StatusHandled} {
		if !legalStatusTransition(same, same) {
			t.Fatalf("same-value transition %s -> %s should be a legal no-op", same, same)
		}
	}
	legal := [][2]Status{
		{StatusMonitoring, StatusWarning},
		{StatusWarning, StatusHandled},
	}
	for _, pair := range legal {
		if !legalStatusTransition(pair[0], pair[1]) {
			t.Fatalf("transition %s -> %s should be legal", pair[0], pair[1])
		}
	}
	illegal := [][2]Status{
		{StatusMonitoring, StatusHandled},
		{StatusWarning, StatusMonitoring},
		{StatusHandled, StatusWarning},
		{StatusHandled, StatusMonitoring},
	}
	for _, pair := range illegal {
		if legalStatusTransition(pair[0], pair[1]) {
			t.Fatalf("transition %s -> %s should be illegal", pair[0], pair[1])
		}
	}
}

// ─── normalizeEvent ──────────────────────────────────────────────────

// 首次创建（create=true）：完整对象，id 由调用方传入、level 缺省 中热、
// status 缺省 监测中、occurred_at 缺省 nil、subject/summary/metadata/
// created_by 缺省空值、created_at/updated_at 为服务端时间且相等。
func TestNormalizeEventCreateDefaults(t *testing.T) {
	event, err := normalizeEvent("run-1", EventInput{EventName: "展厅舆情事件"}, fixedTime, "01ARZ3NDEKTSV4RRFFQ69G5FAV", true)
	if err != nil {
		t.Fatalf("normalizeEvent: %v", err)
	}
	if event.ID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || event.RunID != "run-1" {
		t.Fatalf("id/run_id = %q / %q, want the caller-provided values", event.ID, event.RunID)
	}
	if event.EventName != "展厅舆情事件" {
		t.Fatalf("event_name = %q, want the provided value", event.EventName)
	}
	if event.Subject != "" || event.Summary != "" || event.CreatedBy != "" {
		t.Fatalf("subject/summary/created_by = %q / %q / %q, want empty defaults", event.Subject, event.Summary, event.CreatedBy)
	}
	if event.OccurredAt != nil {
		t.Fatalf("occurred_at = %v, want nil default", event.OccurredAt)
	}
	if event.Level != DefaultLevel {
		t.Fatalf("level = %q, want the default %q", event.Level, DefaultLevel)
	}
	if event.Status != DefaultStatus {
		t.Fatalf("status = %q, want the default %q", event.Status, DefaultStatus)
	}
	if event.Metadata == nil || len(event.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", event.Metadata)
	}
	if !event.CreatedAt.Equal(fixedTime) || !event.UpdatedAt.Equal(fixedTime) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", event.CreatedAt, event.UpdatedAt, fixedTime)
	}
}

// 显式字段原样保留：event_name/subject/summary/occurred_at/level/status/
// metadata/created_by 透传；occurred_at 为指针值。
func TestNormalizeEventPassthrough(t *testing.T) {
	occurredAt := time.Date(2026, 8, 1, 8, 30, 0, 0, time.UTC)
	event, err := normalizeEvent("run-1", EventInput{
		EventName:  "展厅舆情事件",
		Subject:    "涉事主体",
		Summary:    "事件概述",
		OccurredAt: &occurredAt,
		Level:      LevelHigh,
		Status:     StatusMonitoring,
		Metadata:   map[string]any{"source": "merit"},
		CreatedBy:  "u-admin",
	}, fixedTime, "id-1", true)
	if err != nil {
		t.Fatalf("normalizeEvent: %v", err)
	}
	if event.EventName != "展厅舆情事件" || event.Subject != "涉事主体" || event.Summary != "事件概述" ||
		event.Level != LevelHigh || event.Status != StatusMonitoring ||
		event.Metadata["source"] != "merit" || event.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", event)
	}
	if event.OccurredAt == nil || !event.OccurredAt.Equal(occurredAt) {
		t.Fatalf("occurred_at = %v, want %v", event.OccurredAt, occurredAt)
	}
}

// 失败路径（400 语义）：缺 event_name、非法 level、非法 status、首次创建
// 显式 status 非 监测中。
func TestNormalizeEventValidation(t *testing.T) {
	cases := []struct {
		name   string
		input  EventInput
		create bool
	}{
		{"missing event_name", EventInput{Level: LevelMedium, Status: StatusMonitoring}, true},
		{"invalid level", EventInput{EventName: "A", Level: "爆热", Status: StatusMonitoring}, true},
		{"invalid status", EventInput{EventName: "A", Level: LevelMedium, Status: "已结束"}, true},
		{"explicit warning on create", EventInput{EventName: "A", Level: LevelMedium, Status: StatusWarning}, true},
		{"explicit handled on create", EventInput{EventName: "A", Level: LevelMedium, Status: StatusHandled}, true},
	}
	for _, testCase := range cases {
		_, err := normalizeEvent("run-1", testCase.input, fixedTime, "id-1", testCase.create)
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", testCase.name, err)
		}
	}
}

// 更新（create=false）时显式 已预警/已处置 是合法输入：状态机检查由
// 服务层对照既有状态执行（normalize 只校验枚举合法性与默认值）。
func TestNormalizeEventUpdateAcceptsAnyValidStatus(t *testing.T) {
	for _, status := range []Status{StatusMonitoring, StatusWarning, StatusHandled} {
		event, err := normalizeEvent("run-1", EventInput{EventName: "A", Status: status}, fixedTime, "id-1", false)
		if err != nil {
			t.Fatalf("normalizeEvent(create=false, status=%s): %v", status, err)
		}
		if event.Status != status {
			t.Fatalf("status = %q, want %q", event.Status, status)
		}
	}
}
