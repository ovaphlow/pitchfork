package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func get(handler http.Handler, target string, header map[string]string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodGet, target, nil)
	for key, value := range header {
		req.Header.Set(key, value)
	}
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, req)
	return recorder
}

// healthz 经统一路由模式 /crate-api/prototype/v1/{resource}（resource=healthz）返回 200 与 JSON。
func TestHealthzViaUnifiedRoute(t *testing.T) {
	recorder := get(NewMux(nil), "/crate-api/prototype/v1/healthz", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); contentType != "application/json" {
		t.Fatalf("Content-Type = %q, want application/json", contentType)
	}
	var payload map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil {
		t.Fatalf("body is not valid JSON: %v", err)
	}
	if payload["status"] != "ok" {
		t.Fatalf(`status field = %q, want "ok"`, payload["status"])
	}
}

// {resource} 动态段取值正确：未知资源走同一通配路由并返回 404 JSON。
func TestResourceDynamicSegment(t *testing.T) {
	recorder := get(NewMux(nil), "/crate-api/prototype/v1/subjects", nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404 for unknown resource", recorder.Code)
	}
	if !strings.Contains(recorder.Body.String(), `"error"`) {
		t.Fatalf("body %q is not a JSON error", recorder.Body.String())
	}
}

// 未知路径 404 且响应体为 { "error": ... } JSON。
func TestUnknownPathReturnsJSON404(t *testing.T) {
	for _, target := range []string{"/", "/unknown", "/crate-api/prototype/v1", "/crate-api/prototype/v1/healthz/extra"} {
		recorder := get(NewMux(nil), target, nil)
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

// 方法不匹配 405、含 Allow 头，响应体为 JSON 错误。
func TestMethodNotAllowedReturnsJSON405WithAllow(t *testing.T) {
	handler := NewMux(nil)
	for _, method := range []string{http.MethodPost, http.MethodPut, http.MethodDelete, http.MethodPatch} {
		req := httptest.NewRequest(method, "/crate-api/prototype/v1/healthz", nil)
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
}

func corsRequest(method, target, origin string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(method, target, nil)
	if origin != "" {
		req.Header.Set("Origin", origin)
	}
	recorder := httptest.NewRecorder()
	NewMux([]string{"https://allowed.example"}).ServeHTTP(recorder, req)
	return recorder
}

// 允许 Origin 的 OPTIONS 预检返回 204 且含 CORS 头。
func TestCORSPreflightAllowedOrigin(t *testing.T) {
	recorder := corsRequest(http.MethodOptions, "/crate-api/prototype/v1/healthz", "https://allowed.example")
	if recorder.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", recorder.Code)
	}
	header := recorder.Header()
	if header.Get("Access-Control-Allow-Origin") != "https://allowed.example" {
		t.Fatalf("ACAO = %q", header.Get("Access-Control-Allow-Origin"))
	}
	if header.Get("Access-Control-Allow-Methods") == "" || header.Get("Access-Control-Allow-Headers") == "" {
		t.Fatalf("preflight is missing Access-Control-Allow-Methods/Headers: %v", header)
	}
}

// 允许 Origin 的实际 GET 响应含 Access-Control-Allow-Origin。
func TestCORSActualRequestAllowedOrigin(t *testing.T) {
	recorder := corsRequest(http.MethodGet, "/crate-api/prototype/v1/healthz", "https://allowed.example")
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if recorder.Header().Get("Access-Control-Allow-Origin") != "https://allowed.example" {
		t.Fatalf("ACAO = %q", recorder.Header().Get("Access-Control-Allow-Origin"))
	}
}

// 不允许的 Origin：GET 与 OPTIONS 响应均不含任何 CORS 头。
func TestCORSDisallowedOriginHasNoHeaders(t *testing.T) {
	for _, method := range []string{http.MethodGet, http.MethodOptions} {
		recorder := corsRequest(method, "/crate-api/prototype/v1/healthz", "https://evil.example")
		header := recorder.Header()
		if header.Get("Access-Control-Allow-Origin") != "" ||
			header.Get("Access-Control-Allow-Methods") != "" ||
			header.Get("Access-Control-Allow-Headers") != "" {
			t.Fatalf("%s: disallowed origin must not get CORS headers, got %v", method, header)
		}
	}
}

// 无 Origin 的请求不受 CORS 逻辑影响：不附加 CORS 头；无 Origin 的 OPTIONS
// 按普通请求落回路由处理（未注册方法返回 405 且含 Allow）。
func TestCORSNoOriginBehavesAsPlainRequest(t *testing.T) {
	recorder := corsRequest(http.MethodGet, "/crate-api/prototype/v1/healthz", "")
	if recorder.Code != http.StatusOK {
		t.Fatalf("GET status = %d, want 200", recorder.Code)
	}
	if recorder.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatalf("no-Origin GET must not carry CORS headers")
	}
	recorder = corsRequest(http.MethodOptions, "/crate-api/prototype/v1/healthz", "")
	if recorder.Code != http.StatusMethodNotAllowed {
		t.Fatalf("no-Origin OPTIONS status = %d, want 405", recorder.Code)
	}
	if allow := recorder.Header().Get("Allow"); !strings.Contains(allow, "GET") {
		t.Fatalf("no-Origin OPTIONS Allow = %q, want it to contain GET", allow)
	}
	if recorder.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatalf("no-Origin OPTIONS must not carry CORS headers")
	}
}

