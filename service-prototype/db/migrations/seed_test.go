package migrations

import (
	"regexp"
	"strconv"
	"strings"
	"testing"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
)

// crockford26 matches a 26-character Crockford Base32 ULID (the alphabet
// omits I, L, O and U). The seed migration embeds fixed ULIDs; every one
// of them must satisfy this pattern.
var crockford26 = regexp.MustCompile(`^[0-9A-HJKMNP-TV-Z]{26}$`)

// seedMigrationSQL reads the seed migration (000016) from the embedded
// filesystem. The test never touches a database.
func seedMigrationSQL(t *testing.T) string {
	t.Helper()
	list, err := Parse(Files)
	if err != nil {
		t.Fatalf("parse embedded migrations: %v", err)
	}
	for _, migration := range list {
		if migration.Version == 16 {
			return migration.SQL
		}
	}
	t.Fatal("migration 000016 (drill seed) not found")
	return ""
}

// seedStatements splits the migration SQL into its statements. The
// statements carry no semicolons inside strings, so a plain split on ";"
// yields one chunk per INSERT.
func seedStatements(sql string) []string {
	var statements []string
	for _, statement := range strings.Split(sql, ";") {
		if strings.TrimSpace(statement) != "" {
			statements = append(statements, statement)
		}
	}
	return statements
}

// TestSeedMigrationMatchesSeedData (防漂移): the seed migration must
// carry exactly the same content as the drills seed function — the four
// scenario names (each exactly once), the four backgrounds, the 21 step
// titles and descriptions (including the full-width-quote string), the
// 15 assessment point titles and created_by='system'. The canonical data
// is the drills.SeedData slice, so any drift between the function path
// and the migration path fails this test.
func TestSeedMigrationMatchesSeedData(t *testing.T) {
	sql := seedMigrationSQL(t)

	if !strings.Contains(sql, "'system'") {
		t.Fatal("migration does not carry created_by='system'")
	}

	for _, scenario := range drills.SeedData {
		if got := strings.Count(sql, "'"+scenario.Name+"'"); got != 1 {
			t.Errorf("scenario name %q appears %d times in the migration, want exactly 1", scenario.Name, got)
		}
		if got := strings.Count(sql, "'"+string(scenario.Category)+"'"); got != 1 {
			t.Errorf("scenario category %q appears %d times in the migration, want exactly 1", scenario.Category, got)
		}
		if got := strings.Count(sql, scenario.Background); got != 1 {
			t.Errorf("scenario background %q appears %d times in the migration, want exactly 1", scenario.Background, got)
		}
		for _, step := range scenario.Steps {
			if got := strings.Count(sql, "'"+step.Title+"'"); got != 1 {
				t.Errorf("step title %q appears %d times in the migration, want exactly 1", step.Title, got)
			}
			if got := strings.Count(sql, step.Description); got != 1 {
				t.Errorf("step description %q appears %d times in the migration, want exactly 1", step.Description, got)
			}
		}
		for _, point := range scenario.Points {
			if got := strings.Count(sql, "'"+point.Title+"'"); got != 1 {
				t.Errorf("point title %q appears %d times in the migration, want exactly 1", point.Title, got)
			}
		}
	}

	// The full-width-quote string of the 信息上报 step must survive the
	// migration verbatim (the canonical data already asserted the exact
	// description; this pin is here to keep the check explicit).
	if !strings.Contains(sql, "同步启动“博物馆—主管部门—属地政府”信息联动机制") {
		t.Fatal("migration lost the full-width-quote string of the 信息上报 step")
	}

	// Exactly 4 scenario inserts, 21 step inserts and 15 point inserts.
	counts := map[string]int{}
	for _, statement := range seedStatements(sql) {
		for _, marker := range []string{
			"INSERT INTO drill_scenarios",
			"INSERT INTO drill_scenario_steps",
			"INSERT INTO drill_assessment_points",
		} {
			if strings.Contains(statement, marker) {
				counts[marker]++
			}
		}
	}
	if counts["INSERT INTO drill_scenarios"] != 4 {
		t.Errorf("scenario INSERT statements = %d, want 4", counts["INSERT INTO drill_scenarios"])
	}
	if counts["INSERT INTO drill_scenario_steps"] != 21 {
		t.Errorf("step INSERT statements = %d, want 21", counts["INSERT INTO drill_scenario_steps"])
	}
	if counts["INSERT INTO drill_assessment_points"] != 15 {
		t.Errorf("point INSERT statements = %d, want 15", counts["INSERT INTO drill_assessment_points"])
	}
}

