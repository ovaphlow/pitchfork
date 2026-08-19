package web

import (
	"regexp"
	"strings"
	"testing"
)

// ─── #54 指挥调度操作与现场终端页 ──────────────────────────────────

// consoleULIDPattern is the 26-character Crockford Base32 ULID shape of
// every server id the page renders (the run id anchoring the form URLs,
// the feedback form's order id and the ids of the demo list rows). The
// page itself never constructs ids.
var consoleULIDPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// consoleFixture builds the display payload of the command console and
// field terminal page: content identical to the httpapi demo data, with
// every id carried by the data being a fixed 26-character server-side
// ULID (the run id and the ids of the order/message/zone/device/
// department rows) so the form actions can be pinned to the ids carried
// by the data (the page never constructs ids). The fixture covers one
// dispatch session (实训方式/主场馆/联训场馆), two dispatch orders (the
// 执行中 one anchoring the feedback form), two department linkage
// reports (消防 first, anchoring the linkage form), two dispatch
// messages (发送方 + 内容), three zone-density reports and three device
// reports covering 正常/告警/离线 — each of the six object kinds at
// least one entry, matching the dispatch contract Chinese values.
func consoleFixture() ConsolePageData {
	return ConsolePageData{
		RunID: "06G00NC5ZWA3K5G194PSBJ8WNR",
		Session: ConsoleSessionView{
			Mode:        "远程协同",
			MainVenue:   "主馆一层大厅",
			JointVenues: []string{"东区联训馆", "西区联训馆"},
		},
		Orders: []ConsoleOrderView{
			{
				ID:         "06G00NC5ZYM6TMDVQ0CTC4GF5C",
				Title:      "增开安检通道",
				Content:    "南门增开一条安检通道，缓解排队",
				Priority:   "普通",
				TargetType: "部门",
				TargetName: "安保部",
				Deadline:   "2026-08-03T14:30",
				Status:     "执行中",
			},
			{
				ID:         "06G00NC5ZYEDN6WR5KJN1PAZMC",
				Title:      "加强A区客流疏导",
				Content:    "A区东侧展厅人流密度持续上升，请立即加强现场疏导",
				Priority:   "特急",
				TargetType: "小组",
				TargetName: "安保二组",
				Deadline:   "2026-08-03T15:00",
				Status:     "待接收",
			},
		},
		Departments: []ConsoleDepartmentView{
			{ID: "06G00NC5ZY1DQAT05XBA8Z3BTG", Department: "消防", Status: "已到位", Note: "消防增援分队已就位"},
			{ID: "06G00NC5ZZQV618KTJKCZMZX04", Department: "场馆应急组", Status: "处置中"},
		},
		Messages: []ConsoleMessageView{
			{ID: "06G00NC5ZX8Y28F5Y7YB4G73ZC", SenderType: "指挥中心", SenderName: "总指挥", Content: "各点位注意，A区客流已接近阈值，请加强疏导"},
			{ID: "06G00NC5ZZR0WAXSPGM9NEWXRC", SenderType: "现场人员", SenderName: "南门岗", Content: "收到，已增开一条安检通道"},
		},
		Zones: []ConsoleZoneView{
			{ID: "06G00NC5ZYZQRCZQJ7N8P5QEW0", ZoneName: "A区东侧展厅", PeopleCount: 180},
			{ID: "06G00NC5ZZBD8RTV93PJY13Z1M", ZoneName: "B区中央大厅", PeopleCount: 520},
			{ID: "06G00NC5ZZKHNXYT0VMRF3D0A8", ZoneName: "C区南门通道", PeopleCount: 960},
		},
		Devices: []ConsoleDeviceView{
			{ID: "06G00NC5ZWRVGK6SM56MPCZ618", DeviceName: "一号供配电柜", DeviceType: "供配电", Status: "正常"},
			{ID: "06G00NC5ZWSC5ANKA9RENE0K10", DeviceName: "东区烟感探测器", DeviceType: "消防", Status: "告警", Note: "烟雾浓度超限"},
			{ID: "06G00NC5ZZ2XVAT1X7QH6F6RGW", DeviceName: "B区客流摄像头", DeviceType: "安防", Status: "离线"},
		},
	}
}

