package httpapi

import (
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// consolePagePath is the server-rendered command console and field
// terminal page (htmx SSR, no shared client, no database).
const consolePagePath = "/demo/console"

// exampleConsoleRunID is the fixed 26-character Crockford Base32 ULID of
// the demo drill run the console page anchors its seven forms on. The
// run is "existing" demo data (like the example runs of the drills
// page), so its id stays stable across renders; the demo rows (orders,
// messages, zone reports, device reports, department reports) carry no
// ids and are minted fresh per render, exactly like the backing API
// mints ids at creation. The page itself never constructs ids.
const exampleConsoleRunID = "06G00NC5ZWA3K5G194PSBJ8WNR"

// handleConsolePage renders the command console and field terminal page.
// Non-GET requests on the page path yield the repository-standard 405
// JSON with Allow: GET (same convention as the API resource routes).
// The display data is injected in memory from the built-in demo data
// below — no database, no API call. The seven forms of the page (会话
// 配置/下达指令/部门联动 on the commander block, 发送消息/上报热力/上报设
// 备/指令反馈 on the field-terminal block) target the sixth-section API
// contract endpoints under /crate-api/prototype/v1/drills/{rid}/...,
// with the rid and the feedback form's oid carried by the demo data;
// the department form's {department} path segment is the business enum
// value of the first demo department.
func handleConsolePage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderConsole(w, consolePageData()); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// consolePageData builds the display payload of the command console and
// field terminal page from the built-in demo data: one dispatch session
// (远程协同 mode with the main venue and two joint venues), two dispatch
// orders (the 执行中 order anchors the field-terminal feedback form),
// two department linkage reports (消防/场馆应急组, the first anchoring the
// commander's linkage form), two dispatch messages (指挥中心/现场人员),
// three zone-density reports and three device reports covering
// 正常/告警/离线. The field values follow the dispatch contracts word
// for word (Chinese enum values included); the demo rows carry no ids,
// so each render mints fresh server-side ids for them, while the run id
// stays fixed (exampleConsoleRunID).
func consolePageData() web.ConsolePageData {
	return web.ConsolePageData{
		RunID: exampleConsoleRunID,
		Session: web.ConsoleSessionView{
			Mode:        string(dispatch.ModeRemote),
			MainVenue:   "主馆一层大厅",
			JointVenues: []string{"东区联训馆", "西区联训馆"},
		},
		Orders: []web.ConsoleOrderView{
			{
				ID:         ulid.New(),
				Title:      "增开安检通道",
				Content:    "南门增开一条安检通道，缓解排队",
				Priority:   string(dispatch.PriorityNormal),
				TargetType: string(dispatch.TargetTypeDepartment),
				TargetName: "安保部",
				Deadline:   "2026-08-03T14:30",
				Status:     string(dispatch.OrderStatusExecuting),
			},
			{
				ID:         ulid.New(),
				Title:      "加强A区客流疏导",
				Content:    "A区东侧展厅人流密度持续上升，请立即加强现场疏导",
				Priority:   string(dispatch.PriorityCritical),
				TargetType: string(dispatch.TargetTypeGroup),
				TargetName: "安保二组",
				Deadline:   "2026-08-03T15:00",
				Status:     string(dispatch.OrderStatusPending),
			},
		},
		Departments: []web.ConsoleDepartmentView{
			{ID: ulid.New(), Department: string(dispatch.DepartmentFire), Status: string(dispatch.DepartmentStatusArrived), Note: "消防增援分队已就位"},
			{ID: ulid.New(), Department: string(dispatch.DepartmentVenue), Status: string(dispatch.DepartmentStatusHandling)},
		},
		Messages: []web.ConsoleMessageView{
			{ID: ulid.New(), SenderType: string(dispatch.SenderTypeCommand), SenderName: "总指挥", Content: "各点位注意，A区客流已接近阈值，请加强疏导"},
			{ID: ulid.New(), SenderType: string(dispatch.SenderTypeField), SenderName: "南门岗", Content: "收到，已增开一条安检通道"},
		},
		Zones: []web.ConsoleZoneView{
			{ID: ulid.New(), ZoneName: "A区东侧展厅", PeopleCount: 180},
			{ID: ulid.New(), ZoneName: "B区中央大厅", PeopleCount: 520},
			{ID: ulid.New(), ZoneName: "C区南门通道", PeopleCount: 960},
		},
		Devices: []web.ConsoleDeviceView{
			{ID: ulid.New(), DeviceName: "一号供配电柜", DeviceType: string(dispatch.DeviceTypePowerSupply), Status: string(dispatch.DeviceStatusNormal)},
			{ID: ulid.New(), DeviceName: "东区烟感探测器", DeviceType: string(dispatch.DeviceTypeFire), Status: string(dispatch.DeviceStatusWarning), Note: "烟雾浓度超限"},
			{ID: ulid.New(), DeviceName: "B区客流摄像头", DeviceType: string(dispatch.DeviceTypeSecurity), Status: string(dispatch.DeviceStatusOffline)},
		},
	}
}