// TestSeedMigrationULIDs (ID 格式与引用闭合): every single-quoted
// 26-character literal of the migration must be a valid Crockford Base32
// ULID, the migration must embed exactly 76 ULID literals (4 scenario
// ids + 21 step ids + 21 step scenario_ids + 15 point ids + 15 point
// scenario_ids), and every step/point scenario_id must equal one of the
// four fixed scenario ids, so the inserts can never hit a missing
// foreign key at runtime.
func TestSeedMigrationULIDs(t *testing.T) {
	sql := seedMigrationSQL(t)

	// Every quoted 26-character alphanumeric literal must be a valid
	// ULID; a wrong alphabet character (I/L/O/U, lowercase) or a length
	// typo fails here. The pattern scans the string independently, so
	// the empty-string literals of the point rows cannot misalign it.
	var literal26 []string
	for _, match := range regexp.MustCompile(`'([A-Za-z0-9]{26})'`).FindAllStringSubmatch(sql, -1) {
		literal26 = append(literal26, match[1])
		if !crockford26.MatchString(match[1]) {
			t.Errorf("quoted literal %q is not a valid 26-character Crockford Base32 ULID", match[1])
		}
	}
	if len(literal26) != 76 {
		t.Fatalf("ULID literals = %d, want 76 (4 scenario ids + 21 step ids + 21 step scenario_ids + 15 point ids + 15 point scenario_ids)", len(literal26))
	}

	scenarioIDs := map[string]bool{}
	var childScenarioIDs []string
	for _, statement := range seedStatements(sql) {
		literals := regexp.MustCompile(`'([0-9A-HJKMNP-TV-Z]{26})'`).FindAllStringSubmatch(statement, -1)
		switch {
		case strings.Contains(statement, "INSERT INTO drill_scenarios"):
			if len(literals) != 1 {
				t.Errorf("scenario statement carries %d ULID literals, want 1", len(literals))
				continue
			}
			scenarioIDs[literals[0][1]] = true
		case strings.Contains(statement, "INSERT INTO drill_scenario_steps"),
			strings.Contains(statement, "INSERT INTO drill_assessment_points"):
			if len(literals) != 2 {
				t.Errorf("child statement carries %d ULID literals, want 2 (id + scenario_id)", len(literals))
				continue
			}
			childScenarioIDs = append(childScenarioIDs, literals[1][1])
		}
	}
	if len(scenarioIDs) != 4 {
		t.Fatalf("fixed scenario ids = %d, want 4", len(scenarioIDs))
	}
	for _, scenarioID := range childScenarioIDs {
		if !scenarioIDs[scenarioID] {
			t.Errorf("step/point scenario_id %q is not one of the four fixed scenario ids (foreign key would fail at runtime)", scenarioID)
		}
	}
}

// TestSeedMigrationStepOrder (sort_order 1..N): the step inserts must
// appear with the per-scenario sort_order sequences 1..5, 1..5, 1..6,
// 1..5 in document order, matching the canonical seed data.
func TestSeedMigrationStepOrder(t *testing.T) {
	sql := seedMigrationSQL(t)

	var orders []int
	orderPattern := regexp.MustCompile(`, (\d+), '`)
	for _, statement := range seedStatements(sql) {
		if !strings.Contains(statement, "INSERT INTO drill_scenario_steps") {
			continue
		}
		match := orderPattern.FindStringSubmatch(statement)
		if match == nil {
			t.Fatalf("step statement without sort_order: %s", strings.TrimSpace(statement))
		}
		order, err := strconv.Atoi(match[1])
		if err != nil {
			t.Fatalf("sort_order %q is not a number", match[1])
		}
		orders = append(orders, order)
	}

	var expected []int
	for _, scenario := range drills.SeedData {
		for _, step := range scenario.Steps {
			expected = append(expected, step.SortOrder)
		}
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
