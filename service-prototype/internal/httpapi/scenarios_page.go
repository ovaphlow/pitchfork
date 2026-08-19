package httpapi

import (
	"net/http"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// scenariosPagePath is the server-rendered drill scenario template
// management page (htmx SSR, no shared client, no database).
const scenariosPagePath = "/demo/scenarios"

// handleScenariosPage renders the drill scenario template management
// page. The display data is injected in memory from drills.SeedData —
// the four built-in scenarios with their steps and assessment points —
// so the page renders without a database or a running API. The server
// mints a fresh 26-character Crockford Base32 ULID per scenario, step
// and assessment point, exactly like the backing API does at creation;
// the page itself never constructs ids.
func handleScenariosPage(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderScenarios(w, scenariosPageData(drills.SeedData)); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// scenariosPageData converts the built-in seed scenarios into the page
// view model. The seed rows carry no ids (they are minted by the service
// at seed time), so each render mints fresh server-side ids; the status
// defaults to 启用, matching the seed contract of the migration
// 000016_drill_seed.sql.
func scenariosPageData(seed []drills.SeedScenario) web.ScenariosPageData {
	scenarios := make([]web.ScenarioView, 0, len(seed))
	for _, item := range seed {
		steps := make([]web.StepView, 0, len(item.Steps))
		for _, step := range item.Steps {
			steps = append(steps, web.StepView{
				ID:          ulid.New(),
				SortOrder:   step.SortOrder,
				Title:       step.Title,
				Description: step.Description,
			})
		}
		points := make([]web.PointView, 0, len(item.Points))
		for _, point := range item.Points {
			points = append(points, web.PointView{
				ID:    ulid.New(),
				Title: point.Title,
			})
		}
		scenarios = append(scenarios, web.ScenarioView{
			ID:         ulid.New(),
			Name:       item.Name,
			Category:   string(item.Category),
			Background: item.Background,
			Status:     string(drills.DefaultScenarioStatus),
			Steps:      steps,
			Points:     points,
		})
	}
	return web.ScenariosPageData{Scenarios: scenarios}
}
