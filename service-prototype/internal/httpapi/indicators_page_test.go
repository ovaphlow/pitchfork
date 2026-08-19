package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

// ─── #63 评估指标配置页 ──────────────────────────────────────

// indicatorsPageULIDPattern is the 26-character Crockford Base32 ULID
// shape every server id rendered by the page must carry (the edit and
// delete form URLs). The page itself never constructs ids.
var indicatorsPageULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// indicatorSeedTitles is the 15 built-in evaluation indicator titles of
// evaluation.SeedData (and the seed migration 000023), one per seed
// row.
var indicatorSeedTitles = []string{
	"预警响应速度", "指挥调度响应速度", "力量到场速度",
	"处置流程规范性", "信息报告规范性",
	"部门协同效率", "信息共享效率",
	"观众疏散组织", "观众秩序维护", "观众伤亡防控",
	"文物转移保护", "文物损失防控",
	"舆情监测预警", "信息发布引导", "舆情处置效果",
}

// GET /demo/evaluation/indicators 返回 200 且 Content-Type 为
// text/html; charset=utf-8，页面内容由 handler 注入的真实
// evaluation.SeedData 渲染：15 项种子指标标题、6 维度分组都在，演示
// 标志分布精确（演示 8 / 非演示 7），空数据或错数据不得通过。
func TestIndicatorsPageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/evaluation/indicators", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "text/html; charset=utf-8" {
		t.Fatalf("Content-Type = %q, want text/html; charset=utf-8", contentType)
	}
	body := recorder.Body.String()
	for _, title := range indicatorSeedTitles {
		if !strings.Contains(body, title) {
			t.Fatalf("page body does not contain built-in indicator %q (SeedData not injected)", title)
		}
	}
	for _, dimension := range []string{"响应速度", "处置规范性", "协同效率", "观众安全", "文物安全", "舆情管控"} {
		if !strings.Contains(body, "<h3>"+dimension+"</h3>") {
			t.Fatalf("page body does not contain the dimension group %q", dimension)
		}
	}
	if got := strings.Count(body, `<span class="demo-badge">演示</span>`); got != 8 {
		t.Fatalf("demo badge count = %d, want 8 (演示 8 / 非演示 7)", got)
	}
	if strings.Contains(body, `name="id"`) {
		t.Fatalf("page must not construct ids (no name=\"id\" input)")
	}
}

// 维度筛选与 4.1 契约列表口径一致：dimension 参数仅渲染该维度分组
// （如 响应速度 → 3 项、其余分组不渲染），参数名与取值与契约一致；空参
// 数（?dimension= 或缺失）渲染全部 15 项；非法取值返回 400
// {"error":"invalid dimension"}。
func TestIndicatorsPageDimensionFilter(t *testing.T) {
	handler := testMux(nil)

	// 响应速度 → 该维度 3 项在、其余维度标题与分组都不在。
	recorder := get(handler, "/demo/evaluation/indicators?dimension=响应速度", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("filtered status = %d, want 200", recorder.Code)
	}
	body := recorder.Body.String()
	for _, title := range []string{"预警响应速度", "指挥调度响应速度", "力量到场速度"} {
		if !strings.Contains(body, title) {
			t.Fatalf("filtered page does not contain 响应速度 indicator %q", title)
		}
	}
	for _, absent := range []string{"处置流程规范性", "部门协同效率", "观众疏散组织", "文物转移保护", "舆情监测预警", "<h3>舆情管控</h3>", "<h3>观众安全</h3>"} {
		if strings.Contains(body, absent) {
			t.Fatalf("filtered page must not render %q (dimension filter broken)", absent)
		}
	}

	// 空参数（缺失与 ?dimension= 等价）渲染全部 15 项。
	for _, target := range []string{"/demo/evaluation/indicators?dimension=", "/demo/evaluation/indicators?dimension"} {
		recorder = get(handler, target, nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("%s: status = %d, want 200", target, recorder.Code)
		}
		body = recorder.Body.String()
		for _, title := range indicatorSeedTitles {
			if !strings.Contains(body, title) {
				t.Fatalf("%s: page does not render all indicators (missing %q)", target, title)
			}
		}
	}

	// 非法取值 → 400 {"error":"invalid dimension"}（与契约一致）。
	recorder = get(handler, "/demo/evaluation/indicators?dimension=不存在", nil)
	if recorder.Code != http.StatusBadRequest {
		t.Fatalf("invalid dimension status = %d, want 400", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
		t.Fatalf("invalid dimension Content-Type = %q, want application/json", contentType)
	}
	var payload map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil || payload["error"] != "invalid dimension" {
		t.Fatalf("invalid dimension body %q is not {error: invalid dimension}", recorder.Body.String())
	}
}

// 页面路由失败路径与既有机制一致：对 /demo/evaluation/indicators 分别以
// POST/PUT/DELETE 请求断言 405 且 Allow 含 GET、响应体为 { "error": ... }
// JSON；未知子路径 /demo/evaluation/indicators/unknown 与
// /demo/unknown 断言 404 JSON。
func TestIndicatorsPageMethodNotAllowedAndUnknownPath(t *testing.T) {
	handler := testMux(nil)
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/demo/evaluation/indicators", nil)
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
	for _, target := range []string{"/demo/evaluation/indicators/unknown", "/demo/unknown"} {
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

// 每次渲染的 ID 铸造行为：编辑/删除表单 URL 中的 id 全部为 26 位
// Crockford Base32 ULID（SeedIndicator 不携带 ID，由渲染 handler 服务端
// 铸造，仿 scenariosPageData 逐项铸造），连续两次渲染互不相同（每次渲染
// 重新铸造）；页面无任何 id 构造输入（无 name="id" 输入项）。
func TestIndicatorsPageMintsFreshULIDPerRender(t *testing.T) {
	handler := testMux(nil)
	renders := make([][]string, 0, 2)
	idSegment := regexp.MustCompile(`evaluation/indicators/([0-9A-HJKMNP-TV-Z]{26})`)
	for i := 0; i < 2; i++ {
		recorder := get(handler, "/demo/evaluation/indicators", nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("render %d: status = %d, want 200", i, recorder.Code)
		}
		body := recorder.Body.String()
		matches := idSegment.FindAllStringSubmatch(body, -1)
		// 15 项指标 × 2（编辑 + 删除表单）= 30 处 id。
		if len(matches) != 30 {
			t.Fatalf("render %d: id segment count = %d, want 30 (15 edit + 15 delete)", i, len(matches))
		}
		seen := make(map[string]bool, 15)
		for _, match := range matches {
			id := match[1]
			if !indicatorsPageULIDPattern.MatchString(id) {
				t.Fatalf("render %d: id %q is not a 26-character Crockford Base32 ULID", i, id)
			}
			seen[id] = true
		}
		if len(seen) != 15 {
			t.Fatalf("render %d: unique id count = %d, want 15", i, len(seen))
		}
		if strings.Contains(body, `name="id"`) {
			t.Fatalf("render %d: page must not construct ids (no name=\"id\" input)", i)
		}
		minted := make([]string, 0, 15)
		for id := range seen {
			minted = append(minted, id)
		}
		renders = append(renders, minted)
	}
	// 两次渲染铸造的 id 集合互不相同：每次渲染服务端重新铸造。
	if strings.Join(renders[0], ",") == strings.Join(renders[1], ",") {
		t.Fatalf("two renders minted the same id set %q, want fresh per render", renders[0])
	}
}
