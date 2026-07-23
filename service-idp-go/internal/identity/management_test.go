package identity_test

import (
	"context"
	"database/sql"
	"errors"
	"testing"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
)

func TestCreateListAndDisableSubject(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	administrator := bootstrapAdministrator(t, databaseConnection)

	created, err := identity.CreateSubject(context.Background(), databaseConnection, administrator.ID, identity.CreateSubjectInput{
		DisplayName: "张三",
		Identifier:  "ZhangSan",
		Password:    "a sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create subject: %v", err)
	}
	if created.Status != "启用" || created.Identifier != "zhangsan" || len(created.Roles) != 0 {
		t.Fatalf("created subject = %#v", created)
	}

	listed, err := identity.ListSubjects(context.Background(), databaseConnection, identity.ListSubjectsInput{Limit: 20})
	if err != nil {
		t.Fatalf("list subjects: %v", err)
	}
	if listed.Total != 2 || len(listed.Subjects) != 2 {
		t.Fatalf("listed subjects = %#v", listed)
	}

	login, err := loginWithSource(context.Background(), databaseConnection, "zhangsan", "a sufficiently long password", "192.0.2.1")
	if err != nil {
		t.Fatalf("login created subject: %v", err)
	}
	disabled, err := identity.DisableSubject(context.Background(), databaseConnection, administrator.ID, created.ID)
	if err != nil {
		t.Fatalf("disable subject: %v", err)
	}
	if disabled.Status != "禁用" || disabled.SecurityVersion != created.SecurityVersion+1 {
		t.Fatalf("disabled subject = %#v", disabled)
	}
	if _, err := identity.CurrentSession(context.Background(), databaseConnection, login.SessionToken, testSessionSettings); !errors.Is(err, identity.ErrInvalidSession) {
		t.Fatalf("disabled subject session error = %v", err)
	}

	var disabledCount, revokedSessionCount, disabledAuditCount int
	if err := databaseConnection.QueryRow(`
		SELECT
			(SELECT COUNT(*) FROM identity_subjects WHERE id = ? AND status = '禁用'),
			(SELECT COUNT(*) FROM identity_sessions WHERE subject_id = ? AND revoked_reason = '主体禁用'),
			(SELECT COUNT(*) FROM identity_audit_events WHERE event_action = '主体状态变更' AND outcome = '成功' AND actor_subject_id = ? AND target_subject_id = ?)
	`, created.ID, created.ID, administrator.ID, created.ID).Scan(&disabledCount, &revokedSessionCount, &disabledAuditCount); err != nil {
		t.Fatalf("read disabled subject state: %v", err)
	}
	if disabledCount != 1 || revokedSessionCount != 1 || disabledAuditCount != 1 {
		t.Fatalf("disabled state = subjects:%d sessions:%d audits:%d", disabledCount, revokedSessionCount, disabledAuditCount)
	}

	if _, err := identity.DisableSubject(context.Background(), databaseConnection, administrator.ID, created.ID); err != nil {
		t.Fatalf("disable existing disabled subject: %v", err)
	}
	if err := databaseConnection.QueryRow(`SELECT COUNT(*) FROM identity_audit_events WHERE event_action = '主体状态变更'`).Scan(&disabledAuditCount); err != nil {
		t.Fatalf("count disable audits: %v", err)
	}
	if disabledAuditCount != 1 {
		t.Fatalf("disable audit count after idempotent request = %d", disabledAuditCount)
	}
}

func TestCreateSubjectRejectsDuplicateAccountIdentifier(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	administrator := bootstrapAdministrator(t, databaseConnection)

	_, err := identity.CreateSubject(context.Background(), databaseConnection, administrator.ID, identity.CreateSubjectInput{
		DisplayName: "另一个管理员",
		Identifier:  "ADMIN",
		Password:    "a sufficiently long password",
	})
	if !errors.Is(err, identity.ErrIdentifierAlreadyExists) {
		t.Fatalf("duplicate account identifier error = %v", err)
	}
}

