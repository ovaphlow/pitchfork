package web

import (
	"html/template"
	"io"
)

// OpinionPageData carries the display data of the public-opinion
// monitoring and handling workbench page (舆情监测与处置工作台). The page
// renders purely from this in-memory payload — no database access, no
// API call — so the caller builds it from the built-in demo data and
// mints the server-side ids. The page is a write-oriented demo: the
// event background block (舆情事件背景), the opinion-feed block (舆情信
// 息流), the release block (信息发布) and the complaint block (投诉处理)
// carry forms whose hx actions anchor on the run id of the demo drill
// run, and the event/posts/releases/complaints demo rows carry their
// server-minted ids.
type OpinionPageData struct {
	// RunID is the 26-character ULID of the demo drill run the forms
	// anchor on (the {rid} path segment). The page itself never
	// constructs ids.
	RunID      string
	Event      OpinionEventView
	Posts      []OpinionPostView
	Releases   []OpinionReleaseView
	Complaints []OpinionComplaintView
}

// OpinionEventView is the opinion event background (舆情事件背景) of the
// page: the server-minted event id, the event name, the subject
// (涉事主体), the summary, the occurred-at instant, the opinion level
// (高热/中热/低热) and the event-level disposition status (监测中/已预
// 警/已处置).
type OpinionEventView struct {
	ID         string
	EventName  string
	Subject    string
	Summary    string
	OccurredAt string
	Level      string
	Status     string
}

// OpinionPostView is one opinion post (舆情信息) of the page: the
// server-minted post id, the source platform (微博/抖音/新闻媒体/论坛/其
// 他), the content, the sentiment (负面/中性/正面), the heat value and
// the warning state (未预警/已预警). The 未预警 posts of the demo data
// carry the inline warning form (its id becomes the {pid} path
// segment).
type OpinionPostView struct {
	ID         string
	Source     string
	Content    string
	Sentiment  string
	Heat       int
	WarnStatus string
}

// OpinionReleaseView is one opinion release (信息发布记录) of the page:
// the server-minted release id, the channel (官网公告/微信公众号/微博官
// 方号/新闻媒体通稿), the title, the content, the media name (媒体名称,
// '' for the official channels) and the publication state (草稿/待审
// 核/已发布/已撤回). Every release row carries the status-transition
// form (its id becomes the {lid} path segment).
type OpinionReleaseView struct {
	ID        string
	Channel   string
	Title     string
	Content   string
	MediaName string
	Status    string
}

// OpinionComplaintView is one complaint ticket (投诉处理记录) of the
// page: the server-minted complaint id, the complainant, the channel
// (现场/电话/网络留言/12345转办/其他), the complaint type (入馆受阻/参观
// 受限/服务态度/设施问题/其他), the content, the handling state (待受
// 理/处理中/已办结), the soothing-guidance measure (handling) and the
// handler (处理人). Every complaint row carries the accept/handle/close
// form (its id becomes the {cid} path segment).
type OpinionComplaintView struct {
	ID            string
	Complainant   string
	Channel       string
	ComplaintType string
	Content       string
	Status        string
	Handling      string
	Handler       string
}

// opinionTemplate is the parsed template collection of the
// public-opinion monitoring and handling workbench page (layout +
// opinion page). It lives in its own template set because the layout's
// content/title hooks are page-specific: every page defines its own
// content block, and one shared parse set would let the alphabetically
// last page win for every page (same pattern as the scenarios, drills,
// command and console pages).
var opinionTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/opinion.html"))

// RenderOpinion renders the public-opinion monitoring and handling
// workbench page (layout + opinion content) with the given in-memory
// display data. All user-controlled input is HTML-escaped by
// html/template.
func RenderOpinion(w io.Writer, data OpinionPageData) error {
	return opinionTemplate.ExecuteTemplate(w, "layout.html", data)
}
