package web

import (
	"html/template"
	"io"
)

// ScenariosPageData carries the display data of the drill scenario
// template management page. The page renders purely from this in-memory
// payload — no database access, no API call — so the caller builds it
// from the built-in seed data (drills.SeedData) and mints the
// server-side ids.
type ScenariosPageData struct {
	Scenarios []ScenarioView
}

// ScenarioView is one scenario template card of the page: the
// server-generated id, the template fields and the ordered steps and
// assessment points of the template.
type ScenarioView struct {
	ID         string
	Name       string
	Category   string
	Background string
	Status     string
	Steps      []StepView
	Points     []PointView
}

// StepView is one scenario step (演练流程步骤) of the page.
type StepView struct {
	ID          string
	SortOrder   int
	Title       string
	Description string
}

// PointView is one assessment point (考核要点模板) of the page.
type PointView struct {
	ID          string
	Title       string
	Description string
}

// scenariosTemplate is the parsed template collection of the drill
// scenario management page (layout + scenarios page). It lives in its
// own template set because the layout's content/title hooks are
// page-specific: the demo page and the scenarios page each define their
// own content block, and one shared parse set would let the
// alphabetically last page win for every page.
var scenariosTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/scenarios.html"))

// RenderScenarios renders the drill scenario template management page
// (layout + scenarios content) with the given in-memory display data.
// All user-controlled input is HTML-escaped by html/template.
func RenderScenarios(w io.Writer, data ScenariosPageData) error {
	return scenariosTemplate.ExecuteTemplate(w, "layout.html", data)
}
