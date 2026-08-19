package web

import (
	"regexp"
	"strings"
	"testing"
)

// ─── #69 舆情监测与处置工作台页 ──────────────────────────────────

// opinionULIDPattern is the 26-character Crockford Base32 ULID shape of
// every server id the page renders (the run id anchoring the form URLs
// and the ids of the event/posts/releases/complaints demo rows). The
// page itself never constructs ids.
var opinionULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// opinionFixture builds the display payload of the public-opinion
// monitoring and handling workbench page: content identical to the
// httpapi demo data, with every id carried by the data being a fixed
// 26-character server-side ULID (the run id and the ids of the event/
// posts/releases/complaints rows) so the form actions can be pinned to
// the ids carried by the data (the page never constructs ids). The
// fixture covers one opinion event (高热/已预警), three opinion posts
// covering the sources 微博/抖音/新闻媒体, the sentiments 负面/中性/正面
// and the warning states 未预警/已预警 (the two 未预警 posts anchoring the
// inline warning forms), two releases covering 官网公告 已发布 and 新闻媒
// 体通稿 待审核 (with the media name) and two complaints covering 现场 待
// 受理 and 网络留言 处理中 — each of the four object kinds at least one
// entry, matching the opinion contract Chinese values.
func opinionFixture() OpinionPageData {
	return OpinionPageData{
		RunID: "06G00NC5ZWA3K5G194PSBJ8WNR",
		Event: OpinionEventView{
			ID:         "06G00NC5ZYM6TMDVQ0CTC4GF5C",
			EventName:  "高温大客流引发入馆受阻舆情",
			Subject:    "市博物馆",
			Summary:    "暑期参观高峰叠加高温天气，南门入馆排队时间过长，游客在网络平台集中吐槽，舆情快速升温。",
			OccurredAt: "2026-08-03T09:20",
			Level:      "高热",
			Status:     "已预警",
		},
		Posts: []OpinionPostView{
			{
				ID:         "06G00NC5ZYEDN6WR5KJN1PAZMC",
				Source:     "微博",
				Content:    "排队快两小时了还没进馆，这么热的天老人小孩都在晒，太糟心了！",
				Sentiment:  "负面",
				Heat:       92,
				WarnStatus: "未预警",
			},
			{
				ID:         "06G00NC5ZY1DQAT05XBA8Z3BTG",
				Source:     "抖音",
				Content:    "今天博物馆人真多，南门排长队，不过有遮阳棚和饮水点，秩序还可以",
				Sentiment:  "中性",
				Heat:       68,
				WarnStatus: "未预警",
			},
			{
				ID:         "06G00NC5ZZQV618KTJKCZMZX04",
				Source:     "新闻媒体",
				Content:    "市博物馆暑期推出预约分流入馆，现场秩序总体平稳有序",
				Sentiment:  "正面",
				Heat:       55,
				WarnStatus: "已预警",
			},
		},
		Releases: []OpinionReleaseView{
			{
				ID:        "06G00NC5ZX8Y28F5Y7YB4G73ZC",
				Channel:   "官网公告",
				Title:     "关于暑期参观高峰的公告",
				Content:   "暑期参观高峰期间实行分时预约入馆，请游客合理安排出行时间。",
				MediaName: "",
				Status:    "已发布",
			},
			{
				ID:        "06G00NC5ZZR0WAXSPGM9NEWXRC",
				Channel:   "新闻媒体通稿",
				Title:     "市博物馆多措并举应对参观高峰",
				Content:   "针对近期参观高峰，市博物馆通过增开通道、延长开放时间等措施保障参观秩序。",
				MediaName: "本市日报",
				Status:    "待审核",
			},
		},
		Complaints: []OpinionComplaintView{
			{
				ID:            "06G00NC5ZYZQRCZQJ7N8P5QEW0",
				Complainant:   "王女士",
				Channel:       "现场",
				ComplaintType: "入馆受阻",
				Content:       "在南门排队近两小时未能入馆，天气炎热，要求尽快协调入馆。",
				Status:        "待受理",
				Handling:      "",
				Handler:       "",
			},
			{
				ID:            "06G00NC5ZZBD8RTV93PJY13Z1M",
				Complainant:   "李先生",
				Channel:       "网络留言",
				ComplaintType: "参观受限",
				Content:       "展厅限流后部分展区无法参观，希望安排补看。",
				Status:        "处理中",
				Handling:      "已电话联系游客，安排次日优先参观",
				Handler:       "客服部张伟",
			},
		},
	}
}

