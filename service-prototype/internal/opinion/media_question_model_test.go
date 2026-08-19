package opinion

import (
	"errors"
	"testing"
)

// ─── QuestionType / AnswerStatus 枚举 ────────────────────────────────

// 枚举合法性：事实类/质疑类/尖锐类、未回答/已回答 合法，其余值非法。
func TestMediaQuestionEnums(t *testing.T) {
	for _, questionType := range []QuestionType{QuestionTypeFactual, QuestionTypeChallenging, QuestionTypeSharp} {
		if !questionType.Valid() {
			t.Fatalf("question_type %q should be valid", questionType)
		}
	}
	for _, invalid := range []QuestionType{"", "其他", "诱导类"} {
		if invalid.Valid() {
			t.Fatalf("question_type %q should be invalid", invalid)
		}
	}
	for _, status := range []AnswerStatus{AnswerStatusPending, AnswerStatusAnswered} {
		if !status.Valid() {
			t.Fatalf("status %q should be valid", status)
		}
	}
	for _, invalid := range []AnswerStatus{"", "回答中", "已跳过"} {
		if invalid.Valid() {
			t.Fatalf("status %q should be invalid", invalid)
		}
	}
}

// ─── normalizeMediaQuestion ──────────────────────────────────────────

// 创建（缺省路径）：完整对象，id 由调用方传入、media_name/question 必填
// 透传、reporter 缺省 ”、question_type 缺省 事实类、answer 缺省 ”、
// status 缺省 未回答、answered_at 缺省 nil、metadata 缺省 {}、created_by
// 缺省 ”，created_at/updated_at 为服务端时间且相等。
func TestNormalizeMediaQuestionCreateDefaults(t *testing.T) {
	question, err := normalizeMediaQuestion("run-1", MediaQuestionInput{
		MediaName: "新华网",
		Question:  "请问本次事件的起因是什么？",
	}, fixedTime, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	if err != nil {
		t.Fatalf("normalizeMediaQuestion: %v", err)
	}
	if question.ID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || question.RunID != "run-1" {
		t.Fatalf("id/run_id = %q / %q, want the caller-provided values", question.ID, question.RunID)
	}
	if question.MediaName != "新华网" || question.Question != "请问本次事件的起因是什么？" {
		t.Fatalf("media_name/question = %q / %q, want the provided values", question.MediaName, question.Question)
	}
	if question.Reporter != "" {
		t.Fatalf("reporter = %q, want the empty default", question.Reporter)
	}
	if question.QuestionType != DefaultQuestionType {
		t.Fatalf("question_type = %q, want the default %q", question.QuestionType, DefaultQuestionType)
	}
	if question.Answer != "" {
		t.Fatalf("answer = %q, want the empty default", question.Answer)
	}
	if question.Status != DefaultAnswerStatus {
		t.Fatalf("status = %q, want the default %q", question.Status, DefaultAnswerStatus)
	}
	if question.AnsweredAt != nil {
		t.Fatalf("answered_at = %v, want nil at creation", question.AnsweredAt)
	}
	if question.Metadata == nil || len(question.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", question.Metadata)
	}
	if question.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", question.CreatedBy)
	}
	if !question.CreatedAt.Equal(fixedTime) || !question.UpdatedAt.Equal(fixedTime) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", question.CreatedAt, question.UpdatedAt, fixedTime)
	}
}

// 显式字段原样保留：media_name/reporter/question/question_type/answer/
// metadata/created_by 透传。
func TestNormalizeMediaQuestionPassthrough(t *testing.T) {
	question, err := normalizeMediaQuestion("run-1", MediaQuestionInput{
		MediaName:    "澎湃新闻",
		Reporter:     "记者小王",
		Question:     "网上流传的视频是否属实？",
		QuestionType: QuestionTypeChallenging,
		Answer:       "该视频经核实存在断章取义……",
		Metadata:     map[string]any{"platform": "press"},
		CreatedBy:    "u-admin",
	}, fixedTime, "id-1")
	if err != nil {
		t.Fatalf("normalizeMediaQuestion: %v", err)
	}
	if question.MediaName != "澎湃新闻" || question.Reporter != "记者小王" ||
		question.Question != "网上流传的视频是否属实？" || question.QuestionType != QuestionTypeChallenging ||
		question.Answer != "该视频经核实存在断章取义……" ||
		question.Metadata["platform"] != "press" || question.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", question)
	}
}

// 失败路径（400 语义）：缺 media_name、缺 question、非法 question_type/
// status、首次创建显式 已回答。
func TestNormalizeMediaQuestionValidation(t *testing.T) {
	cases := []struct {
		name  string
		input MediaQuestionInput
	}{
		{"missing media_name", MediaQuestionInput{Question: "A"}},
		{"missing question", MediaQuestionInput{MediaName: "新华网"}},
		{"invalid question_type", MediaQuestionInput{MediaName: "新华网", Question: "A", QuestionType: "诱导类"}},
		{"invalid status", MediaQuestionInput{MediaName: "新华网", Question: "A", Status: "回答中"}},
		{"explicit answered on create", MediaQuestionInput{MediaName: "新华网", Question: "A", Status: AnswerStatusAnswered}},
	}
	for _, testCase := range cases {
		_, err := normalizeMediaQuestion("run-1", testCase.input, fixedTime, "id-1")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", testCase.name, err)
		}
	}
}
