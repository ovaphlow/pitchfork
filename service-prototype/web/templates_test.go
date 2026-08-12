package web

import (
	"strings"
	"testing"
)

// 模板集合解析成功（包初始化时即解析，这里显式验证可执行）。
func TestTemplatesParseAndRenderDemo(t *testing.T) {
	var output strings.Builder
	if err := RenderDemo(&output, "你好，世界"); err != nil {
		t.Fatalf("RenderDemo: %v", err)
	}
	rendered := output.String()
	if !strings.Contains(rendered, "Prototype Demo") {
		t.Fatalf("rendered page does not contain the demo heading")
	}
	if !strings.Contains(rendered, "你好，世界") {
		t.Fatalf("rendered page does not contain the greeting")
	}
	if !strings.Contains(rendered, `hx-get="/crate-api/prototype/v1/demo-fragment"`) {
		t.Fatalf("rendered page does not reference the htmx fragment resource")
	}
}

// 含 HTML 特殊字符的输入被正确转义（无注入）。
func TestTemplateEscapesHTMLInput(t *testing.T) {
	var output strings.Builder
	payload := `<script>alert("xss")</script>`
	if err := RenderDemo(&output, payload); err != nil {
		t.Fatalf("RenderDemo: %v", err)
	}
	rendered := output.String()
	if strings.Contains(rendered, "<script>alert") {
		t.Fatalf("rendered output contains unescaped HTML: %s", rendered)
	}
	if !strings.Contains(rendered, "&lt;script&gt;alert") {
		t.Fatalf("rendered output does not contain the escaped input: %s", rendered)
	}
}

// 片段渲染同样转义用户可控输入。
func TestTemplateEscapesFragmentMessage(t *testing.T) {
	var output strings.Builder
	if err := RenderDemoFragment(&output, `<img src=x onerror=alert(1)>`); err != nil {
		t.Fatalf("RenderDemoFragment: %v", err)
	}
	rendered := output.String()
	if strings.Contains(rendered, "<img") {
		t.Fatalf("fragment contains unescaped HTML: %s", rendered)
	}
	if !strings.Contains(rendered, "&lt;img") {
		t.Fatalf("fragment does not contain the escaped input: %s", rendered)
	}
}
