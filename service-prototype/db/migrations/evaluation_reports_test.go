package migrations

import (
	"strings"
	"testing"
)

// evaluationReportsMigrationSQL reads the evaluation reports migration
// (000025) from the embedded filesystem. The test never touches a
// database.
func evaluationReportsMigrationSQL(t *testing.T) string {
	t.Helper()
	list, err := Parse(Files)
	if err != nil {
		t.Fatalf("parse embedded migrations: %v", err)
	}
	for _, migration := range list {
		if migration.Version == 25 {
			return migration.SQL
		}
	}
	t.Fatal("migration 000025 (evaluation reports) not found")
	return ""
}

// TestEvaluationReportsMigrationStructure asserts the 000025 column
// set, the unique run_id, the run cascade and the JSONB snapshot
// columns: every column of the migration has API behavior (id
// server-generated, run_id unique per run and cascading, overall_score
// REAL for the 1-decimal total, the three JSONB snapshots, created_by
// default ” and server-maintained timestamps).
func TestEvaluationReportsMigrationStructure(t *testing.T) {
	sql := evaluationReportsMigrationSQL(t)

	if !strings.Contains(sql, "CREATE TABLE IF NOT EXISTS evaluation_reports") {
		t.Error("migration misses the evaluation_reports table")
	}
	columns := []string{
		"id TEXT PRIMARY KEY",
		"run_id TEXT NOT NULL UNIQUE REFERENCES drill_runs(id) ON DELETE CASCADE",
		"overall_score REAL NOT NULL DEFAULT 0",
		"dimension_scores JSONB NOT NULL DEFAULT '{}'::jsonb",
		"indicator_scores JSONB NOT NULL DEFAULT '{}'::jsonb",
		"suggestions JSONB NOT NULL DEFAULT '[]'::jsonb",
		"created_by TEXT NOT NULL DEFAULT ''",
		"created_at TIMESTAMPTZ NOT NULL DEFAULT now()",
		"updated_at TIMESTAMPTZ NOT NULL DEFAULT now()",
	}
	for _, column := range columns {
		// 列定义用空格对齐，先折叠空白再比对（与 evaluation_scores 测试同口径）。
		if !strings.Contains(whitespaceCollapsed(sql), whitespaceCollapsed(column)) {
			t.Errorf("migration misses the column definition %q", column)
		}
	}
	// overall_score must be REAL (the 1-decimal rounding rule wins over
	// the INT of the original spec; pinned by the evaluation_reports
	// card).
	if strings.Contains(sql, "overall_score INT") {
		t.Error("overall_score is INT, want REAL (1-decimal totals)")
	}
}