// 空允许列表 = 任何 Origin 都不放行（等价于未配置）。
func TestCORSNoAllowedOriginsRejectsAll(t *testing.T) {
	recorder := get(NewMux(nil), "/crate-api/prototype/v1/healthz", map[string]string{"Origin": "https://any.example"})
	if recorder.Header().Get("Access-Control-Allow-Origin") != "" {
		t.Fatalf("empty allow list must not emit CORS headers")
	}
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200 (CORS must not block the request)", recorder.Code)
	}
}

// ─── #30 服务端渲染页面 / htmx 片段 / 静态资产 ─────────────────────────

// GET /demo 返回 200 且 Content-Type 为 text/html，内容为渲染后的页面。
func TestDemoPageServesHTML(t *testing.T) {
	recorder := get(NewMux(nil), "/demo", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	if !strings.Contains(recorder.Body.String(), "Prototype Demo") {
		t.Fatalf("page body does not contain the demo heading")
	}
}

// GET /crate-api/prototype/v1/demo-fragment 返回 200、text/html，响应体为 HTML 片段。
func TestDemoFragmentServesHTMLFragment(t *testing.T) {
	recorder := get(NewMux(nil), "/crate-api/prototype/v1/demo-fragment", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	body := recorder.Body.String()
	if !strings.Contains(body, "htmx") || !strings.HasPrefix(strings.TrimSpace(body), "<p") {
		t.Fatalf("body %q is not an HTML fragment", body)
	}
}

// 经 go:embed 提供的静态资产（htmx.min.js）GET 返回 200 且非空。
func TestStaticAssetServedViaEmbed(t *testing.T) {
	recorder := get(NewMux(nil), "/static/htmx.min.js", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/javascript") {
		t.Fatalf("Content-Type = %q, want text/javascript", contentType)
	}
	if recorder.Body.Len() == 0 {
		t.Fatal("htmx.min.js body is empty")
	}
}

// 缺失资产返回 404 JSON。
func TestStaticAssetMissingReturnsJSON404(t *testing.T) {
	recorder := get(NewMux(nil), "/static/does-not-exist.js", nil)
	if recorder.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", recorder.Code)
	}
	var payload map[string]string
	if err := json.Unmarshal(recorder.Body.Bytes(), &payload); err != nil || payload["error"] == "" {
		t.Fatalf("body %q is not a JSON error", recorder.Body.String())
	}
}
