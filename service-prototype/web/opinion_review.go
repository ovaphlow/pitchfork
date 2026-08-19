package web

import (
	"html/template"
	"io"
)

// OpinionReviewPageData carries the display data of the media
// communication and after-action review page (媒体沟通与舆情复盘). The
// page renders purely from this in-memory payload — no database access,
// no API call — so the caller builds it from the built-in demo data and
// mints the server-side ids. The page is a write-oriented demo: the
// press-conference block (新闻发布会媒体问答) and the review block (舆情
// 复盘) carry forms whose hx actions anchor on the run id of the demo
// drill run, and the media-question demo rows carry their server-minted
// ids.
type OpinionReviewPageData struct {
	// RunID is the 26-character server-minted ULID of the demo drill
	// run. It anchors the hx-get refresh actions and the hx-post /
	// hx-put forms (the {rid} path segment); the page itself never
	// constructs ids.
	RunID     string
	Questions []MediaQuestionView
	Review    ReviewView
}

// MediaQuestionView is one media Q&A record (媒体问答记录) of the page:
// the server-minted question id, the media name, the reporter, the
// question, the question type (事实类/质疑类/尖锐类), the answer (empty
// until answered), the answering state (未回答/已回答) and the
// answered-at instant (empty until answered). Every question row carries
// the inline answer form (its id becomes the {mqid} path segment).
type MediaQuestionView struct {
	ID           string
	MediaName    string
	Reporter     string
	Question     string
	QuestionType string
	Answer       string
	Status       string
	AnsweredAt   string
}

// ReviewView is the after-action review report (舆情复盘记录) of the
// page: the five text sections 事件经过 (case_summary) / 处置亮点
// (highlights) / 存在问题 (problems) / 经验教训 (lessons) / 改进建议
// (suggestions), matching the opinion review contract field names word
// for word.
type ReviewView struct {
	CaseSummary string
	Highlights  string
	Problems    string
	Lessons     string
	Suggestions string
}

// opinionReviewTemplate is the parsed template collection of the media
// communication and after-action review page (layout + opinion review
// page). It lives in its own template set because the layout's
// content/title hooks are page-specific: every page defines its own
// content block, and one shared parse set would let the alphabetically
// last page win for every page (same pattern as the scenarios, drills,
// command, console and opinion pages).
var opinionReviewTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/opinion_review.html"))

// RenderOpinionReview renders the media communication and after-action
// review page (layout + opinion review content) with the given in-memory
// display data. All user-controlled input is HTML-escaped by
// html/template.
func RenderOpinionReview(w io.Writer, data OpinionReviewPageData) error {
	return opinionReviewTemplate.ExecuteTemplate(w, "layout.html", data)
}
