package dispatch

import (
	"errors"
	"testing"
)

// normalizeSession 的缺省与透传口径：mode 缺省 实战演练；main_venue /
// joint_venues / metadata / created_by 原样透传（nil 切片/映射归一为
// 空数组/空对象）；非法 mode → ValidationError。
func TestNormalizeSessionDefaultsAndPassthrough(t *testing.T) {
	now := fixedTime
	session, err := normalizeSession("run-1", SessionInput{}, now, "id-1")
	if err != nil {
		t.Fatalf("normalizeSession: %v", err)
	}
	if session.ID != "id-1" || session.RunID != "run-1" {
		t.Fatalf("id/run_id = %q / %q, want the caller-supplied values", session.ID, session.RunID)
	}
	if session.Mode != DefaultMode {
		t.Fatalf("mode = %q, want default %q", session.Mode, DefaultMode)
	}
	if session.MainVenue != "" {
		t.Fatalf("main_venue = %q, want empty default", session.MainVenue)
	}
	if session.JointVenues == nil || len(session.JointVenues) != 0 {
		t.Fatalf("joint_venues = %#v, want an empty array", session.JointVenues)
	}
	if session.Metadata == nil || len(session.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", session.Metadata)
	}
	if session.CreatedBy != "" {
		t.Fatalf("created_by = %q, want empty default", session.CreatedBy)
	}
	if !session.CreatedAt.Equal(now) || !session.UpdatedAt.Equal(now) {
		t.Fatalf("timestamps = %v / %v, want the caller-supplied time", session.CreatedAt, session.UpdatedAt)
	}

	passthrough, err := normalizeSession("run-1", SessionInput{
		Mode:        ModeRemote,
		MainVenue:   "主场馆A",
		JointVenues: []string{"联训场馆B"},
		Metadata:    map[string]any{"source": "merit"},
		CreatedBy:   "u-admin",
	}, now, "id-2")
	if err != nil {
		t.Fatalf("normalizeSession passthrough: %v", err)
	}
	if passthrough.Mode != ModeRemote || passthrough.MainVenue != "主场馆A" ||
		len(passthrough.JointVenues) != 1 || passthrough.JointVenues[0] != "联训场馆B" ||
		passthrough.Metadata["source"] != "merit" || passthrough.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", passthrough)
	}
}

// 非法 mode（非 桌面推演/实战演练/远程协同）→ ValidationError；显式空串
// 视为缺省（与其他资源的枚举缺省口径一致）。
func TestNormalizeSessionRejectsInvalidMode(t *testing.T) {
	for name, input := range map[string]SessionInput{
		"invalid value": {Mode: Mode("演练")},
		"blank value":   {Mode: Mode(" ")},
	} {
		_, err := normalizeSession("run-1", input, fixedTime, "id-1")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want ValidationError", name, err)
		}
	}

	// 显式空串回落到缺省（与其他包的空枚举口径一致）。
	session, err := normalizeSession("run-1", SessionInput{Mode: ""}, fixedTime, "id-1")
	if err != nil {
		t.Fatalf("empty mode: %v", err)
	}
	if session.Mode != DefaultMode {
		t.Fatalf("empty mode -> %q, want %q", session.Mode, DefaultMode)
	}
}
