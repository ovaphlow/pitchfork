package identity_test

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/ovaphlow/pitchfork/service-idp-go/internal/identity"
)

var testSessionSettings = identity.SessionSettings{
	TTL:     time.Hour,
	IdleTTL: 30 * time.Minute,
}

func TestLoginCreatesOpaqueSessionAndLogoutRevokesIt(t *testing.T) {
	databaseConnection := migratedDatabase(t)
	if _, err := identity.EnsureBootstrap(context.Background(), databaseConnection, identity.BootstrapInput{
		Identifier: "admin",
		Password:   "correct horse battery staple",
	}); err != nil {
		t.Fatalf("ensure bootstrap: %v", err)
	}

	login, err := identity.Login(context.Background(), databaseConnection, "ADMIN", "correct horse battery staple", testSessionSettings)
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

	_, err := identity.Login(context.Background(), databaseConnection, "admin", "wrong password", testSessionSettings)
	if !errors.Is(err, identity.ErrInvalidCredentials) {
		t.Fatalf("login error = %v", err)
	}
}
