package httpapi

import (
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// opinionReviewPagePath is the server-rendered media communication and
// after-action review page (htmx SSR, no shared client, no database).
const opinionReviewPagePath = "/demo/opinion/review"

// handleOpinionReviewPage renders the media communication and
// after-action review page. Non-GET requests on the page path yield the
// repository-standard 405 JSON with Allow: GET (same convention as the
// API resource routes). The display data is injected in memory from the
// built-in demo data below — no database, no API call. The demo rows
// carry no ids, so each render mints a fresh 26-character Crockford
// Base32 ULID for the demo drill run (the {rid} anchor of every htmx
// action) and for the media-question rows (the {mqid} path segment of
// the answer forms), exactly like the backing API does at creation; the
// page itself never constructs ids. The two blocks (新闻发布会媒体问答 / 舆
// 情复盘) carry the hx-get refresh actions and the hx-post / hx-put
// forms targeting the sixth-section API contract endpoints under
// /crate-api/prototype/v1/drills/{rid}/..., with the rid and the
// per-row mqids carried by the demo data. The htmx actions are
// same-origin requests, so no CORS preflight applies (the page has no
// cross-origin write methods).
func handleOpinionReviewPage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderOpinionReview(w, opinionReviewPageData()); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// opinionReviewPageData builds the display payload of the media
// communication and after-action review page from the built-in demo
// data: three media questions covering the question types 事实类/质疑类/
// 尖锐类 and the answering states 已回答/未回答 (the answered rows carry
// the answer and the answered_at instant, the unanswered row has no
// answer yet), and one after-action review with all five text sections
// (事件经过/处置亮点/存在问题/经验教训/改进建议) filled. The field values
// follow the opinion contracts word for word (Chinese enum values
// included); the demo rows carry no ids, so each render mints fresh
// server-side ids for the run anchor and every question row.
func opinionReviewPageData() web.OpinionReviewPageData {
	return web.OpinionReviewPageData{
		RunID: ulid.New(),
		Questions: []web.MediaQuestionView{
			{
				ID:           ulid.New(),
				MediaName:    "本市日报",
				Reporter:     "张敏",
				Question:     "请问今天南门入馆排队时间大概多久？",
				QuestionType: string(opinion.QuestionTypeFactual),
				Answer:       "截至上午11时，南门平均排队约40分钟，馆方已增开两条入馆通道并增设遮阳设施。",
				Status:       string(opinion.AnswerStatusAnswered),
				AnsweredAt:   "2026-08-03T10:45",
			},
			{
				ID:           ulid.New(),
				MediaName:    "市电视台",
				Reporter:     "李健",
				Question:     "有游客反映预约系统放票后几分钟就约满，是否存在内部放票？",
				QuestionType: string(opinion.QuestionTypeChallenging),
				Answer:       "预约票源全部面向公众公开发放，馆方未预留任何内部名额，放票数据可公开核查。",
				Status:       string(opinion.AnswerStatusAnswered),
				AnsweredAt:   "2026-08-03T11:05",
			},
			{
				ID:           ulid.New(),
				MediaName:    "网络自媒体",
				Reporter:     "赵倩",
				Question:     "为什么不在昨天高温预警时就提前限流？这是不是管理失职？",
				QuestionType: string(opinion.QuestionTypeSharp),
				Answer:       "",
				Status:       string(opinion.AnswerStatusPending),
				AnsweredAt:   "",
			},
		},
		Review: web.ReviewView{
			CaseSummary: "8月3日暑期参观高峰叠加高温天气，南门入馆排队时间过长，游客在网络平台集中吐槽，舆情快速升温；馆方启动应急处置，发布公告、增开通道并召开新闻发布会回应媒体关切。",
			Highlights:  "快速启动舆情响应机制，1小时内发布情况说明；新闻发布会准备充分，媒体问答口径统一。",
			Problems:    "高温预警发布后未第一时间启动限流预案，响应存在滞后；预约系统容量提示不够醒目。",
			Lessons:     "气象预警与客流预警应联动触发，提前启动分流措施；舆情监测需覆盖更多平台，第一时间发现苗头。",
			Suggestions: "建立高温天气自动限流预案并纳入演练；优化预约系统余票提示；常态化开展新闻发布会模拟实训。",
		},
	}
}
