package web

import (
	"html/template"
	"io"
)

// CommandPageData carries the display data of the command-center big
// screen page (指挥中心大屏). The page renders purely from this in-memory
// payload — no database access, no API call — so the caller builds it
// from the built-in demo data and mints the server-side run id. The
// page is a read-only monitoring view: the six blocks (会话配置、区域
// 热力、设备状态、部门联动、消息流、指令列表) only display data, and the
// hx-get refresh actions of the message-flow, zone-heat and device
// blocks anchor on the run id carried by the data.
type CommandPageData struct {
	// RunID is the 26-character server-minted ULID of the demo drill
	// run. It anchors the hx-get refresh endpoints; the page itself
	// never constructs ids.
	RunID       string
	Session     SessionView
	Zones       []ZoneView
	Devices     []DeviceView
	Departments []DepartmentView
	Messages    []MessageView
	Orders      []OrderView
}

// SessionView is the dispatch command session configuration (指挥调度
// 会话配置) of the run: the training mode (实训方式), the main venue
// (主场馆) and the joint venues (联训场馆).
type SessionView struct {
	Mode        string
	MainVenue   string
	JointVenues []string
}

// ZoneView is one zone crowd-density report (区域人流热力上报) of the
// page. The heat level (低/中/高) and the graded color class are
// decided by the template from the fixed thresholds (PeopleCount < 300
// low, 300–800 medium, > 800 high), so the thresholds stay visible in
// the markup.
type ZoneView struct {
	ZoneName    string
	PeopleCount int
}

// DeviceView is one device running-status report (设备运行状态上报) of
// the page: the device name, kind and running status (正常/告警/离线)
// with the optional fault note. 告警/离线 devices are highlighted by
// the template with a striking style class.
type DeviceView struct {
	DeviceName string
	DeviceType string
	Status     string
	Note       string
}

// DepartmentView is one department linkage-disposal report (部门联动处
// 置记录) of the page: the department name, the linkage status and the
// optional note.
type DepartmentView struct {
	Department string
	Status     string
	Note       string
}

// MessageView is one dispatch message (即时通讯消息) of the page: the
// sender side (指挥中心/现场人员), the sender display name and the
// content.
type MessageView struct {
	SenderType string
	SenderName string
	Content    string
}

// OrderView is one dispatch order (调度指令) of the page: the title,
// content, priority, receiver, execution status and the feedback trail.
type OrderView struct {
	Title      string
	Content    string
	Priority   string
	TargetType string
	TargetName string
	Status     string
	Feedback   string
}

// commandTemplate is the parsed template collection of the
// command-center big screen page (layout + command page). It lives in
// its own template set because the layout's content/title hooks are
// page-specific: every page defines its own content block, and one
// shared parse set would let the alphabetically last page win for every
// page (same pattern as the scenarios and drills pages).
var commandTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/command.html"))

// RenderCommand renders the command-center big screen page (layout +
// command content) with the given in-memory display data. All
// user-controlled input is HTML-escaped by html/template.
func RenderCommand(w io.Writer, data CommandPageData) error {
	return commandTemplate.ExecuteTemplate(w, "layout.html", data)
}
