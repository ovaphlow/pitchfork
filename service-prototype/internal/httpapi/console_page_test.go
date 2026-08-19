package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
)

// ─── #54 指挥调度操作与现场终端页 ──────────────────────────────────

// consolePageULIDPattern is the 26-character Crockford Base32 ULID shape
// every server id rendered by the page must carry (the form-URL rid and
// oid plus the ids of the demo list rows). The page itself never
// constructs ids.
var consolePageULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// GET /demo/console 返回 200 且 Content-Type 为 text/html；页面含「指挥调
// 度操作与现场终端」标题与「指挥员操作」「现场终端」两大区块，内容由 handler
// 注入的内存演示数据渲染（会话配置/指令/部门联动/消息/热力/设备六类对象各至
// 少一条、dispatch 枚举中文值都在，空数据或错数据不得通过）。
func TestConsolePageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/console", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	body := recorder.Body.String()
	for _, text := range []string{
		"指挥调度操作与现场终端", "指挥员操作", "现场终端",
		"远程协同", "主馆一层大厅", "东区联训馆", "西区联训馆",
		"增开安检通道", "加强A区客流疏导", "特急", "执行中", "待接收",
		"消防", "场馆应急组", "已到位", "处置中",
		"指挥中心 · 总指挥", "现场人员 · 南门岗",
		"A区东侧展厅", "B区中央大厅", "C区南门通道",
		"一号供配电柜", "东区烟感探测器", "B区客流摄像头", "告警", "离线", "烟雾浓度超限",
	} {
		if !strings.Contains(body, text) {
			t.Fatalf("page body does not contain %q (demo data not injected)", text)
		}
	}
}

// 每次渲染的 ID 铸造行为：表单 URL 中的 rid 始终是数据带出的固定 26 位
// Crockford Base32 ULID（示例 run id，仿 drills 页 exampleRun*ID 模式）；
// oid（反馈表单 URL）与各列表行 id 由服务端铸造，连续两次渲染互不相同（验
// 证每次渲染服务端重新铸造）；页面出现的全部服务端 id 均匹配
// ^[0-9A-HJKMNP-TV-Z]{26}$（部门联动表单的 {department} 路径段为业务枚举
// 值，不参与该断言）；页面无任何 id 构造输入（无 name="id" 输入项）。
func TestConsolePageMintsFreshULIDPerRender(t *testing.T) {
	handler := testMux(nil)
	renders := make([][]string, 0, 2)
	for i := 0; i < 2; i++ {
		recorder := get(handler, "/demo/console", nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("render %d: status = %d, want 200", i, recorder.Code)
		}
		body := recorder.Body.String()

		// 表单 URL 中的 rid：全部为固定示例 ULID。
		ridSegment := regexp.MustCompile(`drills/([^/"']+)/`)
		ridMatches := ridSegment.FindAllStringSubmatch(body, -1)
		if len(ridMatches) == 0 {
			t.Fatalf("render %d: page body does not reference any run id", i)
		}
		for _, match := range ridMatches {
			if match[1] != exampleConsoleRunID {
				t.Fatalf("render %d: run id %q in a form URL, want the fixed %q", i, match[1], exampleConsoleRunID)
			}
			if !consolePageULIDPattern.MatchString(match[1]) {
				t.Fatalf("render %d: run id %q is not a 26-character Crockford Base32 ULID", i, match[1])
			}
		}

		// 反馈表单 URL 中的 oid：恰一个，为铸造的 ULID。
		oidSegment := regexp.MustCompile(`drills/` + exampleConsoleRunID + `/orders/([^/"']+)`)
		oidMatches := oidSegment.FindAllStringSubmatch(body, -1)
		if len(oidMatches) != 1 {
			t.Fatalf("render %d: feedback form must carry exactly one order id, got %d", i, len(oidMatches))
		}
		if !consolePageULIDPattern.MatchString(oidMatches[0][1]) {
			t.Fatalf("render %d: order id %q is not a 26-character Crockford Base32 ULID", i, oidMatches[0][1])
		}

		// 部门联动表单的 {department} 路径段为业务枚举值（消防），不参与 ULID 断言。
		departmentSegment := regexp.MustCompile(`drills/` + exampleConsoleRunID + `/departments/([^/"']+)`)
		departmentMatches := departmentSegment.FindAllStringSubmatch(body, -1)
		if len(departmentMatches) != 1 || departmentMatches[0][1] != string(dispatch.DepartmentFire) {
			t.Fatalf("render %d: department form must carry exactly one business enum segment, got %v", i, departmentMatches)
		}

		// 各列表行 id 均为 26 位 ULID。
		rowIDPattern := regexp.MustCompile(`class="(?:order|message|zone|device|department)-id">([^<]+)<`)
		rowMatches := rowIDPattern.FindAllStringSubmatch(body, -1)
		if len(rowMatches) == 0 {
			t.Fatalf("render %d: page body does not render any list-row id", i)
		}
		minted := make([]string, 0, len(rowMatches)+1)
		minted = append(minted, oidMatches[0][1])
		for _, match := range rowMatches {
			if !consolePageULIDPattern.MatchString(match[1]) {
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
	// 两次渲染铸造的 id（oid + 列表行 id）互不相同：每次渲染服务端重新铸造。
	if strings.Join(renders[0], ",") == strings.Join(renders[1], ",") {
		t.Fatalf("two renders minted the same id set %q, want fresh per render", renders[0])
	}
}

// 页面路由失败路径与既有机制一致：对 /demo/console 分别以 POST/PUT/DELETE
// 请求断言 405 且 Allow 含 GET、响应体为 { "error": ... } JSON；未知路径
// /demo/console/unknown 与 /demo/unknown 断言 404 JSON。
func TestConsolePageMethodNotAllowedAndUnknownPath(t *testing.T) {
	handler := testMux(nil)
	// 未知方法：POST/PUT/DELETE /demo/console → 405，Allow 含 GET，JSON 错误体。
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete} {
		req := httptest.NewRequest(method, "/demo/console", nil)
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
	// 未知路径：GET /demo/console/unknown 与 /demo/unknown → 404 JSON。
	for _, target := range []string{"/demo/console/unknown", "/demo/unknown"} {
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
