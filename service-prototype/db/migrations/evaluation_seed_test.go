package migrations

import (
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
)

// evaluationSeedMigrationSQL reads the seed migration (000023) from the
// embedded filesystem. The test never touches a database.
func evaluationSeedMigrationSQL(t *testing.T) string {
	t.Helper()
	list, err := Parse(Files)
	if err != nil {
		t.Fatalf("parse embedded migrations: %v", err)
	}
	for _, migration := range list {
		if migration.Version == 23 {
			return migration.SQL
		}
	}
	t.Fatal("migration 000023 (evaluation indicator seed) not found")
	return ""
}

// evaluationSeedStatements splits the migration SQL into its statements
// (the statements carry no semicolons inside strings, so a plain split
// on ";" yields one chunk per INSERT plus the CREATE TABLE chunk).
func evaluationSeedStatements(sql string) []string {
	var statements []string
	for _, statement := range strings.Split(sql, ";") {
		if strings.TrimSpace(statement) != "" {
			statements = append(statements, statement)
		}
	}
	return statements
}

// TestEvaluationSeedMigrationMatchesSeedData (防漂移): the seed migration
// must carry exactly the same content as the evaluation seed function —
// the fifteen indicator titles (each exactly once), descriptions, demo
// flags, per-dimension sort_order sequences and created_by='system'.
// The canonical data is the evaluation.SeedData slice, so any drift
// between the function path and the migration path fails this test.
// Each INSERT statement carries five quoted literals (id, dimension,
// title, description, created_by) and the unquoted weight/demo/
// sort_order values in the values row.
func TestEvaluationSeedMigrationMatchesSeedData(t *testing.T) {
	sql := evaluationSeedMigrationSQL(t)

	if !strings.Contains(sql, "'system'") {
		t.Fatal("migration does not carry created_by='system'")
	}

	// Per-title expected sort_order (1..N within each dimension, in
	// SeedData order) and demo flag.
	expectedOrder := make(map[string]int, len(evaluation.SeedData))
	expectedDemo := make(map[string]bool, len(evaluation.SeedData))
	orderByDimension := map[evaluation.Dimension]int{}
	for _, seed := range evaluation.SeedData {
		orderByDimension[seed.Dimension]++
		expectedOrder[seed.Title] = orderByDimension[seed.Dimension]
		expectedDemo[seed.Title] = seed.Demo
	}

	insertCount := 0
	for _, statement := range evaluationSeedStatements(sql) {
		if !strings.Contains(statement, "INSERT INTO evaluation_indicators") {
			continue
		}
		insertCount++
		literals := regexp.MustCompile(`'([^']*)'`).FindAllStringSubmatch(statement, -1)
		if len(literals) != 5 {
			t.Fatalf("indicator statement carries %d quoted literals, want 5 (id, dimension, title, description, created_by): %s", len(literals), strings.TrimSpace(statement))
		}
		id, dimension, title, description, createdBy := literals[0][1], literals[1][1], literals[2][1], literals[3][1], literals[4][1]
		if !crockford26.MatchString(id) {
			t.Errorf("indicator id %q is not a valid 26-character Crockford Base32 ULID", id)
		}
		seed, ok := expectedDemo[title]
		if !ok {
			t.Errorf("indicator title %q is not part of the canonical seed data", title)
			continue
		}
		if evaluation.Dimension(dimension) != seedDimension(t, title) {
			t.Errorf("indicator %q: dimension = %q, want %q", title, dimension, seedDimension(t, title))
		}
		if description != seedDescription(t, title) {
			t.Errorf("indicator %q: description = %q, want %q", title, description, seedDescription(t, title))
		}
		if createdBy != "system" {
			t.Errorf("indicator %q: created_by = %q, want system", title, createdBy)
		}
		match := regexp.MustCompile(`, (\d+), (true|false), (\d+), '`).FindStringSubmatch(statement)
		if match == nil {
			t.Fatalf("indicator statement without weight/demo/sort_order: %s", strings.TrimSpace(statement))
		}
		if weight, _ := strconv.Atoi(match[1]); weight != 1 {
			t.Errorf("indicator %q: weight = %d, want 1", title, weight)
		}
		if demo := match[2] == "true"; demo != seed {
			t.Errorf("indicator %q: demo = %v, want %v", title, demo, seed)
		}
		if order, _ := strconv.Atoi(match[3]); order != expectedOrder[title] {
			t.Errorf("indicator %q: sort_order = %d, want %d", title, order, expectedOrder[title])
		}
	}
	if insertCount != 15 {
		t.Fatalf("indicator INSERT statements = %d, want 15", insertCount)
	}
}

