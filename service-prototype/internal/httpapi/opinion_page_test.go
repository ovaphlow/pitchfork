package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

// ─── #69 舆情监测与处置工作台页 ──────────────────────────────────

// opinionPageULIDPattern is the 26-character Crockford Base32 ULID shape
// every server id rendered by the page must carry (the form-URL rid and
// the ids of the demo list rows). The page itself never constructs ids.
var opinionPageULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// GET /demo/opinion 返回 200 且 Content-Type 为 text/html；页面含「舆情应
// 对实训」标题与四大区块（舆情事件背景/舆情信息流/信息发布/投诉处理），内容
// 由 handler 注入的内存演示数据渲染（事件 1 条高热/已预警、舆情信息 ≥3 条来
// 源覆盖 微博/抖音/新闻媒体、情感覆盖 负面/中性/正面、预警状态覆盖 未预警/已
// 预警、发布 ≥2 条 官网公告 已发布 + 新闻媒体通稿 待审核 含媒体名称、投诉 ≥2
// 条 现场 待受理 + 网络留言 处理中；opinion 契约中文枚举值都在，空数据或错数
// 据不得通过）。
func TestOpinionPageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/opinion", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	body := recorder.Body.String()
	for _, text := range []string{
		"舆情应对实训", "舆情事件背景", "舆情信息流", "信息发布", "投诉处理",
		"高温大客流引发入馆受阻舆情", "市博物馆", "高热", "已预警",
		"微博", "抖音", "新闻媒体", "负面", "中性", "正面", "未预警",
		"官网公告", "新闻媒体通稿", "已发布", "待审核", "本市日报",
		"现场", "网络留言", "待受理", "处理中", "王女士", "李先生",
	} {
		if !strings.Contains(body, text) {
			t.Fatalf("page body does not contain %q (demo data not injected)", text)
		}
	}
}

// 每次渲染的 ID 铸造行为：rid 锚点与事件/舆情行/发布行/投诉行 id 均由服务端
// 铸造——同一渲染内所有动作共享同一 rid 锚点且全部匹配
// ^[0-9A-HJKMNP-TV-Z]{26}$；连续两次渲染铸造的 id 集合互不相同（验证每次
// 渲染服务端重新铸造）；页面无任何 id 构造输入（无 name="id" 输入项）。
func TestOpinionPageMintsFreshULIDPerRender(t *testing.T) {
	handler := testMux(nil)
	renders := make([][]string, 0, 2)
	for i := 0; i < 2; i++ {
		recorder := get(handler, "/demo/opinion", nil)
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
		if !opinionPageULIDPattern.MatchString(runID) {
			t.Fatalf("render %d: run id %q is not a 26-character Crockford Base32 ULID", i, runID)
		}
		for _, match := range ridMatches[1:] {
			if match[1] != runID {
				t.Fatalf("render %d: run id %q in a form URL, want the shared anchor %q", i, match[1], runID)
			}
		}

		// 行内表单 URL 中的 pid/lid/cid：全部为 26 位 ULID。
		rowSegments := []string{"posts", "releases", "complaints"}
		for _, segment := range rowSegments {
			rowPattern := regexp.MustCompile(`drills/` + runID + `/` + segment + `/([^/"']+)`)
			matches := rowPattern.FindAllStringSubmatch(body, -1)
			if len(matches) == 0 {
				t.Fatalf("render %d: page body does not carry any /drills/%s/%s/{id} form URL", i, runID, segment)
			}
			for _, match := range matches {
				if !opinionPageULIDPattern.MatchString(match[1]) {
					t.Fatalf("render %d: %s row id %q is not a 26-character Crockford Base32 ULID", i, segment, match[1])
				}
			}
		}

		// 各列表行 id（事件/舆情/发布/投诉编号）均为 26 位 ULID。
		rowIDPattern := regexp.MustCompile(`class="(?:event|post|release|complaint)-id">([^<]+)<`)
		rowMatches := rowIDPattern.FindAllStringSubmatch(body, -1)
		if len(rowMatches) == 0 {
			t.Fatalf("render %d: page body does not render any list-row id", i)
		}
		minted := make([]string, 0, len(rowMatches)+1)
		minted = append(minted, runID)
		for _, match := range rowMatches {
			if !opinionPageULIDPattern.MatchString(match[1]) {
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

// 页面路由失败路径与既有机制一致：对 /demo/opinion 分别以 POST/PUT/DELETE
// 请求断言 405 且 Allow 含 GET、响应体为 { "error": ... } JSON；未知路径
// /demo/opinion/unknown 与 /demo/unknown 断言 404 JSON。
func TestOpinionPageMethodNotAllowedAndUnknownPath(t *testing.T) {
	handler := testMux(nil)
	// 未知方法：POST/PUT/DELETE /demo/opinion → 405，Allow 含 GET，JSON 错误体。
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/demo/opinion", nil)
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
	// 未知路径：GET /demo/opinion/unknown 与 /demo/unknown → 404 JSON。
	for _, target := range []string{"/demo/opinion/unknown", "/demo/unknown"} {
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
