package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

// ─── #69 媒体沟通与舆情复盘页 ──────────────────────────────────

// opinionReviewPageULIDPattern is the 26-character Crockford Base32
// ULID shape every server id rendered by the page must carry (the
// form-URL rid and the ids of the demo question rows). The page itself
// never constructs ids.
var opinionReviewPageULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// GET /demo/opinion/review 返回 200 且 Content-Type 为 text/html；页面含
// 「媒体沟通与舆情复盘」标题与两区块（新闻发布会媒体问答/舆情复盘），内容由
// handler 注入的内存演示数据渲染（媒体问答 3 条 问题类型覆盖 事实类/质疑类/
// 尖锐类、回答状态覆盖 未回答/已回答 且已回答条目含 answer 与 answered_at、
// 媒体/记者/问题文本都在；复盘 1 条五段 事件经过/处置亮点/存在问题/经验教训/
// 改进建议 均有内容；opinion 契约中文枚举值都在，空数据或错数据不得通过）。
func TestOpinionReviewPageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/opinion/review", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	body := recorder.Body.String()
	for _, text := range []string{
		"媒体沟通与舆情复盘", "新闻发布会媒体问答", "舆情复盘",
		"本市日报", "市电视台", "网络自媒体", "张敏", "李健", "赵倩",
		"请问今天南门入馆排队时间大概多久？",
		"有游客反映预约系统放票后几分钟就约满，是否存在内部放票？",
		"为什么不在昨天高温预警时就提前限流？这是不是管理失职？",
		"事实类", "质疑类", "尖锐类", "未回答", "已回答",
		"截至上午11时，南门平均排队约40分钟，馆方已增开两条入馆通道并增设遮阳设施。",
		"预约票源全部面向公众公开发放，馆方未预留任何内部名额，放票数据可公开核查。",
		"2026-08-03T10:45", "2026-08-03T11:05",
		"事件经过", "处置亮点", "存在问题", "经验教训", "改进建议",
		"8月3日暑期参观高峰叠加高温天气",
		"气象预警与客流预警应联动触发，提前启动分流措施",
		"建立高温天气自动限流预案并纳入演练",
	} {
		if !strings.Contains(body, text) {
			t.Fatalf("page body does not contain %q (demo data not injected)", text)
		}
	}
}

// 每次渲染的 ID 铸造行为：rid 锚点与媒体问答行 id 均由服务端铸造——同一渲染
// 内所有动作共享同一 rid 锚点且全部匹配 ^[0-9A-HJKMNP-TV-Z]{26}$；行内撰
// 写回答表单的 mqid 与列表行展示 id 均为 26 位 ULID；连续两次渲染铸造的 id
// 集合互不相同（验证每次渲染服务端重新铸造）；页面无任何 id 构造输入（无
// name="id" 输入项）。
func TestOpinionReviewPageMintsFreshULIDPerRender(t *testing.T) {
	handler := testMux(nil)
	renders := make([][]string, 0, 2)
	for i := 0; i < 2; i++ {
		recorder := get(handler, "/demo/opinion/review", nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("render %d: status = %d, want 200", i, recorder.Code)
		}
		body := recorder.Body.String()

		// 表单 URL 中的 rid：同一渲染内全部相同且为 26 位 ULID。
		ridSegment := regexp.MustCompile(`drills/([^/"']+)/`)
		ridMatches := ridSegment.FindAllStringSubmatch(body, -1)
		if len(ridMatches) == 0 {
			t.Fatalf("render %d: page body does not reference any run id", i)
		}
		runID := ridMatches[0][1]
		if !opinionReviewPageULIDPattern.MatchString(runID) {
			t.Fatalf("render %d: run id %q is not a 26-character Crockford Base32 ULID", i, runID)
		}
		for _, match := range ridMatches[1:] {
			if match[1] != runID {
				t.Fatalf("render %d: run id %q in a form URL, want the shared anchor %q", i, match[1], runID)
			}
		}

		// 行内撰写回答表单 URL 中的 mqid：全部为 26 位 ULID。
		mqidPattern := regexp.MustCompile(`drills/` + runID + `/media-questions/([^/"']+)`)
		mqidMatches := mqidPattern.FindAllStringSubmatch(body, -1)
		if len(mqidMatches) == 0 {
			t.Fatalf("render %d: page body does not carry any /drills/%s/media-questions/{mqid} form URL", i, runID)
		}
		for _, match := range mqidMatches {
			if !opinionReviewPageULIDPattern.MatchString(match[1]) {
				t.Fatalf("render %d: media question id %q is not a 26-character Crockford Base32 ULID", i, match[1])
			}
		}

		// 列表行展示 id（媒体问答编号）均为 26 位 ULID。
		rowIDPattern := regexp.MustCompile(`class="question-id">([^<]+)<`)
		rowMatches := rowIDPattern.FindAllStringSubmatch(body, -1)
		if len(rowMatches) == 0 {
			t.Fatalf("render %d: page body does not render any list-row id", i)
		}
		minted := make([]string, 0, len(rowMatches)+1)
		minted = append(minted, runID)
		for _, match := range rowMatches {
			if !opinionReviewPageULIDPattern.MatchString(match[1]) {
				t.Fatalf("render %d: list-row id %q is not a 26-character Crockford Base32 ULID", i, match[1])
			}
			minted = append(minted, match[1])
		}

		// 页面不构造 ID：没有任何 id 输入项。
		if strings.Contains(body, `name="id"`) {
			t.Fatalf("render %d: page must not construct ids (no name=\"id\" input)", i)
		}

		renders = append(renders, minted)
	}
	// 两次渲染铸造的 id（rid + 列表行 id）互不相同：每次渲染服务端重新铸造。
	if strings.Join(renders[0], ",") == strings.Join(renders[1], ",") {
		t.Fatalf("two renders minted the same id set %q, want fresh per render", renders[0])
	}
}