func TestDisableSubjectProtectsLastEnabledAdministrator(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	administrator := bootstrapAdministrator(t, databaseConnection)
	login, err := loginWithSource(context.Background(), databaseConnection, "admin", "correct horse battery staple", "192.0.2.1")
	if err != nil {
		t.Fatalf("login administrator: %v", err)
	}

	_, err = identity.DisableSubject(context.Background(), databaseConnection, administrator.ID, administrator.ID)
	if !errors.Is(err, identity.ErrLastAdministrator) {
		t.Fatalf("disable last administrator error = %v", err)
	}
	if _, err := identity.CurrentSession(context.Background(), databaseConnection, login.SessionToken, testSessionSettings); err != nil {
		t.Fatalf("last administrator session after rejected disable: %v", err)
	}

	var enabledAdministratorCount, disableAuditCount int
	if err := databaseConnection.QueryRow(`
		SELECT
			(SELECT COUNT(*) FROM identity_subjects WHERE id = ? AND status = '启用'),
			(SELECT COUNT(*) FROM identity_audit_events WHERE event_action = '主体状态变更')
	`, administrator.ID).Scan(&enabledAdministratorCount, &disableAuditCount); err != nil {
		t.Fatalf("read last administrator state: %v", err)
	}
	if enabledAdministratorCount != 1 || disableAuditCount != 0 {
		t.Fatalf("last administrator state = enabled:%d audits:%d", enabledAdministratorCount, disableAuditCount)
	}
}

func TestTemporaryPasswordRequiresChangeAndPasswordChangeRevokesSessions(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	administrator := bootstrapAdministrator(t, databaseConnection)
	created, err := identity.CreateSubject(context.Background(), databaseConnection, administrator.ID, identity.CreateSubjectInput{
		DisplayName: "张三",
		Identifier:  "zhangsan",
		Password:    "original sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create subject: %v", err)
	}

	originalLogin, err := loginWithSource(context.Background(), databaseConnection, "zhangsan", "original sufficiently long password", "192.0.2.1")
	if err != nil {
		t.Fatalf("login with original password: %v", err)
	}
	if err := identity.SetTemporaryPassword(context.Background(), databaseConnection, administrator.ID, created.ID, "temporary sufficiently long password"); err != nil {
		t.Fatalf("set temporary password: %v", err)
	}
	if _, err := identity.CurrentSession(context.Background(), databaseConnection, originalLogin.SessionToken, testSessionSettings); !errors.Is(err, identity.ErrInvalidSession) {
		t.Fatalf("original session after temporary password error = %v", err)
	}
	if _, err := loginWithSource(context.Background(), databaseConnection, "zhangsan", "original sufficiently long password", "192.0.2.1"); !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("login with replaced password error = %v", err)
	}

	temporaryLogin, err := loginWithSource(context.Background(), databaseConnection, "zhangsan", "temporary sufficiently long password", "192.0.2.1")
	if err != nil {
		t.Fatalf("login with temporary password: %v", err)
	}
	temporarySession, err := identity.CurrentSession(context.Background(), databaseConnection, temporaryLogin.SessionToken, testSessionSettings)
	if err != nil {
		t.Fatalf("load temporary session: %v", err)
	}
	if temporarySession.Access != "仅改密" {
		t.Fatalf("temporary session access = %q", temporarySession.Access)
	}

	if err := identity.ChangePassword(context.Background(), databaseConnection, created.ID, identity.ChangePasswordInput{
		CurrentPassword: "temporary sufficiently long password",
		NewPassword:     "replacement sufficiently long password",
	}); err != nil {
		t.Fatalf("change password: %v", err)
	}
	if _, err := identity.CurrentSession(context.Background(), databaseConnection, temporaryLogin.SessionToken, testSessionSettings); !errors.Is(err, identity.ErrInvalidSession) {
		t.Fatalf("temporary session after password change error = %v", err)
	}

	var credentialStatus string
	var passwordRevision, securityVersion, revokedSessions, temporaryPasswordAudits, passwordChangeAudits int
	if err := databaseConnection.QueryRow(`
		SELECT
			(SELECT credential_status FROM identity_password_credentials WHERE subject_id = ?),
			(SELECT password_revision FROM identity_password_credentials WHERE subject_id = ?),
			(SELECT security_version FROM identity_subjects WHERE id = ?),
			(SELECT COUNT(*) FROM identity_sessions WHERE subject_id = ? AND revoked_reason = '凭据变更'),
			(SELECT COUNT(*) FROM identity_audit_events WHERE event_action = '凭据变更' AND actor_subject_id = ? AND target_subject_id = ? AND metadata = '{"credential_status":"需更新"}'),
			(SELECT COUNT(*) FROM identity_audit_events WHERE event_action = '凭据变更' AND actor_subject_id = ? AND target_subject_id = ? AND metadata = '{"credential_status":"有效"}')
	`, created.ID, created.ID, created.ID, created.ID, administrator.ID, created.ID, created.ID, created.ID).Scan(
		&credentialStatus,
		&passwordRevision,
		&securityVersion,
		&revokedSessions,
		&temporaryPasswordAudits,
		&passwordChangeAudits,
	); err != nil {
		t.Fatalf("read password change state: %v", err)
	}
	if credentialStatus != "有效" || passwordRevision != 3 || securityVersion != 3 || revokedSessions != 2 || temporaryPasswordAudits != 1 || passwordChangeAudits != 1 {
		t.Fatalf("password change state = status:%q revision:%d security:%d revoked:%d temporary_audits:%d change_audits:%d", credentialStatus, passwordRevision, securityVersion, revokedSessions, temporaryPasswordAudits, passwordChangeAudits)
	}

	changedLogin, err := loginWithSource(context.Background(), databaseConnection, "zhangsan", "replacement sufficiently long password", "192.0.2.1")
	if err != nil {
		t.Fatalf("login with replacement password: %v", err)
	}
	changedSession, err := identity.CurrentSession(context.Background(), databaseConnection, changedLogin.SessionToken, testSessionSettings)
	if err != nil {
		t.Fatalf("load changed-password session: %v", err)
	}
	if changedSession.Access != "完整" {
		t.Fatalf("changed-password session access = %q", changedSession.Access)
	}
}

