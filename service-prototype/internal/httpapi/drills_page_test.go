package httpapi

import (
	"net/http"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// ─── #44 演练执行与考核页 ──────────────────────────────────────

// GET /demo/drills 返回 200 且 Content-Type 为 text/html，页面内容由
// handler 注入的示例任务 fixture 与真实 drills.SeedData 渲染（示例任务
// 标题、场景名与状态都在，空数据或错数据不得通过）。
func TestDrillsPageServesHTML(t *testing.T) {
	recorder := get(testMux(nil), "/demo/drills", nil)
	if recorder.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", recorder.Code)
	}
	if contentType := recorder.Header().Get("Content-Type"); !strings.HasPrefix(contentType, "text/html") {
		t.Fatalf("Content-Type = %q, want text/html", contentType)
	}
	body := recorder.Body.String()
	for _, text := range []string{exampleRun1Title, exampleRun1Scenario, exampleRun2Title, exampleRun2Scenario, string(drills.RunStatusInProgress)} {
		if !strings.Contains(body, text) {
			t.Fatalf("page body does not contain %q (fixture/SeedData not injected)", text)
		}
	}
}
