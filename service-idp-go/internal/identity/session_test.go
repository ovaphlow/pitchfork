package identity_test

import (
	"context"
	"database/sql"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
)

var testSessionSettings = identity.SessionSettings{
	TTL:     time.Hour,
	IdleTTL: 30 * time.Minute,
}

var testLoginThrottleSettings = identity.LoginThrottleSettings{
	Secret:          []byte("test-login-throttle-secret-with-at-least-32-bytes"),
	FailureLimit:    3,
	Window:          time.Hour,
	LockoutDuration: time.Hour,
}

func TestLoginCreatesOpaqueSessionAndLogoutRevokesIt(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	login, err := loginWithSource(context.Background(), databaseConnection, "ADMIN", "correct horse battery staple", "192.0.2.1")
	if err != nil {
		t.Fatalf("login: %v", err)
	}
	if login.SessionToken == "" || login.CSRFToken == "" || login.ExpiresAt.IsZero() {
		t.Fatalf("incomplete login result: %#v", login)
	}

	session, err := identity.CurrentSession(context.Background(), databaseConnection, login.SessionToken, testSessionSettings)
	if err != nil {
		t.Fatalf("load current session: %v", err)
	}
	if session.Access != "完整" {
		t.Fatalf("session access = %q", session.Access)
	}
	if !identity.VerifyCSRF(session, login.CSRFToken) {
		t.Fatal("correct CSRF token was rejected")
	}
	if identity.VerifyCSRF(session, login.SessionToken) {
		t.Fatal("session token was accepted as CSRF token")
	}

	if err := identity.Logout(context.Background(), databaseConnection, login.SessionToken); err != nil {
		t.Fatalf("logout: %v", err)
	}
	_, err = identity.CurrentSession(context.Background(), databaseConnection, login.SessionToken, testSessionSettings)
	if !errors.Is(err, identity.ErrInvalidSession) {
		t.Fatalf("session after logout error = %v", err)
	}
}

func TestLoginReturnsGenericErrorForWrongPassword(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	_, err := loginWithSource(context.Background(), databaseConnection, "admin", "wrong password", "192.0.2.1")
	if !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("login error = %v", err)
	}
}

func TestLoginThrottleLocksIdentifierAndSourcePair(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	for attempt := 0; attempt < testLoginThrottleSettings.FailureLimit; attempt++ {
		_, err := loginWithSource(context.Background(), databaseConnection, "admin", "wrong password", "192.0.2.1")
		if !errors.Is(err, identity.ErrInvalidCredentials) {
			t.Fatalf("failed login attempt %d error = %v", attempt+1, err)
		}
	}
	_, err := loginWithSource(context.Background(), databaseConnection, "admin", "correct horse battery staple", "192.0.2.1")
	if !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("locked login error = %v", err)
	}
	if _, err := loginWithSource(context.Background(), databaseConnection, "admin", "correct horse battery staple", "198.51.100.2"); err != nil {
		t.Fatalf("login from another source: %v", err)
	}

	var failedCount, identifierHashLength, sourceHashLength int
	var locked bool
	if err := databaseConnection.QueryRow(`
		SELECT failed_count, locked_until IS NOT NULL, length(identifier_hash), length(source_hash)
		FROM identity_login_throttles
	`).Scan(&failedCount, &locked, &identifierHashLength, &sourceHashLength); err != nil {
		t.Fatalf("read login throttle: %v", err)
	}
	if failedCount != testLoginThrottleSettings.FailureLimit || !locked {
		t.Fatalf("throttle = count:%d locked:%t", failedCount, locked)
	}
	if identifierHashLength != 32 || sourceHashLength != 32 {
		t.Fatalf("throttle hash lengths = identifier:%d source:%d", identifierHashLength, sourceHashLength)
	}
}

