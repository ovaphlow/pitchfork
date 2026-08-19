package web

import (
	"regexp"
	"strings"
	"testing"
)

// ─── #69 媒体沟通与舆情复盘页 ──────────────────────────────────

// opinionReviewULIDPattern is the 26-character Crockford Base32 ULID
// shape of every id the page renders (the run id anchoring the htmx
// actions and the media-question ids of the answer forms). The page
// itself never constructs ids.
var opinionReviewULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// opinionReviewFixture builds the display payload of the media
// communication and after-action review page: content identical to the
// httpapi demo data, with the demo drill run and the media questions
// carrying fixed 26-character server-side ULIDs so the htmx action URLs
// can be pinned to the ids carried by the data (the page never
// constructs ids). The fixture covers three media questions (one per
// question type 事实类/质疑类/尖锐类, the first two 已回答 with the answer
// and the answered-at instant, the third 未回答 with no answer yet) and
// one after-action review with all five text sections (事件经过/处置亮点/
// 存在问题/经验教训/改进建议) filled.
func opinionReviewFixture() OpinionReviewPageData {
	return OpinionReviewPageData{
		RunID: "06G00QAJ2197VBH6390A1BX79C",
		Questions: []MediaQuestionView{
			{
				ID:           "06G00QAJ2197VBH6390A1BX79D",
				MediaName:    "本市日报",
				Reporter:     "张敏",
				Question:     "请问今天南门入馆排队时间大概多久？",
				QuestionType: "事实类",
				Answer:       "截至上午11时，南门平均排队约40分钟，馆方已增开两条入馆通道并增设遮阳设施。",
				Status:       "已回答",
				AnsweredAt:   "2026-08-03T10:45",
			},
			{
				ID:           "06G00QAJ2197VBH6390A1BX79E",
				MediaName:    "市电视台",
				Reporter:     "李健",
				Question:     "有游客反映预约系统放票后几分钟就约满，是否存在内部放票？",
				QuestionType: "质疑类",
				Answer:       "预约票源全部面向公众公开发放，馆方未预留任何内部名额，放票数据可公开核查。",
				Status:       "已回答",
				AnsweredAt:   "2026-08-03T11:05",
			},
			{
				ID:           "06G00QAJ2197VBH6390A1BX79F",
				MediaName:    "网络自媒体",
				Reporter:     "赵倩",
				Question:     "为什么不在昨天高温预警时就提前限流？这是不是管理失职？",
				QuestionType: "尖锐类",
				Answer:       "",
				Status:       "未回答",
				AnsweredAt:   "",
			},
		},
		Review: ReviewView{
			CaseSummary: "8月3日暑期参观高峰叠加高温天气，南门入馆排队时间过长，游客在网络平台集中吐槽，舆情快速升温；馆方启动应急处置，发布公告、增开通道并召开新闻发布会回应媒体关切。",
			Highlights:  "快速启动舆情响应机制，1小时内发布情况说明；新闻发布会准备充分，媒体问答口径统一。",
			Problems:    "高温预警发布后未第一时间启动限流预案，响应存在滞后；预约系统容量提示不够醒目。",
			Lessons:     "气象预警与客流预警应联动触发，提前启动分流措施；舆情监测需覆盖更多平台，第一时间发现苗头。",
			Suggestions: "建立高温天气自动限流预案并纳入演练；优化预约系统余票提示；常态化开展新闻发布会模拟实训。",
		},
	}
}

// 页面渲染成功且包含关键内容：页面标题「媒体沟通与舆情复盘」与两区块「新闻
// 发布会媒体问答」「舆情复盘」；媒体问答演示数据齐全——问题类型标记（事实类/
// 质疑类/尖锐类）与回答状态（未回答/已回答）均出现，已回答条目渲染 answer 与
// answered_at，媒体/记者/问题文本都在；复盘五段段落标签与内容均渲染（事件经
// 过/处置亮点/存在问题/经验教训/改进建议，与 opinion review 契约字段一一对
// 应）。
func TestRenderOpinionReviewPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderOpinionReview(&output, opinionReviewFixture()); err != nil {
		t.Fatalf("RenderOpinionReview: %v", err)
	}
	rendered := output.String()
	// 页面标题与两区块。
	for _, text := range []string{"媒体沟通与舆情复盘", "新闻发布会媒体问答", "舆情复盘"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 问题类型标记与回答状态标记均出现。
	for _, text := range []string{"事实类", "质疑类", "尖锐类", "未回答", "已回答"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the marker %q", text)
		}
	}
	// 媒体/记者/问题文本与已回答条目的 answer、answered_at。
	for _, text := range []string{
		"本市日报", "市电视台", "网络自媒体", "张敏", "李健", "赵倩",
		"请问今天南门入馆排队时间大概多久？",
		"有游客反映预约系统放票后几分钟就约满，是否存在内部放票？",
		"为什么不在昨天高温预警时就提前限流？这是不是管理失职？",
		"截至上午11时，南门平均排队约40分钟，馆方已增开两条入馆通道并增设遮阳设施。",
		"预约票源全部面向公众公开发放，馆方未预留任何内部名额，放票数据可公开核查。",
		"2026-08-03T10:45", "2026-08-03T11:05",
	} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the question data %q", text)
		}
	}
	// 复盘五段段落标签与内容均渲染（lessons/suggestions 非空）。
	for _, text := range []string{
		"事件经过", "处置亮点", "存在问题", "经验教训", "改进建议",
		"8月3日暑期参观高峰叠加高温天气", "快速启动舆情响应机制",
		"高温预警发布后未第一时间启动限流预案",
		"气象预警与客流预警应联动触发，提前启动分流措施",
		"建立高温天气自动限流预案并纳入演练",
	} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the review section %q", text)
		}
	}
}

