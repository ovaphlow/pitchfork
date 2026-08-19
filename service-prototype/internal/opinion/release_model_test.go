package opinion

import (
	"testing"
)

// ─── Channel / ReleaseStatus 枚举 ────────────────────────────────────

// 枚举合法性：channel 四种（官网公告/微信公众号/微博官方号/新闻媒体通稿）
// 与 status 四种（草稿/待审核/已发布/已撤回）合法，其余值非法。
func TestReleaseEnums(t *testing.T) {
	for _, channel := range []Channel{ChannelOfficialWebsite, ChannelWechat, ChannelWeiboOfficial, ChannelNewsRelease} {
		if !channel.Valid() {
			t.Fatalf("channel %q should be valid", channel)
		}
	}
	for _, invalid := range []Channel{"", "官网", "电视台", "新闻媒体"} {
		if invalid.Valid() {
			t.Fatalf("channel %q should be invalid", invalid)
		}
	}
	for _, status := range []ReleaseStatus{ReleaseStatusDraft, ReleaseStatusPending, ReleaseStatusPublished, ReleaseStatusWithdrawn} {
		if !status.Valid() {
			t.Fatalf("status %q should be valid", status)
		}
	}
	for _, invalid := range []ReleaseStatus{"", "草", "审核中", "已下线"} {
		if invalid.Valid() {
			t.Fatalf("status %q should be invalid", invalid)
		}
	}
}

// 状态机迁移合法性：同值 no-op 合法；相邻前进（草稿→待审核→已发布→已撤回）
// 合法；跳级（草稿→已发布、待审核→已撤回、草稿→已撤回）与回退（含已发布→
// 待审核、已撤回改回任何状态）非法。
func TestLegalReleaseStatusTransition(t *testing.T) {
	for _, status := range validReleaseStatuses {
		if !legalReleaseStatusTransition(status, status) {
			t.Fatalf("same-value no-op %s -> %s should be legal", status, status)
		}
	}
	adjacent := [][2]ReleaseStatus{
		{ReleaseStatusDraft, ReleaseStatusPending},
		{ReleaseStatusPending, ReleaseStatusPublished},
		{ReleaseStatusPublished, ReleaseStatusWithdrawn},
	}
	for _, pair := range adjacent {
		if !legalReleaseStatusTransition(pair[0], pair[1]) {
			t.Fatalf("adjacent advance %s -> %s should be legal", pair[0], pair[1])
		}
	}
	illegal := [][2]ReleaseStatus{
		{ReleaseStatusDraft, ReleaseStatusPublished},
		{ReleaseStatusDraft, ReleaseStatusWithdrawn},
		{ReleaseStatusPending, ReleaseStatusWithdrawn},
		{ReleaseStatusPending, ReleaseStatusDraft},
		{ReleaseStatusPublished, ReleaseStatusPending},
		{ReleaseStatusPublished, ReleaseStatusDraft},
		{ReleaseStatusWithdrawn, ReleaseStatusDraft},
		{ReleaseStatusWithdrawn, ReleaseStatusPending},
		{ReleaseStatusWithdrawn, ReleaseStatusPublished},
	}
	for _, pair := range illegal {
		if legalReleaseStatusTransition(pair[0], pair[1]) {
			t.Fatalf("illegal transition %s -> %s must be rejected", pair[0], pair[1])
		}
	}
}

// ─── normalizeRelease（创建入口）──────────────────────────────────────

// 创建缺省：title/content 必填；channel 缺省 官网公告、status 缺省 草稿、
// media_name 缺省 ''、published_at nil、metadata {}、created_by ''；
// run_id 注入、id 与时间戳由调用方提供。
func TestNormalizeReleaseCreateDefaults(t *testing.T) {
	now := fixedTime
	release, err := normalizeRelease("run-1", ReleaseInput{Title: "情况说明", Content: "正文"}, now, "rel-1")
	if err != nil {
		t.Fatalf("normalizeRelease: %v", err)
	}
	if release.ID != "rel-1" || release.RunID != "run-1" {
		t.Fatalf("id/run_id = %q/%q, want rel-1/run-1", release.ID, release.RunID)
	}
	if release.Channel != DefaultChannel {
		t.Fatalf("channel = %q, want the default %s", release.Channel, DefaultChannel)
	}
	if release.Title != "情况说明" || release.Content != "正文" {
		t.Fatalf("title/content = %q/%q, want the provided values", release.Title, release.Content)
	}
	if release.MediaName != "" {
		t.Fatalf("media_name = %q, want the empty default", release.MediaName)
	}
	if release.Status != DefaultReleaseStatus {
		t.Fatalf("status = %q, want the default %s", release.Status, DefaultReleaseStatus)
	}
	if release.PublishedAt != nil {
		t.Fatalf("published_at = %v, want nil at creation", release.PublishedAt)
	}
	if release.Metadata == nil || len(release.Metadata) != 0 {
		t.Fatalf("metadata = %#v, want an empty object", release.Metadata)
	}
	if release.CreatedBy != "" {
		t.Fatalf("created_by = %q, want the empty default", release.CreatedBy)
	}
	if !release.CreatedAt.Equal(now) || !release.UpdatedAt.Equal(now) {
		t.Fatalf("created_at/updated_at = %v/%v, want %v", release.CreatedAt, release.UpdatedAt, now)
	}
}

// 显式字段原样保留：channel 四种 / media_name / metadata / created_by 透传。
func TestNormalizeReleasePassthrough(t *testing.T) {
	for _, channel := range validChannels {
		release, err := normalizeRelease("run-1", ReleaseInput{Title: "T", Content: "C", Channel: channel}, fixedTime, "r")
		if err != nil {
			t.Fatalf("normalizeRelease(%s): %v", channel, err)
		}
		if release.Channel != channel {
			t.Fatalf("channel = %q, want %q", release.Channel, channel)
		}
	}
	release, err := normalizeRelease("run-1", ReleaseInput{
		Title:     "T",
		Content:   "C",
		MediaName: "新华社",
		Metadata:  map[string]any{"k": "v"},
		CreatedBy: "u-admin",
	}, fixedTime, "r")
	if err != nil {
		t.Fatalf("normalizeRelease: %v", err)
	}
	if release.MediaName != "新华社" || release.Metadata["k"] != "v" || release.CreatedBy != "u-admin" {
		t.Fatalf("passthrough fields = %+v", release)
	}
}

// 校验失败：缺 title / 缺 content / 非法 channel / 非法 status / 显式非
// 草稿 status → ValidationError。
func TestNormalizeReleaseValidation(t *testing.T) {
	cases := []ReleaseInput{
		{Content: "C"},
		{Title: "T"},
		{Title: "", Content: ""},
		{Title: "T", Content: "C", Channel: "电视台"},
		{Title: "T", Content: "C", Status: "审核中"},
		{Title: "T", Content: "C", Status: ReleaseStatusPending},
		{Title: "T", Content: "C", Status: ReleaseStatusPublished},
		{Title: "T", Content: "C", Status: ReleaseStatusWithdrawn},
	}
	for _, input := range cases {
		if _, err := normalizeRelease("run-1", input, fixedTime, "r"); err == nil {
			t.Fatalf("normalizeRelease(%+v) should fail", input)
		} else if _, ok := err.(*ValidationError); !ok {
			t.Fatalf("normalizeRelease(%+v) err = %T, want *ValidationError", input, err)
		}
	}
}
