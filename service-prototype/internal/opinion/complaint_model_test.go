package opinion

import (
	"testing"
)

// ─── ComplaintChannel / ComplaintType / ComplaintStatus 枚举 ─────────

// 枚举合法性：channel 五种（现场/电话/网络留言/12345转办/其他）、
// complaint_type 五种（入馆受阻/参观受限/服务态度/设施问题/其他）与
// status 三种（待受理/处理中/已办结）合法，其余值非法。
func TestComplaintEnums(t *testing.T) {
	for _, channel := range validComplaintChannels {
		if !channel.Valid() {
			t.Fatalf("channel %q should be valid", channel)
		}
	}
	for _, invalid := range []ComplaintChannel{"", "邮件", "广播", "12345", "现场投诉"} {
		if invalid.Valid() {
			t.Fatalf("channel %q should be invalid", invalid)
		}
	}
	for _, complaintType := range validComplaintTypes {
		if !complaintType.Valid() {
			t.Fatalf("complaint_type %q should be valid", complaintType)
		}
	}
	for _, invalid := range []ComplaintType{"", "门票", "退票", "入馆"} {
		if invalid.Valid() {
			t.Fatalf("complaint_type %q should be invalid", invalid)
		}
	}
	for _, status := range validComplaintStatuses {
		if !status.Valid() {
			t.Fatalf("status %q should be valid", status)
		}
	}
	for _, invalid := range []ComplaintStatus{"", "待办", "已受理", "结案"} {
		if invalid.Valid() {
			t.Fatalf("status %q should be invalid", invalid)
		}
	}
}

// 状态机迁移合法性：同值 no-op 合法；相邻前进（待受理→处理中→已办结）
// 合法；跳级（待受理→已办结）与回退（处理中→待受理、已办结→处理中、
// 已办结→待受理）非法。
func TestLegalComplaintStatusTransition(t *testing.T) {
	for _, status := range validComplaintStatuses {
		if !legalComplaintStatusTransition(status, status) {
			t.Fatalf("same-value no-op %s -> %s should be legal", status, status)
		}
	}
	adjacent := [][2]ComplaintStatus{
		{ComplaintStatusPending, ComplaintStatusProcessing},
		{ComplaintStatusProcessing, ComplaintStatusClosed},
	}
	for _, pair := range adjacent {
		if !legalComplaintStatusTransition(pair[0], pair[1]) {
			t.Fatalf("adjacent advance %s -> %s should be legal", pair[0], pair[1])
		}
	}
	illegal := [][2]ComplaintStatus{
		{ComplaintStatusPending, ComplaintStatusClosed},
		{ComplaintStatusProcessing, ComplaintStatusPending},
		{ComplaintStatusClosed, ComplaintStatusProcessing},
		{ComplaintStatusClosed, ComplaintStatusPending},
	}
	for _, pair := range illegal {
		if legalComplaintStatusTransition(pair[0], pair[1]) {
			t.Fatalf("illegal transition %s -> %s must be rejected", pair[0], pair[1])
		}
	}
}

// ─── normalizeComplaint（创建入口）────────────────────────────────────