// 舆情监测与处置工作台页渲染成功且包含关键内容：页面标题「舆情应对实训」与
// 四大区块标题（舆情事件背景/舆情信息流/信息发布/投诉处理）；演示数据齐
// 全——事件（事件名称/涉事主体/等级 高热/状态 已预警）、舆情信息（来源 微博/
// 抖音/新闻媒体、情感 负面/中性/正面、热度数值、预警状态 未预警/已预警）、
// 发布（官网公告 已发布、新闻媒体通稿 待审核 含媒体名称 本市日报、标题/内容）、
// 投诉（现场 待受理、网络留言 处理中，含投诉人/类型/处理措施/处理人）。空数
// 据或错数据不得通过。
func TestRenderOpinionPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderOpinion(&output, opinionFixture()); err != nil {
		t.Fatalf("RenderOpinion: %v", err)
	}
	rendered := output.String()
	// 页面标题与四大区块标题。
	for _, text := range []string{"舆情应对实训", "舆情事件背景", "舆情信息流", "信息发布", "投诉处理"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 舆情事件背景：事件名称/涉事主体/摘要/等级/状态。
	for _, text := range []string{"高温大客流引发入馆受阻舆情", "市博物馆", "暑期参观高峰叠加高温天气", "高热", "已预警", "2026-08-03T09:20"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the event field %q", text)
		}
	}
	// 舆情信息流：来源/情感/热度/预警状态全覆盖。
	for _, text := range []string{"微博", "抖音", "新闻媒体", "负面", "中性", "正面", "未预警", "排队快两小时了还没进馆", "今天博物馆人真多", "市博物馆暑期推出预约分流入馆", ">92<", ">68<", ">55<"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the post %q", text)
		}
	}
	// 信息发布：渠道/标题/内容/媒体名称/状态。
	for _, text := range []string{"官网公告", "新闻媒体通稿", "关于暑期参观高峰的公告", "市博物馆多措并举应对参观高峰", "暑期参观高峰期间实行分时预约入馆", "本市日报", "已发布", "待审核"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the release %q", text)
		}
	}
	// 投诉处理：投诉人/渠道/类型/内容/状态/处理措施/处理人。
	for _, text := range []string{"王女士", "李先生", "现场", "网络留言", "入馆受阻", "参观受限", "在南门排队近两小时未能入馆", "展厅限流后部分展区无法参观", "待受理", "处理中", "已电话联系游客，安排次日优先参观", "客服部张伟"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the complaint %q", text)
		}
	}
}

