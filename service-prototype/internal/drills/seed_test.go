package drills

import (
	"context"
	"testing"
)

// seedListLimit is the page size used by the seed tests when counting
// and locating rows; the seed data never grows beyond a handful of rows.
const seedListLimit = 1000

// seedService returns a service over a fresh in-memory store using the
// real clock and the real ULID generator, so the seed tests can assert
// the 26-character Crockford Base32 ids and the server timestamps of the
// seed rows. The tests never touch a database.
func seedService() *Service {
	return NewService(NewInMemoryStore())
}

// countSeedRows returns the total number of scenarios, steps and
// assessment points currently stored through the service.
func countSeedRows(t *testing.T, service *Service) (scenarios, steps, points int) {
	t.Helper()
	ctx := context.Background()
	records, total, err := service.ListScenarios(ctx, ScenarioFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListScenarios: %v", err)
	}
	scenarios = total
	for _, scenario := range records {
		_, stepTotal, err := service.ListSteps(ctx, scenario.ID, ListFilter{Limit: seedListLimit})
		if err != nil {
			t.Fatalf("ListSteps(%s): %v", scenario.ID, err)
		}
		steps += stepTotal
		_, pointTotal, err := service.ListPoints(ctx, scenario.ID, ListFilter{Limit: seedListLimit})
		if err != nil {
			t.Fatalf("ListPoints(%s): %v", scenario.ID, err)
		}
		points += pointTotal
	}
	return scenarios, steps, points
}

// findScenario locates the scenario with the given name in the store.
func findScenario(t *testing.T, service *Service, name string) Scenario {
	t.Helper()
	records, _, err := service.ListScenarios(context.Background(), ScenarioFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListScenarios: %v", err)
	}
	for _, scenario := range records {
		if scenario.Name == name {
			return scenario
		}
	}
	t.Fatalf("scenario %q not found", name)
	return Scenario{}
}

// assertSeedScenarioContent verifies one seeded scenario word for word
// against the canonical seed data: the category, the background, the
// lifecycle status 启用, the empty metadata object, created_by='system',
// the ULID id and the exact steps / assessment points.
func assertSeedScenarioContent(t *testing.T, service *Service, scenario Scenario, seed SeedScenario) {
	t.Helper()
	ctx := context.Background()
	if !ulidPattern.MatchString(scenario.ID) {
		t.Errorf("scenario %q: id %q is not a 26-character Crockford Base32 ULID", scenario.Name, scenario.ID)
	}
	if scenario.Category != seed.Category {
		t.Errorf("scenario %q: category = %q, want %q", scenario.Name, scenario.Category, seed.Category)
	}
	if scenario.Background != seed.Background {
		t.Errorf("scenario %q: background = %q, want %q", scenario.Name, scenario.Background, seed.Background)
	}
	if scenario.Status != ScenarioStatusEnabled {
		t.Errorf("scenario %q: status = %q, want %q", scenario.Name, scenario.Status, ScenarioStatusEnabled)
	}
	if scenario.CreatedBy != "system" {
		t.Errorf("scenario %q: created_by = %q, want %q", scenario.Name, scenario.CreatedBy, "system")
	}
	if scenario.Metadata == nil {
		t.Errorf("scenario %q: metadata is nil, want an empty object", scenario.Name)
	} else if len(scenario.Metadata) != 0 {
		t.Errorf("scenario %q: metadata = %v, want an empty object", scenario.Name, scenario.Metadata)
	}
	if scenario.CreatedAt.IsZero() || scenario.UpdatedAt.IsZero() {
		t.Errorf("scenario %q: timestamps must be set by the service clock (created_at=%v, updated_at=%v)", scenario.Name, scenario.CreatedAt, scenario.UpdatedAt)
	}

	steps, total, err := service.ListSteps(ctx, scenario.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListSteps(%s): %v", scenario.ID, err)
	}
	if total != len(seed.Steps) {
		t.Errorf("scenario %q: step total = %d, want %d", scenario.Name, total, len(seed.Steps))
	}
	for index, step := range steps {
		expected := seed.Steps[index]
		if !ulidPattern.MatchString(step.ID) {
			t.Errorf("scenario %q step %d: id %q is not a ULID", scenario.Name, index+1, step.ID)
		}
		if step.SortOrder != expected.SortOrder {
			t.Errorf("scenario %q step %d: sort_order = %d, want %d", scenario.Name, index+1, step.SortOrder, expected.SortOrder)
		}
		if step.Title != expected.Title {
			t.Errorf("scenario %q step %d: title = %q, want %q", scenario.Name, index+1, step.Title, expected.Title)
		}
		if step.Description != expected.Description {
			t.Errorf("scenario %q step %d: description = %q, want %q", scenario.Name, index+1, step.Description, expected.Description)
		}
		if step.CreatedBy != "system" {
			t.Errorf("scenario %q step %d: created_by = %q, want %q", scenario.Name, index+1, step.CreatedBy, "system")
		}
		if step.CreatedAt.IsZero() || step.UpdatedAt.IsZero() {
			t.Errorf("scenario %q step %d: timestamps must be set by the service clock", scenario.Name, index+1)
		}
	}

	points, total, err := service.ListPoints(ctx, scenario.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListPoints(%s): %v", scenario.ID, err)
	}
	if total != len(seed.Points) {
		t.Errorf("scenario %q: point total = %d, want %d", scenario.Name, total, len(seed.Points))
	}
	for index, point := range points {
		expected := seed.Points[index]
		if !ulidPattern.MatchString(point.ID) {
			t.Errorf("scenario %q point %d: id %q is not a ULID", scenario.Name, index+1, point.ID)
		}
		if point.Title != expected.Title {
			t.Errorf("scenario %q point %d: title = %q, want %q", scenario.Name, index+1, point.Title, expected.Title)
		}
		if point.Description != "" {
			t.Errorf("scenario %q point %d: description = %q, want empty", scenario.Name, index+1, point.Description)
		}
		if point.CreatedBy != "system" {
			t.Errorf("scenario %q point %d: created_by = %q, want %q", scenario.Name, index+1, point.CreatedBy, "system")
		}
		if point.CreatedAt.IsZero() || point.UpdatedAt.IsZero() {
			t.Errorf("scenario %q point %d: timestamps must be set by the service clock", scenario.Name, index+1)
		}
	}
}