// 指挥调度操作与现场终端页渲染成功且包含关键内容：页面标题与「指挥员操作」
// 「现场终端」两大区块；六类演示对象各至少一条——会话配置字段（实训方式/主
// 场馆/联训场馆）、指令（标题/内容/优先级/接收方/截止时间/状态）、部门联动
// （消防/场馆应急组与状态）、消息（发送方 + 内容）、热力区域与人数、设备三
// 态（正常/告警/离线）与告警备注（与 dispatch 契约中文值一致）。
func TestRenderConsolePageContent(t *testing.T) {
	var output strings.Builder
	if err := RenderConsole(&output, consoleFixture()); err != nil {
		t.Fatalf("RenderConsole: %v", err)
	}
	rendered := output.String()
	// 页面标题与两大区块标题。
	for _, text := range []string{"指挥调度操作与现场终端", "指挥员操作", "现场终端"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain %q", text)
		}
	}
	// 会话配置字段与值（实训方式/主场馆/联训场馆）。
	for _, text := range []string{"实训方式", "主场馆", "联训场馆", "远程协同", "主馆一层大厅", "东区联训馆", "西区联训馆"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the session field %q", text)
		}
	}
	// 指令演示数据：标题/内容/优先级/接收方/截止时间/状态。
	for _, text := range []string{"增开安检通道", "加强A区客流疏导", "南门增开一条安检通道，缓解排队", "A区东侧展厅人流密度持续上升，请立即加强现场疏导", "特急", "部门 · 安保部", "小组 · 安保二组", "2026-08-03T14:30", "2026-08-03T15:00", "执行中", "待接收"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the order %q", text)
		}
	}
	// 部门联动演示数据：部门名与状态。
	for _, text := range []string{"消防", "场馆应急组", "已到位", "处置中", "消防增援分队已就位"} {
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
	// 热力区域与人数。
	for _, text := range []string{"A区东侧展厅", "B区中央大厅", "C区南门通道", ">180<", ">520<", ">960<"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the zone %q", text)
		}
	}
	// 设备三态与告警备注。
	for _, text := range []string{"一号供配电柜", "东区烟感探测器", "B区客流摄像头", "正常", "告警", "离线", "烟雾浓度超限"} {
		if !strings.Contains(rendered, text) {
			t.Fatalf("rendered page does not contain the device %q", text)
		}
	}
}

// 指挥员区块表单动作与第六节 API 契约一一对应，hx URL 钉死完整前缀：会话
// 配置 → hx-put /crate-api/prototype/v1/drills/{rid}/command-session（字
// 段 mode/main_venue/joint_venues）；下达指令 → hx-post
// /crate-api/prototype/v1/drills/{rid}/orders（字段 title/content/
// priority/target_type/target_name/deadline）；部门联动 → hx-put
// /crate-api/prototype/v1/drills/{rid}/departments/{department}（department
// 为演示数据带出的业务枚举值，字段 status/note）。rid 为数据带出的固定 26
// 位 ULID，页面不构造 id。
func TestRenderConsoleCommanderForms(t *testing.T) {
	var output strings.Builder
	if err := RenderConsole(&output, consoleFixture()); err != nil {
		t.Fatalf("RenderConsole: %v", err)
	}
	rendered := output.String()
	runID := "06G00NC5ZWA3K5G194PSBJ8WNR"

	// 会话配置 → PUT /drills/{rid}/command-session，字段 mode/main_venue/joint_venues。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/command-session"`) {
		t.Fatalf("session form does not target PUT /drills/%s/command-session", runID)
	}
	for _, field := range []string{"mode", "main_venue", "joint_venues"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("session form does not carry the %s field", field)
		}
	}
	// 下达指令 → POST /drills/{rid}/orders，字段 title/content/priority/
	// target_type/target_name/deadline。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/orders"`) {
		t.Fatalf("order create form does not target POST /drills/%s/orders", runID)
	}
	for _, field := range []string{"title", "content", "priority", "target_type", "target_name", "deadline"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("order create form does not carry the %s field", field)
		}
	}
	// 部门联动 → PUT /drills/{rid}/departments/{department}，department 为
	// 演示数据第一个部门的业务枚举值（消防），字段 status/note。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/departments/消防"`) {
		t.Fatalf("department form does not target PUT /drills/%s/departments/消防", runID)
	}
	if !strings.Contains(rendered, `name="status"`) || !strings.Contains(rendered, `name="note"`) {
		t.Fatalf("department form does not carry the status/note fields")
	}
}

