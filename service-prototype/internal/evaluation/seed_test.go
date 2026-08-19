package evaluation

import (
	"context"
	"regexp"
	"testing"
)

// ulidPattern matches a 26-character Crockford Base32 ULID (the alphabet
// omits I, L, O and U).
var ulidPattern = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

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

// listAllIndicators returns every indicator of the store (page size
// seedListLimit) and the total.
func listAllIndicators(t *testing.T, service *Service) ([]Indicator, int) {
	t.Helper()
	records, total, err := service.ListIndicators(context.Background(), IndicatorFilter{Limit: seedListLimit})
	if err != nil {
		t.Fatalf("ListIndicators: %v", err)
	}
	return records, total
}

// findIndicator locates the indicator with the given title in the store.
func findIndicator(t *testing.T, service *Service, title string) Indicator {
	t.Helper()
	records, _ := listAllIndicators(t, service)
	for _, indicator := range records {
		if indicator.Title == title {
			return indicator
		}
	}
	t.Fatalf("indicator %q not found", title)
	return Indicator{}
}

// assertSeedIndicatorContent verifies one seeded indicator word for word
// against the canonical seed data: the dimension, the demo flag, the
// description, created_by='system', the default weight 1, the
// per-dimension sort_order 1..N and the ULID id.
func assertSeedIndicatorContent(t *testing.T, service *Service, indicator Indicator, seed SeedIndicator, wantOrder int) {
	t.Helper()
	if !ulidPattern.MatchString(indicator.ID) {
		t.Errorf("indicator %q: id %q is not a 26-character Crockford Base32 ULID", indicator.Title, indicator.ID)
	}
	if indicator.Dimension != seed.Dimension {
		t.Errorf("indicator %q: dimension = %q, want %q", indicator.Title, indicator.Dimension, seed.Dimension)
	}
	if indicator.Weight != 1 {
		t.Errorf("indicator %q: weight = %d, want 1", indicator.Title, indicator.Weight)
	}
	if indicator.Demo != seed.Demo {
		t.Errorf("indicator %q: demo = %v, want %v", indicator.Title, indicator.Demo, seed.Demo)
	}
	if indicator.SortOrder != wantOrder {
		t.Errorf("indicator %q: sort_order = %d, want %d", indicator.Title, indicator.SortOrder, wantOrder)
	}
	if indicator.Description != seed.Description {
		t.Errorf("indicator %q: description = %q, want %q", indicator.Title, indicator.Description, seed.Description)
	}
	if indicator.CreatedBy != "system" {
		t.Errorf("indicator %q: created_by = %q, want %q", indicator.Title, indicator.CreatedBy, "system")
	}
	if indicator.CreatedAt.IsZero() || indicator.UpdatedAt.IsZero() {
		t.Errorf("indicator %q: timestamps must be set by the service clock (created_at=%v, updated_at=%v)", indicator.Title, indicator.CreatedAt, indicator.UpdatedAt)
	}
}

// TestSeedInsertsBuiltinIndicators: the first seed run inserts exactly
// the fifteen built-in indicators with the six-dimension coverage and
// the demo distribution of the specification (响应速度 3 / 处置规范性 2 /
// 协同效率 2 / 观众安全 3 / 文物安全 2 / 舆情管控 3; demo=true only on the
// 观众安全, 文物安全 and 舆情管控 rows, 8 rows total), and every seed
// row carries the seed field contract (created_by='system', weight 1,
// per-dimension sort_order 1..N, ULID ids, server timestamps) and the
// canonical content word for word.
func TestSeedInsertsBuiltinIndicators(t *testing.T) {
	service := seedService()
	if err := Seed(context.Background(), service); err != nil {
		t.Fatalf("Seed: %v", err)
	}

	records, total := listAllIndicators(t, service)
	if total != 15 || len(records) != 15 {
		t.Fatalf("after seed: total/records = %d/%d, want 15/15", total, len(records))
	}

	dimensionCounts := map[Dimension]int{}
	demoCount := 0
	for _, indicator := range records {
		dimensionCounts[indicator.Dimension]++
		if indicator.Demo {
			demoCount++
		}
	}
	want := map[Dimension]int{
		DimensionResponseSpeed:    3,
		DimensionDisposalStandard: 2,
		DimensionCoordination:     2,
		DimensionAudienceSafety:   3,
		DimensionRelicSafety:      2,
		DimensionPublicOpinion:    3,
	}
	if len(dimensionCounts) != 6 {
		t.Fatalf("dimensions covered = %d, want 6 (%v)", len(dimensionCounts), dimensionCounts)
	}
	for dimension, count := range want {
		if dimensionCounts[dimension] != count {
			t.Errorf("dimension %q rows = %d, want %d", dimension, dimensionCounts[dimension], count)
		}
	}
	if demoCount != 8 {
		t.Errorf("demo=true rows = %d, want 8", demoCount)
	}

	orderByDimension := map[Dimension]int{}
	for _, seed := range SeedData {
		orderByDimension[seed.Dimension]++
		indicator := findIndicator(t, service, seed.Title)
		assertSeedIndicatorContent(t, service, indicator, seed, orderByDimension[seed.Dimension])
	}
}

