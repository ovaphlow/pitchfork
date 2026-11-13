package identity_test

import (
	"context"
	"database/sql"
	"errors"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/platform/internal/identity"
)

// recordingMailer 只记录投递，供领域测试断言「有没有发信」。
type recordingMailer struct {
	mutex    sync.Mutex
	messages []string
}

func (mailer *recordingMailer) SendEmailLoginLink(_ context.Context, to string, link string, _ time.Duration) error {
	mailer.mutex.Lock()
	defer mailer.mutex.Unlock()
	mailer.messages = append(mailer.messages, to+" "+link)
	return nil
}

func (mailer *recordingMailer) sent() []string {
	mailer.mutex.Lock()
	defer mailer.mutex.Unlock()
	return append([]string(nil), mailer.messages...)
}

var testEmailLinkSettings = identity.EmailLoginLinkSettings{
	TTL:          10 * time.Minute,
	RedirectURIs: []string{"http://localhost:4321/"},
	RateWindow:   15 * time.Minute,
	EmailLimit:   3,
	SourceLimit:  10,
}

func requestEmailLoginLink(t *testing.T, databaseConnection *sql.DB, email string, displayName string, mailer identity.EmailLoginLinkMailer) {
	t.Helper()
	err := identity.RequestEmailLoginLink(context.Background(), databaseConnection, identity.RequestEmailLoginLinkInput{
		Email:         email,
		DisplayName:   displayName,
		RedirectURI:   "http://localhost:4321/",
		SourceAddress: "192.0.2.1",
	}, testEmailLinkSettings, testLoginThrottleSettings.Secret, mailer)
	if err != nil {
		t.Fatalf("request email login link: %v", err)
	}
}

// TestTemporaryPasswordEnablesLoginForPasswordlessSubject 锁定邮件链接账号的管理员兜底：
// 无密码主体可以被设置临时密码、用临时密码登录（仅改密），改密后拿到完整会话。
func TestTemporaryPasswordEnablesLoginForPasswordlessSubject(t *testing.T) {
	ctx := context.Background()
	databaseConnection := migratedDatabase(t)
	mailer := &recordingMailer{}

	requestEmailLoginLink(t, databaseConnection, "link.user@example.com", "链接用户", mailer)
	if len(mailer.sent()) != 1 {
		t.Fatalf("mails sent = %d, want 1", len(mailer.sent()))
	}

	var subjectID string
	if err := databaseConnection.QueryRow("SELECT subject_id FROM identity_email_login_links").Scan(&subjectID); err != nil {
		t.Fatalf("read created subject id: %v", err)
	}

	if _, err := loginWithSource(ctx, databaseConnection, "link.user@example.com", "irrelevant", "192.0.2.1"); !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("passwordless login error = %v, want ErrInvalidCredentials", err)
	}

	const temporaryPassword = "temporary password for link user"
	if err := identity.SetTemporaryPassword(ctx, databaseConnection, "", subjectID, temporaryPassword); err != nil {
		t.Fatalf("set temporary password: %v", err)
	}

	temporaryLogin, err := loginWithSource(ctx, databaseConnection, "link.user@example.com", temporaryPassword, "192.0.2.1")
	if err != nil {
		t.Fatalf("login with temporary password: %v", err)
	}
	if temporaryLogin.Access != identity.SessionAccessPasswordOnly {
		t.Fatalf("temporary login access = %q, want %q", temporaryLogin.Access, identity.SessionAccessPasswordOnly)
	}

	const newPassword = "a brand new password for link user"
	if err := identity.ChangePassword(ctx, databaseConnection, subjectID, identity.ChangePasswordInput{
		CurrentPassword: temporaryPassword,
		NewPassword:     newPassword,
	}); err != nil {
		t.Fatalf("change password: %v", err)
	}

	fullLogin, err := loginWithSource(ctx, databaseConnection, "link.user@example.com", newPassword, "192.0.2.1")
	if err != nil {
		t.Fatalf("login with new password: %v", err)
	}
	if fullLogin.Access != identity.SessionAccessFull {
		t.Fatalf("full login access = %q, want %q", fullLogin.Access, identity.SessionAccessFull)
	}
	if _, err := loginWithSource(ctx, databaseConnection, "link.user@example.com", temporaryPassword, "192.0.2.1"); !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("temporary password after change error = %v, want ErrInvalidCredentials", err)
	}
}

// TestConsumeEmailLoginLinkIsSingleUseAndVerifiesEmail 覆盖兑换路径的领域约束：
// 一次性、签发完整会话、把邮箱标识标记为已验证。
func TestConsumeEmailLoginLinkIsSingleUseAndVerifiesEmail(t *testing.T) {
	ctx := context.Background()
	databaseConnection := migratedDatabase(t)
	mailer := &recordingMailer{}

	requestEmailLoginLink(t, databaseConnection, "single.use@example.com", "一次性用户", mailer)
	sent := mailer.sent()
	if len(sent) != 1 {
		t.Fatalf("mails sent = %d, want 1", len(sent))
	}
	parsedToken := tokenFromSentMail(t, sent[0])

	login, err := identity.ConsumeEmailLoginLink(ctx, databaseConnection, parsedToken, testSessionSettings, testLoginThrottleSettings.Secret, "192.0.2.1")
	if err != nil {
		t.Fatalf("consume email login link: %v", err)
	}
	if login.Access != identity.SessionAccessFull {
		t.Fatalf("access = %q, want %q", login.Access, identity.SessionAccessFull)
	}
	session, err := identity.CurrentSession(ctx, databaseConnection, login.SessionToken, testSessionSettings)
	if err != nil {
		t.Fatalf("current session: %v", err)
	}

	var subjectID string
	if err := databaseConnection.QueryRow("SELECT subject_id FROM identity_email_login_links").Scan(&subjectID); err != nil {
		t.Fatalf("read link subject: %v", err)
	}
	if session.SubjectID != subjectID {
		t.Errorf("session subject = %q, want %q", session.SubjectID, subjectID)
	}

	if _, err := identity.ConsumeEmailLoginLink(ctx, databaseConnection, parsedToken, testSessionSettings, testLoginThrottleSettings.Secret, "192.0.2.1"); !errors.Is(err, identity.ErrInvalidEmailLoginLink) {
		t.Fatalf("second consume error = %v, want ErrInvalidEmailLoginLink", err)
	}
	if _, err := identity.ConsumeEmailLoginLink(ctx, databaseConnection, "invalid-token", testSessionSettings, testLoginThrottleSettings.Secret, "192.0.2.1"); !errors.Is(err, identity.ErrInvalidEmailLoginLink) {
		t.Fatalf("malformed token error = %v, want ErrInvalidEmailLoginLink", err)
	}
}

func tokenFromSentMail(t *testing.T, message string) string {
	t.Helper()
	index := strings.LastIndex(message, " ")
	if index < 0 {
		t.Fatalf("malformed recorded mail %q", message)
	}
	parsed, err := url.Parse(message[index+1:])
	if err != nil {
		t.Fatalf("parse mail link: %v", err)
	}
	token := parsed.Query().Get("email_token")
	if token == "" {
		t.Fatalf("mail link %q has no email_token", message[index+1:])
	}
	return token
}