// seedDimension and seedDescription look up the canonical seed row by
// title (the anti-drift helpers keep the statement loop readable).
func seedDimension(t *testing.T, title string) evaluation.Dimension {
	t.Helper()
	for _, seed := range evaluation.SeedData {
		if seed.Title == title {
			return seed.Dimension
		}
	}
	t.Fatalf("seed row %q not found", title)
	return ""
}

func seedDescription(t *testing.T, title string) string {
	t.Helper()
	for _, seed := range evaluation.SeedData {
		if seed.Title == title {
			return seed.Description
		}
	}
	t.Fatalf("seed row %q not found", title)
	return ""
}

// TestEvaluationSeedMigrationCoverage (维度与 demo 分布): the migration
// must carry the six-dimension coverage and the demo distribution of
// the specification — 响应速度 3 / 处置规范性 2 / 协同效率 2 / 观众安全 3 /
// 文物安全 2 / 舆情管控 3, with demo=true on the 观众安全, 文物安全 and
// 舆情管控 rows only (8 rows), and exactly 15 fixed ULID literals.
func TestEvaluationSeedMigrationCoverage(t *testing.T) {
	sql := evaluationSeedMigrationSQL(t)

	dimensionCounts := map[string]int{}
	demoCount := 0
	for _, statement := range evaluationSeedStatements(sql) {
		if !strings.Contains(statement, "INSERT INTO evaluation_indicators") {
			continue
		}
		literals := regexp.MustCompile(`'([^']*)'`).FindAllStringSubmatch(statement, -1)
		if len(literals) != 5 {
			t.Fatalf("indicator statement carries %d quoted literals, want 5", len(literals))
		}
		dimensionCounts[literals[1][1]]++
		if regexp.MustCompile(`, (\d+), true, (\d+), '`).MatchString(statement) {
			demoCount++
		}
	}
	want := map[string]int{"响应速度": 3, "处置规范性": 2, "协同效率": 2, "观众安全": 3, "文物安全": 2, "舆情管控": 3}
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

	// Every single-quoted 26-character literal must be a valid Crockford
	// Base32 ULID and there must be exactly 15 of them (one per row; the
	// CREATE TABLE statement carries none).
	var literal26 []string
	for _, match := range regexp.MustCompile(`'([A-Za-z0-9]{26})'`).FindAllStringSubmatch(sql, -1) {
		literal26 = append(literal26, match[1])
		if !crockford26.MatchString(match[1]) {
			t.Errorf("quoted literal %q is not a valid 26-character Crockford Base32 ULID", match[1])
		}
	}
	if len(literal26) != 15 {
		t.Fatalf("ULID literals = %d, want 15 (one per seeded indicator)", len(literal26))
	}
}

// TestEvaluationSeedMigrationSortOrder (sort_order 1..N): the indicator
// inserts must appear with the per-dimension sort_order sequences 1..3,
// 1..2, 1..2, 1..3, 1..2, 1..3 in document order, matching the
// canonical seed data.
func TestEvaluationSeedMigrationSortOrder(t *testing.T) {
	sql := evaluationSeedMigrationSQL(t)

	var orders []int
	orderPattern := regexp.MustCompile(`, \d+, (?:true|false), (\d+), '`)
	for _, statement := range evaluationSeedStatements(sql) {
		if !strings.Contains(statement, "INSERT INTO evaluation_indicators") {
			continue
		}
		match := orderPattern.FindStringSubmatch(statement)
		if match == nil {
			t.Fatalf("indicator statement without sort_order: %s", strings.TrimSpace(statement))
		}
		order, err := strconv.Atoi(match[1])
		if err != nil {
			t.Fatalf("sort_order %q is not a number", match[1])
		}
		orders = append(orders, order)
	}

	var expected []int
	orderByDimension := map[evaluation.Dimension]int{}
	for _, seed := range evaluation.SeedData {
		orderByDimension[seed.Dimension]++
		expected = append(expected, orderByDimension[seed.Dimension])
	}
	if len(orders) != len(expected) {
		t.Fatalf("sort_order values = %d, want %d", len(orders), len(expected))
	}
	for index := range expected {
		if orders[index] != expected[index] {
			t.Fatalf("sort_order sequence diverges at %d: got %v, want %v", index, orders, expected)
		}
	}
}