// TestSeedIsIdempotent: a second seed run must not produce any
// additional rows; the total stays 15.
func TestSeedIsIdempotent(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("first Seed: %v", err)
	}
	if err := Seed(ctx, service); err != nil {
		t.Fatalf("second Seed: %v", err)
	}

	_, total := listAllIndicators(t, service)
	if total != 15 {
		t.Fatalf("after second seed: total = %d, want 15 (no duplicates)", total)
	}
}

// TestSeedSkipsUserCreatedIndicator: deduplication looks at the current
// title only, not at the origin — a user-created indicator that already
// carries a seed title is skipped like any other existing row, and its
// row is left untouched (the total stays 15).
func TestSeedSkipsUserCreatedIndicator(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	userIndicator, err := service.CreateIndicator(ctx, IndicatorInput{
		Dimension: SeedData[0].Dimension,
		Title:     SeedData[0].Title,
		CreatedBy: "admin",
	})
	if err != nil {
		t.Fatalf("CreateIndicator: %v", err)
	}

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("Seed: %v", err)
	}

	_, total := listAllIndicators(t, service)
	if total != 15 {
		t.Fatalf("after seed: total = %d, want 15 (user-created title skipped)", total)
	}
	kept := findIndicator(t, service, SeedData[0].Title)
	if kept.ID != userIndicator.ID {
		t.Fatalf("kept indicator id = %q, want the user row %q", kept.ID, userIndicator.ID)
	}
	if kept.CreatedBy != "admin" {
		t.Errorf("kept indicator created_by = %q, want %q (user row untouched)", kept.CreatedBy, "admin")
	}
}

// TestSeedReinsertsRenamedIndicator (修改保留案): renaming a seed
// indicator releases the original title, so the next seed run inserts a
// fresh indicator under the seed title (total 15→16); the renamed row
// keeps its new title untouched, and both rows are visible in the
// listing.
func TestSeedReinsertsRenamedIndicator(t *testing.T) {
	service := seedService()
	ctx := context.Background()

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("Seed: %v", err)
	}
	const seedTitle = "预警响应速度"
	const renamedTitle = "自定义指标"
	original := findIndicator(t, service, seedTitle)
	renamed, err := service.UpdateIndicator(ctx, original.ID, IndicatorInput{
		Dimension: original.Dimension,
		Title:     renamedTitle,
		Weight:    intPtr(1),
		CreatedBy: "system",
	})
	if err != nil {
		t.Fatalf("UpdateIndicator: %v", err)
	}
	if renamed.Title != renamedTitle {
		t.Fatalf("renamed indicator title = %q, want %q", renamed.Title, renamedTitle)
	}

	if err := Seed(ctx, service); err != nil {
		t.Fatalf("second Seed: %v", err)
	}

	_, total := listAllIndicators(t, service)
	if total != 16 {
		t.Fatalf("after re-seed: total = %d, want 16 (renamed seed re-inserted)", total)
	}
	kept := findIndicator(t, service, renamedTitle)
	if kept.ID != original.ID {
		t.Fatalf("renamed row id = %q, want %q", kept.ID, original.ID)
	}
	fresh := findIndicator(t, service, seedTitle)
	if fresh.ID == original.ID {
		t.Fatalf("fresh seed row reuses the renamed row id %q", fresh.ID)
	}
	if fresh.SortOrder != 1 {
		t.Errorf("fresh seed row sort_order = %d, want 1 (first free position of the dimension)", fresh.SortOrder)
	}
}