// 现场终端区块表单动作与第六节 API 契约一一对应，hx URL 钉死完整前缀：发送
// 消息 → hx-post /crate-api/prototype/v1/drills/{rid}/messages（字段
// sender_type/sender_name/content）；上报热力 → hx-post
// /crate-api/prototype/v1/drills/{rid}/zone-densities（字段 zone_name/
// people_count）；上报设备 → hx-post /crate-api/prototype/v1/drills/{rid}/
// devices（字段 device_name/device_type/status/note）；指令反馈 → hx-put
// /crate-api/prototype/v1/drills/{rid}/orders/{oid}（字段 status/feedback，
// oid 为演示数据带出的固定 26 位 ULID）。页面不构造 id。
func TestRenderConsoleFieldForms(t *testing.T) {
	var output strings.Builder
	if err := RenderConsole(&output, consoleFixture()); err != nil {
		t.Fatalf("RenderConsole: %v", err)
	}
	rendered := output.String()
	runID := "06G00NC5ZWA3K5G194PSBJ8WNR"
	orderID := "06G00NC5ZYM6TMDVQ0CTC4GF5C"

	// 发送消息 → POST /drills/{rid}/messages，字段 sender_type/sender_name/content。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/messages"`) {
		t.Fatalf("message form does not target POST /drills/%s/messages", runID)
	}
	for _, field := range []string{"sender_type", "sender_name", "content"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("message form does not carry the %s field", field)
		}
	}
	// 上报热力 → POST /drills/{rid}/zone-densities，字段 zone_name/people_count。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/zone-densities"`) {
		t.Fatalf("zone form does not target POST /drills/%s/zone-densities", runID)
	}
	for _, field := range []string{"zone_name", "people_count"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("zone form does not carry the %s field", field)
		}
	}
	// 上报设备 → POST /drills/{rid}/devices，字段 device_name/device_type/status/note。
	if !strings.Contains(rendered, `hx-post="/crate-api/prototype/v1/drills/`+runID+`/devices"`) {
		t.Fatalf("device form does not target POST /drills/%s/devices", runID)
	}
	for _, field := range []string{"device_name", "device_type", "status", "note"} {
		if !strings.Contains(rendered, `name="`+field+`"`) {
			t.Fatalf("device form does not carry the %s field", field)
		}
	}
	// 指令反馈 → PUT /drills/{rid}/orders/{oid}，字段 status/feedback。
	if !strings.Contains(rendered, `hx-put="/crate-api/prototype/v1/drills/`+runID+`/orders/`+orderID+`"`) {
		t.Fatalf("feedback form does not target PUT /drills/%s/orders/%s", runID, orderID)
	}
	if !strings.Contains(rendered, `name="feedback"`) {
		t.Fatalf("feedback form does not carry the feedback field")
	}
}

// 跨切面：页面出现的全部服务端 id（表单 URL 中的 rid/oid 与各列表行的 id）
// 均匹配 26 位 Crockford Base32 正则 ^[0-9A-HJKMNP-TV-Z]{26}$；部门联动表
// 单的 {department} 路径段为业务枚举（消防），不参与该断言；页面无任何 id
// 构造输入（无 name="id" 输入项）。
func TestRenderConsoleIDsAreULIDsAndNoIDInput(t *testing.T) {
	var output strings.Builder
	if err := RenderConsole(&output, consoleFixture()); err != nil {
		t.Fatalf("RenderConsole: %v", err)
	}
	rendered := output.String()
	runID := "06G00NC5ZWA3K5G194PSBJ8WNR"

	// 表单 URL 中的 rid：所有 /drills/{rid}/ 段均为固定示例 ULID。
	ridSegment := regexp.MustCompile(`drills/([^/"']+)/`)
	ridMatches := ridSegment.FindAllStringSubmatch(rendered, -1)
	if len(ridMatches) == 0 {
		t.Fatalf("rendered page does not reference any run id")
	}
	for _, match := range ridMatches {
		if match[1] != runID {
			t.Fatalf("run id %q in a form URL, want the data-carried %q", match[1], runID)
		}
		if !consoleULIDPattern.MatchString(match[1]) {
			t.Fatalf("run id %q is not a 26-character Crockford Base32 ULID", match[1])
		}
	}
	// 反馈表单 URL 中的 oid：数据带出的固定 ULID。
	oidSegment := regexp.MustCompile(`drills/` + runID + `/orders/([^/"']+)`)
	oidMatches := oidSegment.FindAllStringSubmatch(rendered, -1)
	if len(oidMatches) != 1 {
		t.Fatalf("feedback form must carry exactly one order id, got %d", len(oidMatches))
	}
	if !consoleULIDPattern.MatchString(oidMatches[0][1]) {
		t.Fatalf("order id %q is not a 26-character Crockford Base32 ULID", oidMatches[0][1])
	}
	// 部门联动表单的 {department} 路径段为业务枚举值，不参与 ULID 断言。
	departmentSegment := regexp.MustCompile(`drills/` + runID + `/departments/([^/"']+)`)
	departmentMatches := departmentSegment.FindAllStringSubmatch(rendered, -1)
	if len(departmentMatches) != 1 || departmentMatches[0][1] != "消防" {
		t.Fatalf("department form must carry exactly one business enum segment, got %v", departmentMatches)
	}
	// 各列表行的 id（指令/消息/热力/设备/部门编号）均为 26 位 ULID。
	rowIDPattern := regexp.MustCompile(`class="(?:order|message|zone|device|department)-id">([^<]+)<`)
	rowMatches := rowIDPattern.FindAllStringSubmatch(rendered, -1)
	if len(rowMatches) == 0 {
		t.Fatalf("rendered page does not render any list-row id")
	}
	for _, match := range rowMatches {
		if !consoleULIDPattern.MatchString(match[1]) {
			t.Fatalf("list-row id %q is not a 26-character Crockford Base32 ULID", match[1])
		}
	}
	// 页面不构造 ID：表单只携带契约字段，没有任何 id 输入项。
	if strings.Contains(rendered, `name="id"`) {
		t.Fatalf("forms must not carry an id input (the page never constructs ids)")
	}
}
