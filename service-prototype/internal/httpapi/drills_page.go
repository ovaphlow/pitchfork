package httpapi

import (
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// drillsPagePath is the server-rendered drill execution and assessment
// page (htmx SSR, no shared client, no database).
const drillsPagePath = "/demo/drills"

// handleDrillsPage renders the drill execution and assessment page. The
// display data is injected in memory — the example drill runs fixture
// (drills.SeedData carries no runs) plus the built-in scenarios from
// drills.SeedData — so the page renders without a database or a running
// API. The example runs carry fixed 26-character server-side ULIDs; the
// steps, assessment points and scenario options come from the seed data
// and get a fresh server-minted ULID per render, exactly like the
// backing API mints ids at creation; the page itself never constructs
// ids.
func handleDrillsPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderDrills(w, drillsPageData(drills.SeedData)); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// exampleRunULIDs are the fixed 26-character Crockford Base32 ULIDs of
// the example drill runs and simulated events of the page fixture. The
// runs are "existing" data (created earlier, so their ids are stable),
// while the steps, points and scenario options of the seed data have no
// ids and are minted per render.
const (
	exampleRun1ID       = "06FZXT3DW8KHD056NHT2QYKY3M"
	exampleRun2ID       = "06FZXT3DW9JBJ4NESWSSTFCBKG"
	exampleSimEvent1ID  = "06FZXT3DW9H30SS3Y92F8090PG"
	exampleRun1Title    = "迎新春特展大客流聚集应急演练"
	exampleRun1Scenario = "大客流聚集应急演练"
	exampleRun2Title    = "市电中断应急处置演练"
	exampleRun2Scenario = "停电与基础设施故障应急演练"
)

// drillsPageData converts the built-in seed scenarios and the example
// drill runs fixture into the page view model. The steps and assessment
// points of each run are taken from the run's seed scenario (ordered by
// sort_order), so they stay in sync with the seed data of the scenario
// page and the seed migration 000016; each render mints fresh
// server-side ids for them. The example run carries one triggered
// simulated event with its simulated data payload, demonstrating the
// trigger/display/handle loop of the sim-event demo area.
func drillsPageData(seed []drills.SeedScenario) web.DrillsPageData {
	byName := make(map[string]drills.SeedScenario, len(seed))
	options := make([]web.ScenarioOption, 0, len(seed))
	for _, item := range seed {
		byName[item.Name] = item
		options = append(options, web.ScenarioOption{ID: ulid.New(), Name: item.Name})
	}

	stepsOf := func(name string) []web.StepView {
		item, ok := byName[name]
		if !ok {
			return nil
		}
		steps := make([]web.StepView, 0, len(item.Steps))
		for _, step := range item.Steps {
			steps = append(steps, web.StepView{
				ID:          ulid.New(),
				SortOrder:   step.SortOrder,
				Title:       step.Title,
				Description: step.Description,
			})
		}
		return steps
	}
	pointsOf := func(name string) []web.PointView {
		item, ok := byName[name]
		if !ok {
			return nil
		}
		points := make([]web.PointView, 0, len(item.Points))
		for _, point := range item.Points {
			points = append(points, web.PointView{
				ID:    ulid.New(),
				Title: point.Title,
			})
		}
		return points
	}

	statusInProgress := string(drills.RunStatusInProgress)
	return web.DrillsPageData{
		Scenarios: options,
		Runs: []web.RunView{
			{
				ID:           exampleRun1ID,
				Title:        exampleRun1Title,
				ScenarioName: exampleRun1Scenario,
				Status:       statusInProgress,
				Steps:        stepsOf(exampleRun1Scenario),
				Points:       pointsOf(exampleRun1Scenario),
				SimEvents: []web.SimEventView{
					{
						ID:        exampleSimEvent1ID,
						EventType: string(drills.SimEventFlowOverflow),
						Status:    string(drills.SimEventTriggered),
						Payload:   `{"area":"A区东侧展厅","density":8.5,"threshold":6.0}`,
					},
				},
			},
			{
				ID:           exampleRun2ID,
				Title:        exampleRun2Title,
				ScenarioName: exampleRun2Scenario,
				Status:       statusInProgress,
				Steps:        stepsOf(exampleRun2Scenario),
				Points:       pointsOf(exampleRun2Scenario),
			},
		},
	}
}