// TestSeedInsertsBuiltinScenarios: the first seed run inserts exactly
// the four built-in scenarios with their 21 steps and 15 assessment
// points, and every seed row carries the seed field contract
// (created_by='system', scenario status 启用, empty metadata, ULID ids)
// and the canonical content word for word.
func TestSeedInsertsBuiltinScenarios(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("Seed: %v", err)
	}

	scenarios, steps, points := countSeedRows(t, service)
	if scenarios != 4 || steps != 21 || points != 15 {
		t.Fatalf("after seed: scenarios/steps/points = %d/%d/%d, want 4/21/15", scenarios, steps, points)
	}

	for _, seed := range SeedData {
		scenario := findScenario(t, service, seed.Name)
		assertSeedScenarioContent(t, service, scenario, seed)
	}
}

// TestSeedIsIdempotent: a second seed run must not produce any
// additional rows; the totals stay 4/21/15.
func TestSeedIsIdempotent(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("first Seed: %v", err)
	}
	if err := Seed(ctx, service); err != nil {
		t.Fatalf("second Seed: %v", err)
	}

	scenarios, steps, points := countSeedRows(t, service)
	if scenarios != 4 || steps != 21 || points != 15 {
		t.Fatalf("after second seed: scenarios/steps/points = %d/%d/%d, want 4/21/15 (no duplicates)", scenarios, steps, points)
	}
}

// TestSeedKeepsDisabledScenario (修改保留案一): disabling a seed
// scenario (name unchanged) must survive a further seed run — the seed
// skips the existing name entirely, so the totals stay 4/21/15 and the
// status stays 停用.
func TestSeedKeepsDisabledScenario(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("Seed: %v", err)
	}
	const scenarioName = "停电与基础设施故障应急演练"
	scenario := findScenario(t, service, scenarioName)
	updated, err := service.UpdateScenario(ctx, scenario.ID, ScenarioInput{
		Name:       scenarioName,
		Category:   scenario.Category,
		Background: scenario.Background,
		Status:     ScenarioStatusDisabled,
		CreatedBy:  "system",
	})
	if err != nil {
		t.Fatalf("UpdateScenario: %v", err)
	}
	if updated.Status != ScenarioStatusDisabled {
		t.Fatalf("updated status = %q, want %q", updated.Status, ScenarioStatusDisabled)
	}

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("second Seed: %v", err)
	}

	scenarios, steps, points := countSeedRows(t, service)
	if scenarios != 4 || steps != 21 || points != 15 {
		t.Fatalf("after re-seed: scenarios/steps/points = %d/%d/%d, want 4/21/15", scenarios, steps, points)
	}
	disabled := findScenario(t, service, scenarioName)
	if disabled.Status != ScenarioStatusDisabled {
		t.Errorf("status after re-seed = %q, want %q (disabled state must be preserved)", disabled.Status, ScenarioStatusDisabled)
	}
}

