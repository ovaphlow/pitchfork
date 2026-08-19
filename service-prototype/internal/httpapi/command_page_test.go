package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"regexp"
	"strings"
	"testing"
)

// ─── #54 指挥中心大屏页 ──────────────────────────────────────

// commandPageULIDPattern is the 26-character Crockford Base32 ULID shape
// every id rendered by the page must carry (the run id anchoring the
// hx-get refresh URLs). The page itself never constructs ids.
var commandPageULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// GET /demo/command 返回 200 且 Content-Type 为 text/html；页面含「指挥
// 中心大屏」标题、六大监控区块（会话配置、区域热力、设备状态、部门联动、
// 消息流、指令列表）与三维场馆地图静态示意区，内容由 handler 注入的内存
// 演示数据渲染（实训方式/设备三态/指令四态/部门状态都在，空数据或错数据
// 不得通过）。
func TestCommandPageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/command", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	body := recorder.Body.String()
	for _, text := range []string{
		"指挥中心大屏", "三维场馆地图", "会话配置", "区域热力", "设备状态", "部门联动", "消息流", "指令列表",
		"远程协同", "主馆一层大厅", "东区联训馆", "西区联训馆",
		"A区东侧展厅", "B区中央大厅", "C区南门通道",
		"正常", "告警", "离线", "烟雾浓度超限",
		"未响应", "已响应", "已到位", "处置中",
		"待接收", "已接收", "执行中", "已完成",
	} {
		if !strings.Contains(body, text) {
			t.Fatalf("page body does not contain %q (demo data not injected)", text)
		}
	}
}

// 每次渲染服务端铸造新的 26 位 Crockford Base32 ULID（hx-get 刷新动作的
// rid 锚点）：两次渲染的 rid 都符合正则且互不相同；页面出现的所有 id 均
// 为 26 位 ULID，且页面无任何 id 构造输入（无 name="id" 输入项）。
func TestCommandPageMintsFreshULIDPerRender(t *testing.T) {
	handler := testMux(nil)
	rids := make([]string, 0, 2)
	for i := 0; i < 2; i++ {
		recorder := get(handler, "/demo/command", nil)
		if recorder.Code != http.StatusOK {
			t.Fatalf("render %d: status = %d, want 200", i, recorder.Code)
		}
		body := recorder.Body.String()
		idSegment := regexp.MustCompile(`drills/([^/"']+)/`)
		matches := idSegment.FindAllStringSubmatch(body, -1)
		if len(matches) == 0 {
			t.Fatalf("render %d: page body does not reference any run id", i)
		}
		for _, match := range matches {
			if !commandPageULIDPattern.MatchString(match[1]) {
				t.Fatalf("render %d: run id %q is not a 26-character Crockford Base32 ULID", i, match[1])
			}
			rids = append(rids, match[1])
		}
		if strings.Contains(body, `name="id"`) {
			t.Fatalf("render %d: page must not construct ids (no name=\"id\" input)", i)
		}
	}
	// 同一 handler 的两次渲染铸造不同的 rid（每次渲染都铸造新 ULID）；同
	// 一次渲染内所有 hx-get 刷新动作共享同一个 rid 锚点。
	if rids[0] == rids[len(rids)/2] {
		t.Fatalf("two renders minted the same run id %q, want fresh per render", rids[0])
	}
}

// 页面路由失败路径与既有机制一致：未知方法 405（Allow 含 GET）、未知子
// 路径 404 且响应体为 { "error": ... } JSON。
func TestCommandPageMethodNotAllowedAndUnknownPath(t *testing.T) {
	handler := testMux(nil)
	// 未知方法：POST /demo/command → 405，Allow 含 GET。
	req := httptest.NewRequest(http.MethodPost, "/demo/command", nil)
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("POST: status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") {
		t.Fatalf("POST: Allow = %q, want it to contain GET", allow)
	}
	// 未知路径：GET /demo/command/extra → 404 JSON。
	recorder = get(handler, "/demo/command/extra", nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", contentType)
	}
	var payload map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil || payload["error"] == "" {
		t.Fatalf("body %q is not a JSON error", recorder.Body.String())
	}
}
