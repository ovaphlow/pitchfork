package web

import (
	"regexp"
	"strings"
	"testing"
)

// ─── #54 指挥中心大屏页 ──────────────────────────────────────

// commandULIDPattern is the 26-character Crockford Base32 ULID shape of
// every id the page renders (the run id anchoring the hx-get refresh
// URLs). The page itself never constructs ids.
var commandULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// commandFixture builds the display payload of the command-center big
// screen: content identical to the httpapi demo data, with the demo
// drill run carrying a fixed 26-character server-side ULID so the
// hx-get refresh URLs can be pinned to the id carried by the data (the
// page never constructs ids). The fixture covers one dispatch session
// (实训方式/主场馆/联训场馆), three zone-density reports (low/medium/high
// heat levels), three device statuses (正常/告警/离线), four department
// linkage reports, two dispatch messages (发送方 + 内容) and four
// dispatch orders (one per order status, with the feedback trail).
func commandFixture() CommandPageData {
	return CommandPageData{
		RunID: "06G00JAJ2197VBH6390A1BX79C",
		Session: SessionView{
			Mode:        "远程协同",
			MainVenue:   "主馆一层大厅",
			JointVenues: []string{"东区联训馆", "西区联训馆"},
		},
		Zones: []ZoneView{
			{ZoneName: "A区东侧展厅", PeopleCount: 180},
			{ZoneName: "B区中央大厅", PeopleCount: 520},
			{ZoneName: "C区南门通道", PeopleCount: 960},
		},
		Devices: []DeviceView{
			{DeviceName: "一号供配电柜", DeviceType: "供配电", Status: "正常"},
			{DeviceName: "东区烟感探测器", DeviceType: "消防", Status: "告警", Note: "烟雾浓度超限"},
			{DeviceName: "B区客流摄像头", DeviceType: "安防", Status: "离线"},
		},
		Departments: []DepartmentView{
			{Department: "安保部", Status: "已到位", Note: "南门通道已增派 6 人"},
			{Department: "消防组", Status: "处置中"},
			{Department: "客服部", Status: "已响应"},
			{Department: "设备部", Status: "未响应"},
		},
		Messages: []MessageView{
			{SenderType: "指挥中心", SenderName: "总指挥", Content: "各点位注意，A区客流已接近阈值，请加强疏导"},
			{SenderType: "现场人员", SenderName: "南门岗", Content: "收到，已增开一条安检通道"},
		},
		Orders: []OrderView{
			{Title: "加强A区客流疏导", Content: "A区东侧展厅人流密度持续上升，请立即加强现场疏导", Priority: "普通", TargetType: "部门", TargetName: "安保部", Status: "待接收"},
			{Title: "启动应急广播", Content: "通过馆内广播发布限流提示", Priority: "紧急", TargetType: "部门", TargetName: "客服部", Status: "已接收", Feedback: "已收到指令，正在准备广播内容"},
			{Title: "增开安检通道", Content: "南门增开一条安检通道，缓解排队", Priority: "普通", TargetType: "小组", TargetName: "安保二组", Status: "执行中", Feedback: "南门已增开一条安检通道"},
			{Title: "排查东区烟感报警", Content: "立即排查东区展厅烟感探测器告警原因", Priority: "特急", TargetType: "个人", TargetName: "消防值班员", Status: "已完成", Feedback: "已排查完毕，确认为装修粉尘触发"},
		},
	}
}

// 指挥中心大屏页渲染成功且包含关键内容：页面标题「指挥中心大屏」、六大监
// 控区块标题与三维场馆地图静态示意区；会话配置字段（实训方式/主场馆/联训
// 场馆）、热力区域、设备三态（正常/告警/离线）、部门状态（未响应/已响应/
// 已到位/处置中）、消息发送方与内容、指令四态（待接收/已接收/执行中/已完
// 成）与指令反馈字段各至少出现一次（与 dispatch 契约中文值一致）。
func TestRenderCommandPageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderCommand(&output, commandFixture()); err != nil {
		t.Fatalf("RenderCommand: %v", err)
	}
	rendered := output.String()
	// 页面标题、六大监控区块与三维场馆地图静态示意区。
	for _, text := range []string{"指挥中心大屏", "三维场馆地图", "会话配置", "区域热力", "设备状态", "部门联动", "消息流", "指令列表", "主场馆三维沙盘示意"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 会话配置字段（实训方式/主场馆/联训场馆）与值。
	for _, text := range []string{"实训方式", "主场馆", "联训场馆", "远程协同", "主馆一层大厅", "东区联训馆", "西区联训馆"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the session field %q", text)
		}
	}
	// 热力区域与设备三态（正常/告警/离线）。
	for _, text := range []string{"A区东侧展厅", "B区中央大厅", "C区南门通道", "一号供配电柜", "东区烟感探测器", "B区客流摄像头", "正常", "告警", "离线", "烟雾浓度超限"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 部门联动状态（未响应/已响应/已到位/处置中）与部门名。
	for _, text := range []string{"安保部", "消防组", "客服部", "设备部", "未响应", "已响应", "已到位", "处置中"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the department %q", text)
		}
	}
	// 消息发送方与内容。
	for _, text := range []string{"指挥中心 · 总指挥", "现场人员 · 南门岗", "各点位注意，A区客流已接近阈值，请加强疏导", "收到，已增开一条安检通道"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the message %q", text)
		}
	}
	// 指令四态（待接收/已接收/执行中/已完成）与反馈字段。
	for _, text := range []string{"加强A区客流疏导", "启动应急广播", "增开安检通道", "排查东区烟感报警", "待接收", "已接收", "执行中", "已完成", "已收到指令，正在准备广播内容", "南门已增开一条安检通道", "已排查完毕，确认为装修粉尘触发"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the order %q", text)
		}
	}
}