// TestSeedReinsertsRenamedScenario (修改保留案二): renaming a seed
// scenario releases the original name, so the next seed run inserts a
// fresh scenario under the seed name (totals 4→5 / 21→26 / 15→19); the
// renamed row keeps its new name and its own steps and points untouched,
// and both rows are visible in the listing.
func TestSeedReinsertsRenamedScenario(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("Seed: %v", err)
	}
	const seedName = "大客流聚集应急演练"
	const renamedName = "自定义客流场景"
	scenario := findScenario(t, service, seedName)
	renamed, err := service.UpdateScenario(ctx, scenario.ID, ScenarioInput{
		Name:       renamedName,
		Category:   scenario.Category,
		Background: scenario.Background,
		CreatedBy:  "system",
	})
	if err != nil {
		t.Fatalf("UpdateScenario: %v", err)
	}
	if renamed.Name != renamedName {
		t.Fatalf("renamed scenario name = %q, want %q", renamed.Name, renamedName)
	}

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("second Seed: %v", err)
	}

	scenarios, steps, points := countSeedRows(t, service)
	if scenarios != 5 || steps != 26 || points != 19 {
		t.Fatalf("after re-seed: scenarios/steps/points = %d/%d/%d, want 5/26/19 (renamed seed re-inserted)", scenarios, steps, points)
	}

	// The renamed row keeps its new name and its original 5 steps and 4
	// points (never touched by the seed run).
	kept := findScenario(t, service, renamedName)
	if kept.ID != scenario.ID {
		t.Fatalf("renamed row id = %q, want %q", kept.ID, scenario.ID)
	}
	_, keptSteps, err := service.ListSteps(ctx, kept.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListSteps(%s): %v", kept.ID, err)
	}
	_, keptPoints, err := service.ListPoints(ctx, kept.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListPoints(%s): %v", kept.ID, err)
	}
	if keptSteps != 5 || keptPoints != 4 {
		t.Fatalf("renamed row steps/points = %d/%d, want 5/4 (untouched)", keptSteps, keptPoints)
	}

	// The fresh seed row under the original name is visible as well.
	fresh := findScenario(t, service, seedName)
	if fresh.ID == scenario.ID {
		t.Fatalf("fresh seed row reuses the renamed row id %q", fresh.ID)
	}
	_, freshSteps, err := service.ListSteps(ctx, fresh.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListSteps(%s): %v", fresh.ID, err)
	}
	_, freshPoints, err := service.ListPoints(ctx, fresh.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListPoints(%s): %v", fresh.ID, err)
	}
	if freshSteps != 5 || freshPoints != 4 {
		t.Fatalf("fresh seed row steps/points = %d/%d, want 5/4", freshSteps, freshPoints)
	}
}

// TestSeedSkipsUserCreatedScenario: deduplication looks at the current
// name only, not at the origin — a user-created scenario that already
// carries a seed name is skipped like any other existing row, and its
// rows are left untouched.
func TestSeedSkipsUserCreatedScenario(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	userScenario, err := service.CreateScenario(ctx, ScenarioInput{
		Name:       SeedData[0].Name,
		Category:   SeedData[0].Category,
		Background: SeedData[0].Background,
		CreatedBy:  "admin",
	})
	if err != nil {
		t.Fatalf("CreateScenario: %v", err)
	}

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("Seed: %v", err)
	}

	// 1 user scenario (name taken, skipped) + 3 remaining seed scenarios.
	scenarios, steps, points := countSeedRows(t, service)
	if scenarios != 4 || steps != 16 || points != 11 {
		t.Fatalf("after seed: scenarios/steps/points = %d/%d/%d, want 4/16/11 (user-created name skipped)", scenarios, steps, points)
	}

	kept := findScenario(t, service, SeedData[0].Name)
	if kept.ID != userScenario.ID {
		t.Fatalf("kept scenario id = %q, want the user row %q", kept.ID, userScenario.ID)
	}
	if kept.CreatedBy != "admin" {
		t.Errorf("kept scenario created_by = %q, want %q (user row untouched)", kept.CreatedBy, "admin")
	}
	_, userSteps, err := service.ListSteps(ctx, userScenario.ID, ListFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListSteps(%s): %v", userScenario.ID, err)
	}
	if userSteps != 0 {
		t.Errorf("user scenario steps = %d, want 0 (no steps patched into the user row)", userSteps)
	}
}
