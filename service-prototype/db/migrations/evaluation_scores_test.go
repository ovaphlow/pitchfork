package migrations

import (
	"strings"
	"testing"
)

// evaluationScoresMigrationSQL reads the evaluation scores migration
// (000024) from the embedded filesystem. The test never touches a
// database.
func evaluationScoresMigrationSQL(t *testing.T) string {
	t.Helper()
	list, err := Parse(Files)
	if err != nil {
		t.Fatalf("parse embedded migrations: %v", err)
	}
	for _, migration := range list {
		if migration.Version == 24 {
			return migration.SQL
		}
	}
	t.Fatal("migration 000024 (evaluation scores) not found")
	return ""
}

// TestEvaluationScoresMigrationStructure (字段闭环防漂移): migration
// 000024 must carry every model column with its constraint — the id
// primary key, the run FK with ON DELETE CASCADE, the indicator FK
// without cascade, the score_type CHECK on the three enum values, the
// required rater, the score 0-100 CHECK, the ” defaults for target /
// comment / created_by and the repository timestamps — plus the partial
// unique index mirroring the one-expert-score-per-(run, indicator) rule
// of the service.
func TestEvaluationScoresMigrationStructure(t *testing.T) {
	sql := evaluationScoresMigrationSQL(t)
	// Column padding is cosmetic; collapse whitespace runs so the
	// assertions only depend on the column text itself.
	sql = whitespaceCollapsed(sql)

	columns := []string{
		"id TEXT PRIMARY KEY",
		"run_id TEXT NOT NULL REFERENCES drill_runs(id) ON DELETE CASCADE",
		"indicator_id TEXT NOT NULL REFERENCES evaluation_indicators(id)",
		"score_type TEXT NOT NULL CHECK (score_type IN ('专家评分', '自评', '互评'))",
		"rater TEXT NOT NULL",
		"target TEXT NOT NULL DEFAULT ''",
		"score INT NOT NULL CHECK (score >= 0 AND score <= 100)",
		"comment TEXT NOT NULL DEFAULT ''",
		"created_by TEXT NOT NULL DEFAULT ''",
		"created_at TIMESTAMPTZ NOT NULL DEFAULT now()",
		"updated_at TIMESTAMPTZ NOT NULL DEFAULT now()",
	}
	for _, column := range columns {
		if !strings.Contains(sql, column) {
			t.Errorf("migration misses the column definition %q", column)
		}
	}
	if !strings.Contains(sql, "CREATE TABLE IF NOT EXISTS evaluation_scores") {
		t.Error("migration misses the evaluation_scores table")
	}
	if !strings.Contains(sql, "CREATE UNIQUE INDEX IF NOT EXISTS evaluation_scores_expert_unique") {
		t.Error("migration misses the expert-score partial unique index")
	}
	if !strings.Contains(sql, "WHERE score_type = '专家评分'") {
		t.Error("migration misses the partial index predicate on 专家评分")
	}
}

// whitespaceCollapsed replaces every run of whitespace with a single
// space, so SQL text can be compared independent of formatting.
func whitespaceCollapsed(text string) string {
	return strings.Join(strings.Fields(text), " ")
}
