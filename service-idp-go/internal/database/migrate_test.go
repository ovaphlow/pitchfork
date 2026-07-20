package database_test

import (
	"context"
	"database/sql"
	"io/fs"
	"path/filepath"
	"testing"
	"testing/fstest"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/db/migrations"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database"
)

func TestMigrateAppliesPhaseOneSchemaOnce(t *testing.T) {
	databaseConnection := testDatabase(t)
	context := context.Background()

	firstResult, err := database.Migrate(context, databaseConnection, migrations.Files)
	if err != nil {
		t.Fatalf("first migration: %v", err)
	}
	if firstResult.Applied != 9 {
		t.Fatalf("first applied count = %d, want 9", firstResult.Applied)
	}

	secondResult, err := database.Migrate(context, databaseConnection, migrations.Files)
	if err != nil {
		t.Fatalf("second migration: %v", err)
	}
	if secondResult.Applied != 0 {
		t.Fatalf("second applied count = %d, want 0", secondResult.Applied)
	}

	var tableCount int
	if err := databaseConnection.QueryRowContext(context, `
		SELECT COUNT(*)
		FROM sqlite_master
		WHERE type = 'table'
		  AND name IN (
				'identity_subjects',
				'identity_profiles',
				'identity_identifiers',
				'identity_password_credentials',
				'identity_roles',
				'identity_subject_roles',
				'identity_sessions',
				'identity_login_throttles',
				'identity_audit_events'
		  )
	`).Scan(&tableCount); err != nil {
		t.Fatalf("count Phase 1 tables: %v", err)
	}
	if tableCount != 9 {
		t.Fatalf("Phase 1 table count = %d, want 9", tableCount)
	}
}

func TestOpenSQLiteConfiguresSafetyPragmas(t *testing.T) {
	databaseConnection := testDatabase(t)

	assertPragmaValue(t, databaseConnection, "foreign_keys", "1")
	assertPragmaValue(t, databaseConnection, "busy_timeout", "5000")
	assertPragmaValue(t, databaseConnection, "journal_mode", "wal")
	assertPragmaValue(t, databaseConnection, "synchronous", "2")
	assertPragmaValue(t, databaseConnection, "trusted_schema", "0")
	assertPragmaValue(t, databaseConnection, "wal_autocheckpoint", "1000")
	assertPragmaValue(t, databaseConnection, "journal_size_limit", "67108864")
}

func TestSubjectStateAndMetadataConstraints(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	context := context.Background()
	now := time.Now().UTC()

	if _, err := databaseConnection.ExecContext(context, `
		INSERT INTO identity_subjects(
			id, status, security_version, disabled_at, metadata, created_at, updated_at
		) VALUES (?, '禁用', 1, NULL, '{}', ?, ?)
	`, "01J8Z4Q5W6V7B8N9M0K1L2P3Q4", now, now); err == nil {
		t.Fatal("insert disabled subject without disabled_at succeeded")
	}

	if _, err := databaseConnection.ExecContext(context, `
		INSERT INTO identity_subjects(
			id, status, security_version, disabled_at, metadata, created_at, updated_at
		) VALUES (?, '启用', 1, NULL, 'not-json', ?, ?)
	`, "01J8Z4Q5W6V7B8N9M0K1L2P3Q5", now, now); err == nil {
		t.Fatal("insert subject with invalid metadata succeeded")
	}
}

func TestPrimaryIdentifierUniquePerSubject(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	context := context.Background()
	now := time.Now().UTC()
	subjectID := "01J8Z4Q5W6V7B8N9M0K1L2P3Q4"

	if _, err := databaseConnection.ExecContext(context, `
		INSERT INTO identity_subjects(
			id, status, security_version, disabled_at, metadata, created_at, updated_at
		) VALUES (?, '启用', 1, NULL, '{}', ?, ?)
	`, subjectID, now, now); err != nil {
		t.Fatalf("insert subject: %v", err)
	}

	insertIdentifier := func(identifierID string, normalizedValue string) error {
		_, err := databaseConnection.ExecContext(context, `
			INSERT INTO identity_identifiers(
				id, subject_id, identifier_type, identifier_value, normalized_value,
				identifier_usage, status, created_at, updated_at
			) VALUES (?, ?, '账号', ?, ?, '主登录', '启用', ?, ?)
		`, identifierID, subjectID, normalizedValue, normalizedValue, now, now)
		return err
	}

	if err := insertIdentifier("01J8Z4Q5W6V7B8N9M0K1L2P3Q5", "operator"); err != nil {
		t.Fatalf("insert primary identifier: %v", err)
	}
	if err := insertIdentifier("01J8Z4Q5W6V7B8N9M0K1L2P3Q6", "operator-2"); err == nil {
		t.Fatal("insert second primary identifier succeeded")
	}
}

func TestMigrateRejectsMalformedMigrationName(t *testing.T) {
	databaseConnection := testDatabase(t)
	files := fstest.MapFS{
		"not-a-version.sql": &fstest.MapFile{Data: []byte("SELECT 1;")},
	}

	_, err := database.Migrate(context.Background(), databaseConnection, fs.FS(files))
	if err == nil {
		t.Fatal("migration with malformed name succeeded")
	}
}

func TestMigrateRollsBackFailingMigration(t *testing.T) {
	databaseConnection := testDatabase(t)
	files := fstest.MapFS{
		"000001_broken.sql": &fstest.MapFile{Data: []byte("THIS IS NOT SQL;")},
	}

	_, err := database.Migrate(context.Background(), databaseConnection, fs.FS(files))
	if err == nil {
		t.Fatal("broken migration succeeded")
	}

	var recordedCount int
	if err := databaseConnection.QueryRow("SELECT COUNT(*) FROM schema_migrations").Scan(&recordedCount); err != nil {
		t.Fatalf("count recorded migrations: %v", err)
	}
	if recordedCount != 0 {
		t.Fatalf("recorded migration count = %d, want 0", recordedCount)
	}
}

func testDatabase(t *testing.T) *sql.DB {
	t.Helper()
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
	if err != nil {
		t.Fatalf("open SQLite database: %v", err)
	}
	t.Cleanup(func() {
		if err := databaseConnection.Close(); err != nil {
			t.Errorf("close SQLite database: %v", err)
		}
	})
	return databaseConnection
}

func migratedDatabase(t *testing.T) *sql.DB {
	t.Helper()
	databaseConnection := testDatabase(t)
	if _, err := database.Migrate(context.Background(), databaseConnection, migrations.Files); err != nil {
		t.Fatalf("migrate database: %v", err)
	}
	return databaseConnection
}

func assertPragmaValue(t *testing.T, databaseConnection *sql.DB, name string, expected string) {
	t.Helper()

	var value string
	if err := databaseConnection.QueryRow("PRAGMA " + name).Scan(&value); err != nil {
		t.Fatalf("read PRAGMA %s: %v", name, err)
	}
	if value != expected {
		t.Fatalf("PRAGMA %s = %q, want %q", name, value, expected)
	}
}