// 表单动作与第六节契约一一对应：媒体问答列表 hx-get → /drills/{rid}/
// media-questions、复盘记录 hx-get → /drills/{rid}/review；新增提问
// hx-post → /drills/{rid}/media-questions（字段 media_name/reporter/
// question/question_type/answer/status）；撰写回答 hx-put → /drills/{rid}/
// media-questions/{mqid}（字段 answer/status，mqid 为数据带出的服务端
// id）；保存复盘 hx-put → /drills/{rid}/review（五段字段 case_summary/
// highlights/problems/lessons/suggestions）。动作、完整 URL 与字段名全部
// 钉死。
func TestRenderOpinionReviewFormsAndActions(t *testing.T) {
	var output strings.Builder
	if err := RenderOpinionReview(&output, opinionReviewFixture()); err != nil {
		t.Fatalf("RenderOpinionReview: %v", err)
	}
	rendered := output.String()
	runID := "06G00QAJ2197VBH6390A1BX79C"
	// 两区块的 hx-get 刷新动作。
	for _, resource := range []string{"media-questions", "review"} {
		target := `/crate-api/prototype/v1/drills/` + runID + `/` + resource
		if !strings.Contains(rendered, `hx-get="`+target+`"`) {
			t.Fatalf("refresh action does not target GET %s", target)
		}
	}
	// 新增提问 → POST /drills/{rid}/media-questions（六字段齐全）。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/media-questions"`) {
		t.Fatalf("create form does not target POST /drills/%s/media-questions", runID)
	}
	for _, field := range []string{"media_name", "reporter", "question", "question_type", "answer", "status"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("create form does not carry the field %q", field)
		}
	}
	// 撰写回答 → PUT /drills/{rid}/media-questions/{mqid}（每行一个，mqid
	// 为数据带出的服务端 id，字段 answer/status）。
	questionIDs := []string{"06G00QAJ2197VBH6390A1BX79D", "06G00QAJ2197VBH6390A1BX79E", "06G00QAJ2197VBH6390A1BX79F"}
	for _, mqid := range questionIDs {
		target := `/crate-api/prototype/v1/drills/` + runID + `/media-questions/` + mqid
		if !strings.Contains(rendered, `hx-put="`+target+`"`) {
			t.Fatalf("answer form does not target PUT %s", target)
		}
	}
	// 保存复盘 → PUT /drills/{rid}/review（五段字段齐全）。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/review"`) {
		t.Fatalf("review form does not target PUT /drills/%s/review", runID)
	}
	for _, field := range []string{"case_summary", "highlights", "problems", "lessons", "suggestions"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("review form does not carry the field %q", field)
		}
	}
}

// 跨切面：页面出现的所有 id（hx-get/hx-post/hx-put 目标 URL 中的 rid 与
// 行内表单 URL 中的 mqid）均为 26 位 Crockford Base32 ULID；页面无任何 id
// 构造输入（无 name="id" 输入项）。
func TestRenderOpinionReviewIDsAreULIDsAndNoIDInput(t *testing.T) {
	var output strings.Builder
	if err := RenderOpinionReview(&output, opinionReviewFixture()); err != nil {
		t.Fatalf("RenderOpinionReview: %v", err)
	}
	rendered := output.String()
	idSegment := regexp.MustCompile(`drills/([^/"']+)/`)
	matches := idSegment.FindAllStringSubmatch(rendered, -1)
	if len(matches) == 0 {
		t.Fatalf("rendered page does not reference any run id")
	}
	for _, match := range matches {
		if !opinionReviewULIDPattern.MatchString(match[1]) {
			t.Fatalf("run id %q is not a 26-character Crockford Base32 ULID", match[1])
		}
	}
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("page must not construct ids (no name=\"id\" input)")
	}
}
