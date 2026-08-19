package web

import (
	"html/template"
	"io"
)

// ConsolePageData carries the display data of the command console and
// field terminal page (指挥调度操作与现场终端). The page renders purely
// from this in-memory payload — no database access, no API call — so
// the caller builds it from the built-in demo data and mints the
// server-side ids. The page is a write-oriented demo: the commander
// block (会话配置、下达指令、部门联动) and the field-terminal block (发送
// 消息、上报热力、上报设备、指令反馈) carry seven forms whose hx actions
// anchor on the run id of the demo drill run, and the list rows of the
// orders/messages/zones/devices/departments demo data carry their
// server-minted ids.
type ConsolePageData struct {
	// RunID is the 26-character ULID of the demo drill run the forms
	// anchor on (the {rid} path segment). The page itself never
	// constructs ids.
	RunID       string
	Session     ConsoleSessionView
	Orders      []ConsoleOrderView
	Departments []ConsoleDepartmentView
	Messages    []ConsoleMessageView
	Zones       []ConsoleZoneView
	Devices     []ConsoleDeviceView
}

// ConsoleSessionView is the dispatch command session configuration (指
// 挥调度会话配置) of the run: the training mode (实训方式), the main venue
// (主场馆) and the joint venues (联训场馆).
type ConsoleSessionView struct {
	Mode        string
	MainVenue   string
	JointVenues []string
}

// ConsoleOrderView is one dispatch order (调度指令) of the page: the
// server-minted order id, the title, content, priority, receiver
// (target_type/target_name), the deadline and the execution status. The
// 执行中 order of the demo data is also the anchor of the field-terminal
// feedback form (its id becomes the {oid} path segment).
type ConsoleOrderView struct {
	ID         string
	Title      string
	Content    string
	Priority   string
	TargetType string
	TargetName string
	Deadline   string
	Status     string
}

// ConsoleDepartmentView is one linkage department (联动部门) of the
// page: the server-minted report id, the department business enum
// (消防/公安/卫健/场馆应急组/其他), the linkage status and the optional
// note. The first department of the demo data is also the target of the
// commander's department-linkage form (its enum value becomes the
// {department} path segment).
type ConsoleDepartmentView struct {
	ID         string
	Department string
	Status     string
	Note       string
}

// ConsoleMessageView is one dispatch message (即时通讯消息) of the page:
// the server-minted message id, the sender side (指挥中心/现场人员), the
// sender display name and the content.
type ConsoleMessageView struct {
	ID         string
	SenderType string
	SenderName string
	Content    string
}

// ConsoleZoneView is one zone crowd-density report (区域人流热力上报) of
// the page: the server-minted report id, the zone name and the reported
// people count.
type ConsoleZoneView struct {
	ID          string
	ZoneName    string
	PeopleCount int
}

// ConsoleDeviceView is one device running-status report (设备运行状态上
// 报) of the page: the server-minted report id, the device name, kind
// and running status (正常/告警/离线) with the optional fault note.
type ConsoleDeviceView struct {
	ID         string
	DeviceName string
	DeviceType string
	Status     string
	Note       string
}

// consoleTemplate is the parsed template collection of the command
// console and field terminal page (layout + console page). It lives in
// its own template set because the layout's content/title hooks are
// page-specific: every page defines its own content block, and one
// shared parse set would let the alphabetically last page win for every
// page (same pattern as the scenarios, drills and command pages).
var consoleTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/console.html"))

// RenderConsole renders the command console and field terminal page
// (layout + console content) with the given in-memory display data. All
// user-controlled input is HTML-escaped by html/template.
func RenderConsole(w io.Writer, data ConsolePageData) error {
	return consoleTemplate.ExecuteTemplate(w, "layout.html", data)
}