func TestSuccessfulLoginClearsMatchingThrottle(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	if _, err := loginWithSource(context.Background(), databaseConnection, "admin", "wrong password", "192.0.2.1"); !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("failed login error = %v", err)
	}
	if _, err := loginWithSource(context.Background(), databaseConnection, "admin", "correct horse battery staple", "192.0.2.1"); err != nil {
		t.Fatalf("successful login: %v", err)
	}

	var throttleCount int
	if err := databaseConnection.QueryRow("SELECT COUNT(*) FROM identity_login_throttles").Scan(&throttleCount); err != nil {
		t.Fatalf("count login throttles: %v", err)
	}
	if throttleCount != 0 {
		t.Fatalf("login throttles after success = %d", throttleCount)
	}
}

func loginWithSource(ctx context.Context, databaseConnection *sql.DB, identifierValue string, passwordValue string, sourceAddress string) (identity.LoginResult, error) {
	return identity.Login(ctx, databaseConnection, identity.LoginInput{
		Identifier:    identifierValue,
		Password:      passwordValue,
		SourceAddress: sourceAddress,
	}, testSessionSettings, testLoginThrottleSettings)
}

// TestCurrentSessionReportsPlatformAdminAndDisplayName 锁定计划 042 §4.1 的两个新增字段：
//
//	platform_admin —— 代操作门禁的**唯一依据**，必须是主体事实而不是会话属性；
//	display_name   —— 下游渲染"当前登录用户"用。
//
// 两者都必须随**主体**而非随会话变化，所以本用例在同一张库上对比平台管理员与普通主体。
func TestCurrentSessionReportsPlatformAdminAndDisplayName(t *testing.T) {
	ctx := context.Background()
	databaseConnection := migratedDatabase(t)
	if _, err := identity.EnsureBootstrap(ctx, databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	bootstrapSubjects, err := identity.ListSubjects(ctx, databaseConnection, identity.ListSubjectsInput{Limit: 10, Offset: 0})
	if err != nil {
		t.Fatalf("list subjects: %v", err)
	}
	if len(bootstrapSubjects.Subjects) != 1 {
		t.Fatalf("bootstrap subject count = %d, want 1", len(bootstrapSubjects.Subjects))
	}
	administrator := bootstrapSubjects.Subjects[0]

	administratorLogin, err := loginWithSource(ctx, databaseConnection, "admin", "correct horse battery staple", "192.0.2.1")
	if err != nil {
		t.Fatalf("login administrator: %v", err)
	}
	administratorSession, err := identity.CurrentSession(ctx, databaseConnection, administratorLogin.SessionToken, testSessionSettings)
	if err != nil {
		t.Fatalf("load administrator session: %v", err)
	}
	if !administratorSession.PlatformAdmin {
		t.Fatal("引导出来的主体必须被判为平台管理员，否则代操作门禁没有引导者")
	}
	if administratorSession.DisplayName != "系统管理员" {
		t.Fatalf("administrator display name = %q, want %q", administratorSession.DisplayName, "系统管理员")
	}

	created, err := identity.CreateSubject(ctx, databaseConnection, administrator.ID, identity.CreateSubjectInput{
		DisplayName: "护理员一号",
		Identifier:  "nurse01",
		Password:    "nurse one password",
	})
	if err != nil {
		t.Fatalf("create subject: %v", err)
	}

	staffLogin, err := loginWithSource(ctx, databaseConnection, "nurse01", "nurse one password", "192.0.2.2")
	if err != nil {
		t.Fatalf("login staff subject: %v", err)
	}
	staffSession, err := identity.CurrentSession(ctx, databaseConnection, staffLogin.SessionToken, testSessionSettings)
	if err != nil {
		t.Fatalf("load staff session: %v", err)
	}
	if staffSession.PlatformAdmin {
		t.Fatal("普通主体不得被判为平台管理员（fail-closed：判定不出管理员就不得代操作）")
	}
	if staffSession.DisplayName != "护理员一号" {
		t.Fatalf("staff display name = %q, want %q", staffSession.DisplayName, "护理员一号")
	}
	if staffSession.SubjectID != created.ID {
		t.Fatalf("staff session subject = %q, want %q", staffSession.SubjectID, created.ID)
	}
}
