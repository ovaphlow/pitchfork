package web

import (
	"html/template"
	"io"
)

// DrillsPageData carries the display data of the drill execution and
// assessment page (演练执行与考核). The page renders purely from this
// in-memory payload — no database access, no API call — so the caller
// builds it from the example-runs fixture and the built-in seed data
// (drills.SeedData) and mints the server-side ids.
type DrillsPageData struct {
	// Runs is the drill task list; every task carries the ordered steps
	// and assessment points of its scenario plus the triggered simulated
	// events of the run.
	Runs []RunView
	// Scenarios is the scenario dropdown of the task creation form; the
	// option ids are server-minted (one per built-in scenario).
	Scenarios []ScenarioOption
}

// ScenarioOption is one option of the task creation form's scenario
// dropdown: the server-minted scenario id and the scenario name.
type ScenarioOption struct {
	ID   string
	Name string
}

// RunView is one drill task (演练任务) of the page: the server-generated
// id, the task title, the name of the attached scenario, the run status
// and the per-run steps, assessment points and simulated events.
type RunView struct {
	ID           string
	Title        string
	ScenarioName string
	Status       string
	Steps        []StepView
	Points       []PointView
	SimEvents    []SimEventView
}

// SimEventView is one triggered simulated event (模拟事件) of a run: the
// server-generated id, the event type, the handling status and the
// simulated data payload shown as raw JSON.
type SimEventView struct {
	ID        string
	EventType string
	Status    string
	Payload   string
}

// drillsTemplate is the parsed template collection of the drill
// execution and assessment page (layout + drills page). It lives in its
// own template set because the layout's content/title hooks are
// page-specific: every page defines its own content block, and one
// shared parse set would let the alphabetically last page win for every
// page (same pattern as the scenarios page).
var drillsTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/drills.html"))

// RenderDrills renders the drill execution and assessment page (layout +
// drills content) with the given in-memory display data. All
// user-controlled input is HTML-escaped by html/template.
func RenderDrills(w io.Writer, data DrillsPageData) error {
	return drillsTemplate.ExecuteTemplate(w, "layout.html", data)
}