// 热力分级与设备样式：按 people_count 分档渲染不同色块 class（<300 低
// zone-low、300–800 中 zone-medium、>800 高 zone-high，阈值与 class 固定
// 在模板中），演示数据每档至少一区；设备告警/离线带醒目样式 class
// （device-warning/device-offline），正常设备为 device-normal。
func TestRenderCommandZoneLevelsAndDeviceStyles(t *testing.T) {
	var output strings.Builder
	if err := RenderCommand(&output, commandFixture()); err != nil {
		t.Fatalf("RenderCommand: %v", err)
	}
	rendered := output.String()
	// 三档热力 class 与档位文案、人数都出现。
	for _, text := range []string{"zone-low", "zone-medium", "zone-high", ">低<", ">中<", ">高<", ">180<", ">520<", ">960<"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the heat level marker %q", text)
		}
	}
	// 分档归属正确：每个区域的名字与其档位 class 出现在同一个 <li> 色块中。
	cellPattern := regexp.MustCompile(`<li class="zone-cell ([^"]+)">(?s)(.*?)</li>`)
	namePattern := regexp.MustCompile(`zone-name">([^<]+)</span>`)
	zoneClass := make(map[string]string)
	for _, cell := range cellPattern.FindAllStringSubmatch(rendered, -1) {
		name := namePattern.FindStringSubmatch(cell[2])
		if len(name) != 2 {
			t.Fatalf("zone cell %q does not carry a zone name", cell[0])
		}
		zoneClass[name[1]] = cell[1]
	}
	for name, want := range map[string]string{
		"A区东侧展厅": "zone-low",
		"B区中央大厅": "zone-medium",
		"C区南门通道": "zone-high",
	} {
		if got := zoneClass[name]; got != want {
			t.Fatalf("zone %q class = %q, want %q", name, got, want)
		}
	}
	// 设备三态标记 class：告警/离线醒目样式、正常设备常规样式。
	for _, text := range []string{"device-warning", "device-offline", "device-normal"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the device style class %q", text)
		}
	}
}

// htmx 刷新动作：消息流/热力/设备区块的 hx-get 指向完整 API 端点
// /crate-api/prototype/v1/drills/{rid}/messages、…/zone-densities、
// …/devices（rid 为数据带出的固定 26 位 ULID，页面不构造 id）；只读页
// 面没有写方法动作（无 hx-post/hx-put/hx-delete）。
func TestRenderCommandHtmxRefreshActions(t *testing.T) {
	var output strings.Builder
	if err := RenderCommand(&output, commandFixture()); err != nil {
		t.Fatalf("RenderCommand: %v", err)
	}
	rendered := output.String()
	runID := "06G00JAJ2197VBH6390A1BX79C"
	for _, resource := range []string{"messages", "zone-densities", "devices"} {
		target := `/crate-api/prototype/v1/drills/` + runID + `/` + resource
		if !strings.Contains(rendered, `hx-get="`+target+`"`) {
			t.Fatalf("refresh action does not target GET %s", target)
		}
	}
	// 方法为 GET：hx-get 是 GET 动词，页面不含任何写方法动作。
	for _, verb := range []string{"hx-post=", "hx-put=", "hx-delete=", "hx-patch="} {
		if strings.Contains(rendered, verb) {
			t.Fatalf("read-only page must not carry the write action %q", verb)
		}
	}
}

// 跨切面：页面出现的所有 id（hx-get 刷新 URL 中的 rid）均为 26 位
// Crockford Base32 ULID；页面无任何 id 构造输入（无 name="id" 输入项）。
func TestRenderCommandIDsAreULIDsAndNoIDInput(t *testing.T) {
	var output strings.Builder
	if err := RenderCommand(&output, commandFixture()); err != nil {
		t.Fatalf("RenderCommand: %v", err)
	}
	rendered := output.String()
	idSegment := regexp.MustCompile(`drills/([^/"']+)/`)
	matches := idSegment.FindAllStringSubmatch(rendered, -1)
	if len(matches) == 0 {
		t.Fatalf("rendered page does not reference any run id")
	}
	for _, match := range matches {
		if !commandULIDPattern.MatchString(match[1]) {
			t.Fatalf("run id %q is not a 26-character Crockford Base32 ULID", match[1])
		}
	}
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("page must not construct ids (no name=\"id\" input)")
	}
}
