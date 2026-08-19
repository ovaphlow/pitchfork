package opinion

import (
	"errors"
	"testing"
)

// ─── Source / Sentiment / WarnStatus 枚举 ────────────────────────────

// 枚举合法性：微博/抖音/新闻媒体/论坛/其他、负面/中性/正面、未预警/已预警
// 合法，其余值非法。
func TestPostEnums(t *testing.T) {
	for _, source := range []Source{SourceWeibo, SourceDouyin, SourceNews, SourceForum, SourceOther} {
		if !source.Valid() {
			t.Fatalf("source %q should be valid", source)
		}
	}
	for _, invalid := range []Source{"", "微信", "小红书"} {
		if invalid.Valid() {
			t.Fatalf("source %q should be invalid", invalid)
		}
	}
	for _, sentiment := range []Sentiment{SentimentNegative, SentimentNeutral, SentimentPositive} {
		if !sentiment.Valid() {
			t.Fatalf("sentiment %q should be valid", sentiment)
		}
	}
	for _, invalid := range []Sentiment{"", "消极", "非常负面"} {
		if invalid.Valid() {
			t.Fatalf("sentiment %q should be invalid", invalid)
		}
	}
	for _, status := range []WarnStatus{WarnStatusPending, WarnStatusWarned} {
		if !status.Valid() {
			t.Fatalf("warn_status %q should be valid", status)
		}
	}
	for _, invalid := range []WarnStatus{"", "预警中", "已结束"} {
		if invalid.Valid() {
			t.Fatalf("warn_status %q should be invalid", invalid)
		}
	}
}

// ─── normalizePost ───────────────────────────────────────────────────

// 创建（缺省路径）：完整对象，id 由调用方传入、source 缺省 微博、sentiment
// 缺省 负面、heat 缺省 0、warn_status 缺省 未预警、warned_at 缺省 nil、
// metadata 缺省 {}、created_by 缺省 ”，created_at/updated_at 为服务端
// 时间且相等。
func TestNormalizePostCreateDefaults(t *testing.T) {
	post, err := normalizePost("run-1", PostInput{Content: "展厅入口聚集大量游客"}, fixedTime, "01ARZ3NDEKTSV4RRFFQ69G5FAV")
	if err != nil {
		t.Fatalf("normalizePost: %v", err)
	}
	if post.ID != "01ARZ3NDEKTSV4RRFFQ69G5FAV" || post.RunID != "run-1" {
		t.Fatalf("id/run_id = %q / %q, want the caller-provided values", post.ID, post.RunID)
	}
	if post.Content != "展厅入口聚集大量游客" {
		t.Fatalf("content = %q, want the provided value", post.Content)
	}
	if post.Source != DefaultSource {
		t.Fatalf("source = %q, want the default %q", post.Source, DefaultSource)
	}
	if post.Sentiment != DefaultSentiment {
		t.Fatalf("sentiment = %q, want the default %q", post.Sentiment, DefaultSentiment)
	}
	if post.Heat != 0 {
		t.Fatalf("heat = %d, want the default 0", post.Heat)
	}
	if post.WarnStatus != DefaultWarnStatus {
		t.Fatalf("warn_status = %q, want the default %q", post.WarnStatus, DefaultWarnStatus)
	}
	if post.WarnedAt != nil {
		t.Fatalf("warned_at = %v, want nil at creation", post.WarnedAt)
	}
	if post.Metadata == nil || len(post.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", post.Metadata)
	}
	if post.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", post.CreatedBy)
	}
	if !post.CreatedAt.Equal(fixedTime) || !post.UpdatedAt.Equal(fixedTime) {
		t.Fatalf("created_at/updated_at = %v / %v, want %v", post.CreatedAt, post.UpdatedAt, fixedTime)
	}
}

// 显式字段原样保留：content/source/sentiment/heat/metadata/created_by
// 透传。
func TestNormalizePostPassthrough(t *testing.T) {
	post, err := normalizePost("run-1", PostInput{
		Content:    "展厅出口出现踩踏风险",
		Source:     SourceNews,
		Sentiment:  SentimentPositive,
		Heat:       88,
		Metadata:   map[string]any{"platform": "news"},
		CreatedBy:  "u-admin",
	}, fixedTime, "id-1")
	if err != nil {
		t.Fatalf("normalizePost: %v", err)
	}
	if post.Content != "展厅出口出现踩踏风险" || post.Source != SourceNews ||
		post.Sentiment != SentimentPositive || post.Heat != 88 ||
		post.Metadata["platform"] != "news" || post.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", post)
	}
}

// 失败路径（400 语义）：缺 content、非法 source/sentiment/warn_status、
// heat 越界（<0 或 >100）、首次创建显式 已预警。
func TestNormalizePostValidation(t *testing.T) {
	cases := []struct {
		name  string
		input PostInput
	}{
		{"missing content", PostInput{Source: SourceWeibo, Sentiment: SentimentNegative, Heat: 0}},
		{"invalid source", PostInput{Content: "A", Source: "微信"}},
		{"invalid sentiment", PostInput{Content: "A", Sentiment: "消极"}},
		{"invalid warn_status", PostInput{Content: "A", WarnStatus: "预警中"}},
		{"heat below range", PostInput{Content: "A", Heat: -1}},
		{"heat above range", PostInput{Content: "A", Heat: 101}},
		{"explicit warned on create", PostInput{Content: "A", WarnStatus: WarnStatusWarned}},
	}
	for _, testCase := range cases {
		_, err := normalizePost("run-1", testCase.input, fixedTime, "id-1")
		var validationError *ValidationError
		if !errors.As(err, &validationError) {
			t.Fatalf("%s: err = %v, want a ValidationError", testCase.name, err)
		}
	}
}
