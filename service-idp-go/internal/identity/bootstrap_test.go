package identity_test

import (
	"context"
	"database/sql"
	"path/filepath"
	"testing"

	"github.com/ovaphlow/pitchfork/service-idp-go/db/migrations"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/database"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
	"github.com/ovaphlow/pitchfork/service-idp-go/internal/password"
)

func TestEnsureBootstrapCreatesAdministratorAndAuditTrail(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	created, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "Admin",
		Password:   "correct horse battery staple",
	})
	if err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}
	if !created {
		t.Fatal("bootstrap was not created")
	}

	var subjectCount, roleCount, grantCount, auditCount int
	if err := databaseConnection.QueryRow(`
		SELECT
			(SELECT COUNT(*) FROM identity_subjects),
			(SELECT COUNT(*) FROM identity_roles),
			(SELECT COUNT(*) FROM identity_subject_roles),
			(SELECT COUNT(*) FROM identity_audit_events)
	`).Scan(&subjectCount, &roleCount, &grantCount, &auditCount); err != nil {
		t.Fatalf("count bootstrap records: %v", err)
	}
	if subjectCount != 1 || roleCount != 2 || grantCount != 1 || auditCount != 1 {
		t.Fatalf("bootstrap counts = subjects:%d roles:%d grants:%d audits:%d", subjectCount, roleCount, grantCount, auditCount)
	}

	var normalizedIdentifier, passwordHash, roleCode string
	if err := databaseConnection.QueryRow(`
		SELECT identifier.normalized_value, credential.password_hash, role.role_code
		FROM identity_identifiers AS identifier
		JOIN identity_password_credentials AS credential ON credential.subject_id = identifier.subject_id
		JOIN identity_subject_roles AS grant ON grant.subject_id = identifier.subject_id
		JOIN identity_roles AS role ON role.id = grant.role_id
	`).Scan(&normalizedIdentifier, &passwordHash, &roleCode); err != nil {
		t.Fatalf("read bootstrap identity: %v", err)
	}
	if normalizedIdentifier != "admin" {
		t.Fatalf("normalized identifier = %q, want admin", normalizedIdentifier)
	}
	if roleCode != "identity.admin" {
		t.Fatalf("role code = %q", roleCode)
	}
	matched, err := password.Verify("correct horse battery staple", passwordHash)
	if err != nil || !matched {
		t.Fatalf("bootstrap password verification = %t, error = %v", matched, err)
	}

	created, err = identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "other-admin",
		Password:   "a second sufficiently long password",
	})
	if err != nil {
		t.Fatalf("repeat bootstrap: %v", err)
	}
	if created {
		t.Fatal("repeat bootstrap created another identity")
	}
}

func TestEnsureBootstrapRequiresCredentialsForEmptyDatabase(t *testing.T) {
	_, err := identity.EnsureBootstrap(context.Background(), migratedDatabase(t), identity.BootstrapInput{})
	if err == nil {
		t.Fatal("empty database accepted missing bootstrap credentials")
	}
}

func migratedDatabase(t *testing.T) *sql.DB {
	t.Helper()
	databaseConnection, err := database.OpenSQLite(context.Background(), filepath.Join(t.TempDir(), "identityd.sqlite"))
	if err != nil {
		t.Fatalf("open SQLite database: %v", err)
	}
	t.Cleanup(func() {
		databaseConnection.Close()
	})
	if _, err := database.Migrate(context.Background(), databaseConnection, migrations.Files); err != nil {
		t.Fatalf("migrate database: %v", err)
	}
	return databaseConnection
}
