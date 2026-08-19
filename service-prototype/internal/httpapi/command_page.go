package httpapi

import (
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// commandPagePath is the server-rendered command-center big screen page
// (htmx SSR, read-only monitoring view, no shared client, no database).
const commandPagePath = "/demo/command"

// handleCommandPage renders the command-center big screen page. Non-GET
// requests on the page path yield the repository-standard 405 JSON with
// Allow: GET (same convention as the API resource routes). The display
// data is injected in memory from the built-in demo data below —
// no database, no API call. The demo rows carry no ids, so each render
// mints a fresh 26-character Crockford Base32 ULID for the demo drill
// run, exactly like the backing API does at creation; the hx-get
// refresh actions of the message-flow, zone-heat and device blocks
// anchor on that run id. The page itself never constructs ids. The
// hx-get refresh actions are same-origin GET requests, so no CORS
// preflight applies (the page has no cross-origin write methods).
func handleCommandPage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderCommand(w, commandPageData()); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// commandPageData builds the display payload of the command-center big
// screen from the built-in demo data: one dispatch session (远程协同
// mode with the main venue and two joint venues), three zone-density
// reports covering the low/medium/high heat levels, three device
// reports covering 正常/告警/离线, four department linkage reports
// covering 未响应/已响应/已到位/处置中, two dispatch messages (指挥中心/
// 现场人员) and four dispatch orders (one per order status
// 待接收/已接收/执行中/已完成 with the feedback trail). The field
// values follow the dispatch contracts word for word (Chinese enum
// values included); the demo rows carry no ids, so each render mints a
// fresh server-side run id.
func commandPageData() web.CommandPageData {
	return web.CommandPageData{
		RunID: ulid.New(),
		Session: web.SessionView{
			Mode:        string(dispatch.ModeRemote),
			MainVenue:   "主馆一层大厅",
			JointVenues: []string{"东区联训馆", "西区联训馆"},
		},
		Zones: []web.ZoneView{
			{ZoneName: "A区东侧展厅", PeopleCount: 180},
			{ZoneName: "B区中央大厅", PeopleCount: 520},
			{ZoneName: "C区南门通道", PeopleCount: 960},
		},
		Devices: []web.DeviceView{
			{DeviceName: "一号供配电柜", DeviceType: string(dispatch.DeviceTypePowerSupply), Status: string(dispatch.DeviceStatusNormal)},
			{DeviceName: "东区烟感探测器", DeviceType: string(dispatch.DeviceTypeFire), Status: string(dispatch.DeviceStatusWarning), Note: "烟雾浓度超限"},
			{DeviceName: "B区客流摄像头", DeviceType: string(dispatch.DeviceTypeSecurity), Status: string(dispatch.DeviceStatusOffline)},
		},
		Departments: []web.DepartmentView{
			{Department: "安保部", Status: string(dispatch.DepartmentStatusArrived), Note: "南门通道已增派 6 人"},
			{Department: "消防组", Status: string(dispatch.DepartmentStatusHandling)},
			{Department: "客服部", Status: string(dispatch.DepartmentStatusResponded)},
			{Department: "设备部", Status: string(dispatch.DepartmentStatusNotResponded)},
		},
		Messages: []web.MessageView{
			{SenderType: string(dispatch.SenderTypeCommand), SenderName: "总指挥", Content: "各点位注意，A区客流已接近阈值，请加强疏导"},
			{SenderType: string(dispatch.SenderTypeField), SenderName: "南门岗", Content: "收到，已增开一条安检通道"},
		},
		Orders: []web.OrderView{
			{Title: "加强A区客流疏导", Content: "A区东侧展厅人流密度持续上升，请立即加强现场疏导", Priority: string(dispatch.PriorityNormal), TargetType: string(dispatch.TargetTypeDepartment), TargetName: "安保部", Status: string(dispatch.OrderStatusPending)},
			{Title: "启动应急广播", Content: "通过馆内广播发布限流提示", Priority: string(dispatch.PriorityUrgent), TargetType: string(dispatch.TargetTypeDepartment), TargetName: "客服部", Status: string(dispatch.OrderStatusReceived), Feedback: "已收到指令，正在准备广播内容"},
			{Title: "增开安检通道", Content: "南门增开一条安检通道，缓解排队", Priority: string(dispatch.PriorityNormal), TargetType: string(dispatch.TargetTypeGroup), TargetName: "安保二组", Status: string(dispatch.OrderStatusExecuting), Feedback: "南门已增开一条安检通道"},
			{Title: "排查东区烟感报警", Content: "立即排查东区展厅烟感探测器告警原因", Priority: string(dispatch.PriorityCritical), TargetType: string(dispatch.TargetTypePerson), TargetName: "消防值班员", Status: string(dispatch.OrderStatusCompleted), Feedback: "已排查完毕，确认为装修粉尘触发"},
		},
	}
}
