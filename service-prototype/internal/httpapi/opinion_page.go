package httpapi

import (
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// opinionPagePath is the server-rendered public-opinion monitoring and
// handling workbench page (htmx SSR, no shared client, no database).
const opinionPagePath = "/demo/opinion"

// handleOpinionPage renders the public-opinion monitoring and handling
// workbench page. Non-GET requests on the page path yield the
// repository-standard 405 JSON with Allow: GET (same convention as the
// API resource routes). The display data is injected in memory from the
// built-in demo data below — no database, no API call. The demo rows
// carry no ids, so each render mints a fresh 26-character Crockford
// Base32 ULID for the demo drill run (the {rid} anchor of every form)
// and for the event/posts/releases/complaints rows, exactly like the
// backing API does at creation; the page itself never constructs ids.
// The four blocks (舆情事件背景 / 舆情信息流 / 信息发布 / 投诉处理) carry
// the hx-get refresh actions and the hx-post / hx-put forms targeting
// the sixth-section API contract endpoints under
// /crate-api/prototype/v1/drills/{rid}/..., with the rid and the
// per-row ids carried by the demo data.
func handleOpinionPage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderOpinion(w, opinionPageData()); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// opinionPageData builds the display payload of the public-opinion
// monitoring and handling workbench page from the built-in demo data:
// one opinion event (等级 高热, 状态 已预警), three opinion posts
// covering the sources 微博/抖音/新闻媒体, the sentiments 负面/中性/正面
// and the warning states 未预警/已预警 (the 未预警 posts anchor the
// inline warning forms), two releases covering 官网公告 已发布 and 新闻媒
// 体通稿 待审核 (with the media name), and two complaints covering 现场
// 待受理 and 网络留言 处理中. The field values follow the opinion
// contracts word for word (Chinese enum values included); the demo rows
// carry no ids, so each render mints fresh server-side ids for the run
// anchor and every row.
func opinionPageData() web.OpinionPageData {
	return web.OpinionPageData{
		RunID: ulid.New(),
		Event: web.OpinionEventView{
			ID:         ulid.New(),
			EventName:  "高温大客流引发入馆受阻舆情",
			Subject:    "市博物馆",
			Summary:    "暑期参观高峰叠加高温天气，南门入馆排队时间过长，游客在网络平台集中吐槽，舆情快速升温。",
			OccurredAt: "2026-08-03T09:20",
			Level:      string(opinion.LevelHigh),
			Status:     string(opinion.StatusWarning),
		},
		Posts: []web.OpinionPostView{
			{
				ID:         ulid.New(),
				Source:     string(opinion.SourceWeibo),
				Content:    "排队快两小时了还没进馆，这么热的天老人小孩都在晒，太糟心了！",
				Sentiment:  string(opinion.SentimentNegative),
				Heat:       92,
				WarnStatus: string(opinion.WarnStatusPending),
			},
			{
				ID:         ulid.New(),
				Source:     string(opinion.SourceDouyin),
				Content:    "今天博物馆人真多，南门排长队，不过有遮阳棚和饮水点，秩序还可以",
				Sentiment:  string(opinion.SentimentNeutral),
				Heat:       68,
				WarnStatus: string(opinion.WarnStatusPending),
			},
			{
				ID:         ulid.New(),
				Source:     string(opinion.SourceNews),
				Content:    "市博物馆暑期推出预约分流入馆，现场秩序总体平稳有序",
				Sentiment:  string(opinion.SentimentPositive),
				Heat:       55,
				WarnStatus: string(opinion.WarnStatusWarned),
			},
		},
		Releases: []web.OpinionReleaseView{
			{
				ID:        ulid.New(),
				Channel:   string(opinion.ChannelOfficialWebsite),
				Title:     "关于暑期参观高峰的公告",
				Content:   "暑期参观高峰期间实行分时预约入馆，请游客合理安排出行时间。",
				MediaName: "",
				Status:    string(opinion.ReleaseStatusPublished),
			},
			{
				ID:        ulid.New(),
				Channel:   string(opinion.ChannelNewsRelease),
				Title:     "市博物馆多措并举应对参观高峰",
				Content:   "针对近期参观高峰，市博物馆通过增开通道、延长开放时间等措施保障参观秩序。",
				MediaName: "本市日报",
				Status:    string(opinion.ReleaseStatusPending),
			},
		},
		Complaints: []web.OpinionComplaintView{
			{
				ID:            ulid.New(),
				Complainant:   "王女士",
				Channel:       string(opinion.ComplaintChannelOnSite),
				ComplaintType: string(opinion.ComplaintTypeEntryBlocked),
				Content:       "在南门排队近两小时未能入馆，天气炎热，要求尽快协调入馆。",
				Status:        string(opinion.ComplaintStatusPending),
				Handling:      "",
				Handler:       "",
			},
			{
				ID:            ulid.New(),
				Complainant:   "李先生",
				Channel:       string(opinion.ComplaintChannelOnline),
				ComplaintType: string(opinion.ComplaintTypeVisitLimited),
				Content:       "展厅限流后部分展区无法参观，希望安排补看。",
				Status:        string(opinion.ComplaintStatusProcessing),
				Handling:      "已电话联系游客，安排次日优先参观",
				Handler:       "客服部张伟",
			},
		},
	}
}