// 四大区块的 htmx 动作与第六节 API 契约一一对应，hx URL 钉死完整前缀：舆情
// 事件背景 → hx-get /drills/{rid}/opinion-event 刷新 + hx-put
// /drills/{rid}/opinion-event 事件 upsert（字段 event_name/subject/
// summary/occurred_at/level/status）；舆情信息流 → hx-get /drills/{rid}/
// posts + hx-post /drills/{rid}/posts 新增（字段 source/content/
// sentiment/heat）+ 行内 hx-put /drills/{rid}/posts/{pid} 预警（字段
// warn_status=已预警，pid 为数据带出的未预警行 id）；信息发布 → hx-get
// /drills/{rid}/releases + hx-post /drills/{rid}/releases 新建（字段
// channel/title/content/media_name）+ hx-put /drills/{rid}/releases/
// {lid} 状态流转（字段 status）；投诉处理 → hx-get /drills/{rid}/complaints
// + hx-post /drills/{rid}/complaints 新增（字段 complainant/channel/
// complaint_type/content）+ hx-put /drills/{rid}/complaints/{cid} 受理/办
// 结（字段 complainant/channel/complaint_type/content/status/handling/
// handler）。页面按实际使用的动作断言，不含 hx-delete 动作。
func TestRenderOpinionForms(t *testing.T) {
	var output strings.Builder
	if err := RenderOpinion(&output, opinionFixture()); err != nil {
		t.Fatalf("RenderOpinion: %v", err)
	}
	rendered := output.String()
	runID := "06G00NC5ZWA3K5G194PSBJ8WNR"
	eventID := "06G00NC5ZYM6TMDVQ0CTC4GF5C"
	postIDs := []string{"06G00NC5ZYEDN6WR5KJN1PAZMC", "06G00NC5ZY1DQAT05XBA8Z3BTG"}
	releaseIDs := []string{"06G00NC5ZX8Y28F5Y7YB4G73ZC", "06G00NC5ZZR0WAXSPGM9NEWXRC"}
	complaintIDs := []string{"06G00NC5ZYZQRCZQJ7N8P5QEW0", "06G00NC5ZZBD8RTV93PJY13Z1M"}

	// 四大区块的 hx-get 刷新动作。
	for _, suffix := range []string{"opinion-event", "posts", "releases", "complaints"} {
		if !strings.Contains(rendered, `hx-get="/crate-api/prototype/v1/drills/`+runID+`/`+suffix+`"`) {
			t.Fatalf("page does not carry the hx-get refresh of /drills/%s/%s", runID, suffix)
		}
	}
	// 舆情事件 upsert → PUT /drills/{rid}/opinion-event，字段 event_name/
	// subject/summary/occurred_at/level/status。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/opinion-event"`) {
		t.Fatalf("event form does not target PUT /drills/%s/opinion-event", runID)
	}
	for _, field := range []string{"event_name", "subject", "summary", "occurred_at", "level", "status"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("event form does not carry the %s field", field)
		}
	}
	// 新增舆情 → POST /drills/{rid}/posts，字段 source/content/sentiment/heat。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/posts"`) {
		t.Fatalf("post create form does not target POST /drills/%s/posts", runID)
	}
	for _, field := range []string{"source", "content", "sentiment", "heat"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("post create form does not carry the %s field", field)
		}
	}
	// 行内预警 → PUT /drills/{rid}/posts/{pid}（未预警行各一），字段
	// warn_status=已预警。
	for _, postID := range postIDs {
		if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/posts/`+postID+`"`) {
			t.Fatalf("warning form does not target PUT /drills/%s/posts/%s", runID, postID)
		}
	}
	if !strings.Contains(rendered, `name="warn_status"`) || !strings.Contains(rendered, `value="已预警"`) {
		t.Fatalf("warning form does not carry warn_status=已预警")
	}
	// 新建发布 → POST /drills/{rid}/releases，字段 channel/title/content/media_name。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/releases"`) {
		t.Fatalf("release create form does not target POST /drills/%s/releases", runID)
	}
	for _, field := range []string{"channel", "title", "content", "media_name"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("release create form does not carry the %s field", field)
		}
	}
	// 状态流转 → PUT /drills/{rid}/releases/{lid}（每行各一），字段 status。
	for _, releaseID := range releaseIDs {
		if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/releases/`+releaseID+`"`) {
			t.Fatalf("release status form does not target PUT /drills/%s/releases/%s", runID, releaseID)
		}
	}
	if !strings.Contains(rendered, `name="status"`) {
		t.Fatalf("release status form does not carry the status field")
	}
	// 登记投诉 → POST /drills/{rid}/complaints，字段 complainant/channel/
	// complaint_type/content。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/complaints"`) {
		t.Fatalf("complaint create form does not target POST /drills/%s/complaints", runID)
	}
	for _, field := range []string{"complainant", "channel", "complaint_type", "content"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("complaint create form does not carry the %s field", field)
		}
	}
	// 受理/办结 → PUT /drills/{rid}/complaints/{cid}（每行各一），字段
	// complainant/channel/complaint_type/content/status/handling/handler。
	for _, complaintID := range complaintIDs {
		if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/complaints/`+complaintID+`"`) {
			t.Fatalf("complaint handling form does not target PUT /drills/%s/complaints/%s", runID, complaintID)
		}
	}
	for _, field := range []string{"complainant", "channel", "complaint_type", "content", "status", "handling", "handler"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("complaint handling form does not carry the %s field", field)
		}
	}
	// 事件编号以数据带出的 id 渲染（页面不构造 id）。
	if !strings.Contains(rendered, eventID) {
		t.Fatalf("rendered page does not render the data-carried event id %s", eventID)
	}
	// 本卡无删除动作，不得虚构 hx-delete。
	if strings.Contains(rendered, "hx-delete") {
		t.Fatalf("page must not carry an hx-delete action (no delete forms in this card)")
	}
}