// 页面路由失败路径与既有机制一致：对 /demo/opinion/review 分别以 POST/PUT/
// DELETE 请求断言 405 且 Allow 含 GET、响应体为 { "error": ... } JSON；未
// 知子路径 /demo/opinion/review/unknown 与 /demo/unknown 断言 404 JSON。
func TestOpinionReviewPageMethodNotAllowedAndUnknownPath(t *testing.T) {
	handler := testMux(nil)
	// 未知方法：POST/PUT/DELETE /demo/opinion/review → 405，Allow 含 GET，
	// JSON 错误体。
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/demo/opinion/review", nil)
		recorder := httptest.NewRecorder()
		handler.ServeHTTP(recorder, req)
		if recorder.Code != http.StatusMethodNotAllowed {
			t.Fatalf("%s: status = %d, want 405", method, recorder.Code)
		}
		if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") {
			t.Fatalf("%s: Allow = %q, want it to contain GET", method, allow)
		}
		if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
			t.Fatalf("%s: Content-Type = %q, want application/json", method, contentType)
		}
		var payload map[string]string
		if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil || payload["error"] == "" {
			t.Fatalf("%s: body %q is not a JSON error", method, recorder.Body.String())
		}
	}
	// 未知路径：GET /demo/opinion/review/unknown 与 /demo/unknown → 404
	// JSON。
	for _, target := range []string{"/demo/opinion/review/unknown", "/demo/unknown"} {
		recorder := get(handler, target, nil)
		if recorder.Code != http.StatusNotFound {
			t.Fatalf("%s: status = %d, want 404", target, recorder.Code)
		}
		if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
			t.Fatalf("%s: Content-Type = %q, want application/json", target, contentType)
		}
		var payload map[string]string
		if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil || payload["error"] == "" {
			t.Fatalf("%s: body %q is not a JSON error", target, recorder.Body.String())
		}
	}
}

// 回归：新增路由不影响既有页面与 API 路由——全部既有页面
// （/demo、/demo/scenarios、/demo/drills、/demo/command、/demo/console、
// /demo/opinion、/demo/evaluation/indicators、/demo/evaluation/reports）
// 与本卡新增页面 /demo/opinion/review 均返回 200 且 Content-Type 为
// text/html；API 健康路由 /crate-api/prototype/v1/healthz 仍返回 200
// JSON。
func TestOpinionReviewPageDoesNotRegressExistingPages(t *testing.T) {
	handler := testMux(nil)
	for _, target := range []string{
		"/demo", "/demo/scenarios", "/demo/drills", "/demo/command", "/demo/console",
		"/demo/opinion", "/demo/opinion/review",
		"/demo/evaluation/indicators", "/demo/evaluation/reports",
	} {
		recorder := get(handler, target, nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200", target, recorder.Code)
		}
		if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
			t.Fatalf("%s: Content-Type = %q, want text/html", target, contentType)
		}
	}
	// API 路由不回归：healthz 仍为 200 JSON。
	recorder := get(handler, "/crate-api/prototype/v1/healthz", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("healthz: status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
		t.Fatalf("healthz: Content-Type = %q, want application/json", contentType)
	}
}