// 创建缺省：complainant/content 必填；channel 缺省 现场、complaint_type
// 缺省 入馆受阻、status 缺省 待受理、handling/handler 缺省 ”、
// closed_at nil、metadata {}、created_by ”；run_id 注入、id 与时间戳
// 由调用方提供。
func TestNormalizeComplaintCreateDefaults(t *testing.T) {
	now := fixedTime
	complaint, err := normalizeComplaint("run-1", ComplaintInput{Complainant: "观众甲", Content: "入馆排队受阻"}, now, "c-1")
	if err != nil {
		t.Fatalf("normalizeComplaint: %v", err)
	}
	if complaint.ID != "c-1" || complaint.RunID != "run-1" {
		t.Fatalf("id/run_id = %q/%q, want c-1/run-1", complaint.ID, complaint.RunID)
	}
	if complaint.Complainant != "观众甲" || complaint.Content != "入馆排队受阻" {
		t.Fatalf("complainant/content = %q/%q, want the provided values", complaint.Complainant, complaint.Content)
	}
	if complaint.Channel != DefaultComplaintChannel {
		t.Fatalf("channel = %q, want the default %s", complaint.Channel, DefaultComplaintChannel)
	}
	if complaint.ComplaintType != DefaultComplaintType {
		t.Fatalf("complaint_type = %q, want the default %s", complaint.ComplaintType, DefaultComplaintType)
	}
	if complaint.Status != DefaultComplaintStatus {
		t.Fatalf("status = %q, want the default %s", complaint.Status, DefaultComplaintStatus)
	}
	if complaint.Handling != "" || complaint.Handler != "" {
		t.Fatalf("handling/handler = %q/%q, want the empty defaults", complaint.Handling, complaint.Handler)
	}
	if complaint.ClosedAt != nil {
		t.Fatalf("closed_at = %v, want nil at creation", complaint.ClosedAt)
	}
	if complaint.Metadata == nil || len(complaint.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", complaint.Metadata)
	}
	if complaint.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", complaint.CreatedBy)
	}
	if !complaint.CreatedAt.Equal(now) || !complaint.UpdatedAt.Equal(now) {
		t.Fatalf("created_at/updated_at = %v/%v, want %v", complaint.CreatedAt, complaint.UpdatedAt, now)
	}
}

// 显式字段原样保留：channel 五种 / complaint_type 五种 / handling /
// handler / metadata / created_by 透传。
func TestNormalizeComplaintPassthrough(t *testing.T) {
	for _, channel := range validComplaintChannels {
		complaint, err := normalizeComplaint("run-1", ComplaintInput{Complainant: "A", Content: "C", Channel: channel}, fixedTime, "c")
		if err != nil {
			t.Fatalf("normalizeComplaint(%s): %v", channel, err)
		}
		if complaint.Channel != channel {
			t.Fatalf("channel = %q, want %q", complaint.Channel, channel)
		}
	}
	for _, complaintType := range validComplaintTypes {
		complaint, err := normalizeComplaint("run-1", ComplaintInput{Complainant: "A", Content: "C", ComplaintType: complaintType}, fixedTime, "c")
		if err != nil {
			t.Fatalf("normalizeComplaint(%s): %v", complaintType, err)
		}
		if complaint.ComplaintType != complaintType {
			t.Fatalf("complaint_type = %q, want %q", complaint.ComplaintType, complaintType)
		}
	}
	complaint, err := normalizeComplaint("run-1", ComplaintInput{
		Complainant: "A",
		Content:     "C",
		Handling:    "安抚并引导至人工通道",
		Handler:     "值班员小李",
		Metadata:    map[string]any{"k": "v"},
		CreatedBy:   "u-admin",
	}, fixedTime, "c")
	if err != nil {
		t.Fatalf("normalizeComplaint: %v", err)
	}
	if complaint.Handling != "安抚并引导至人工通道" || complaint.Handler != "值班员小李" ||
		complaint.Metadata["k"] != "v" || complaint.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", complaint)
	}
}

// 校验失败：缺 complainant / 缺 content / 非法 channel / 非法
// complaint_type / 非法 status / 显式非 待受理 status → ValidationError。
func TestNormalizeComplaintValidation(t *testing.T) {
	cases := []ComplaintInput{
		{Content: "C"},
		{Complainant: "A"},
		{Complainant: "", Content: ""},
		{Complainant: "A", Content: "C", Channel: "邮件"},
		{Complainant: "A", Content: "C", ComplaintType: "门票"},
		{Complainant: "A", Content: "C", Status: "已受理"},
		{Complainant: "A", Content: "C", Status: ComplaintStatusProcessing},
		{Complainant: "A", Content: "C", Status: ComplaintStatusClosed},
	}
	for _, input := range cases {
		if _, err := normalizeComplaint("run-1", input, fixedTime, "c"); err == nil {
			t.Fatalf("normalizeComplaint(%+v) should fail", input)
		} else if _, ok := err.(*ValidationError); !ok {
			t.Fatalf("normalizeComplaint(%+v) err = %T, want *ValidationError", input, err)
		}
	}
}