// 跨切面：页面出现的全部服务端 id（表单 URL 中的 rid 与各列表行的 id）均匹
// 配 26 位 Crockford Base32 正则 ^[0-9A-HJKMNP-TV-Z]{26}$；同一渲染内所有
// 动作共享同一 rid 锚点；pid/lid/cid 路径段为数据带出的行 id；页面无任何 id
// 构造输入（无 name="id" 输入项）。
func TestRenderOpinionIDsAreULIDsAndNoIDInput(t *testing.T) {
	var output strings.Builder
	if err := RenderOpinion(&output, opinionFixture()); err != nil {
		t.Fatalf("RenderOpinion: %v", err)
	}
	rendered := output.String()
	runID := "06G00NC5ZWA3K5G194PSBJ8WNR"

	// 表单 URL 中的 rid：所有 /drills/{rid}/ 段均为同一数据带出的固定 ULID。
	ridSegment := regexp.MustCompile(`drills/([^/"']+)/`)
	ridMatches := ridSegment.FindAllStringSubmatch(rendered, -1)
	if len(ridMatches) == 0 {
		t.Fatalf("rendered page does not reference any run id")
	}
	for _, match := range ridMatches {
		if match[1] != runID {
			t.Fatalf("run id %q in a form URL, want the data-carried %q", match[1], runID)
		}
		if !opinionULIDPattern.MatchString(match[1]) {
			t.Fatalf("run id %q is not a 26-character Crockford Base32 ULID", match[1])
		}
	}
	// 行内表单 URL 中的 pid/lid/cid：恰为数据带出的行 id 且均为 26 位 ULID。
	rowSegments := []string{"posts", "releases", "complaints"}
	for _, segment := range rowSegments {
		rowPattern := regexp.MustCompile(`drills/` + runID + `/` + segment + `/([^/"']+)`)
		matches := rowPattern.FindAllStringSubmatch(rendered, -1)
		if len(matches) == 0 {
			t.Fatalf("rendered page does not carry any /drills/%s/%s/{id} form URL", runID, segment)
		}
		for _, match := range matches {
			if !opinionULIDPattern.MatchString(match[1]) {
				t.Fatalf("%s row id %q is not a 26-character Crockford Base32 ULID", segment, match[1])
			}
		}
	}
	// 各列表行的 id（事件/舆情/发布/投诉编号）均为 26 位 ULID。
	rowIDPattern := regexp.MustCompile(`class="(?:event|post|release|complaint)-id">([^<]+)<`)
	rowMatches := rowIDPattern.FindAllStringSubmatch(rendered, -1)
	if len(rowMatches) == 0 {
		t.Fatalf("rendered page does not render any list-row id")
	}
	for _, match := range rowMatches {
		if !opinionULIDPattern.MatchString(match[1]) {
			t.Fatalf("list-row id %q is not a 26-character Crockford Base32 ULID", match[1])
		}
	}
	// 页面不构造 ID：表单只携带契约字段，没有任何 id 输入项。
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("forms must not carry an id input (the page never constructs ids)")
	}
}