func TestChangePasswordRejectsIncorrectCurrentPassword(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	administrator := bootstrapAdministrator(t, databaseConnection)
	created, err := identity.CreateSubject(context.Background(), databaseConnection, administrator.ID, identity.CreateSubjectInput{
		DisplayName: "张三",
		Identifier:  "zhangsan",
		Password:    "original sufficiently long password",
	})
	if err != nil {
		t.Fatalf("create subject: %v", err)
	}

	err = identity.ChangePassword(context.Background(), databaseConnection, created.ID, identity.ChangePasswordInput{
		CurrentPassword: "incorrect sufficiently long password",
		NewPassword:     "replacement sufficiently long password",
	})
	if !errors.Is(err, identity.ErrIncorrectPassword) {
		t.Fatalf("change password error = %v", err)
	}

	login, err := loginWithSource(context.Background(), databaseConnection, "zhangsan", "original sufficiently long password", "192.0.2.1")
	if err != nil {
		t.Fatalf("login with unchanged password: %v", err)
	}
	if _, err := identity.CurrentSession(context.Background(), databaseConnection, login.SessionToken, testSessionSettings); err != nil {
		t.Fatalf("load unchanged-password session: %v", err)
	}
}

func bootstrapAdministrator(t *testing.T, databaseConnection *sql.DB) identity.Subject {
	t.Helper()
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}
	listed, err := identity.ListSubjects(context.Background(), databaseConnection, identity.ListSubjectsInput{Limit: 1})
	if err != nil {
		t.Fatalf("list bootstrap administrator: %v", err)
	}
	if listed.Total != 1 || len(listed.Subjects) != 1 {
		t.Fatalf("bootstrap subjects = %#v", listed)
	}
	return listed.Subjects[0]
}
