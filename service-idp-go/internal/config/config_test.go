package config

import (
	"strings"
	"testing"
	"time"
)

func TestLoadFromLookupUsesDefaults(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"IDENTITYD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err != nil {
		t.Fatalf("load defaults: %v", err)
	}

	if configuration.Address != defaultAddress {
		t.Fatalf("address = %q, want %q", configuration.Address, defaultAddress)
	}
	if configuration.DatabasePath != defaultDatabasePath {
		t.Fatalf("database path = %q, want %q", configuration.DatabasePath, defaultDatabasePath)
	}
	if configuration.SessionTTL != defaultSessionTTL {
		t.Fatalf("session TTL = %s, want %s", configuration.SessionTTL, defaultSessionTTL)
	}
	if configuration.SessionIdleTTL != defaultSessionIdleTTL {
		t.Fatalf("session idle TTL = %s, want %s", configuration.SessionIdleTTL, defaultSessionIdleTTL)
	}
	if configuration.LoginThrottleWindow != defaultThrottleWindow {
		t.Fatalf("login throttle window = %s, want %s", configuration.LoginThrottleWindow, defaultThrottleWindow)
	}
	if configuration.LoginThrottleLockout != defaultThrottleLockout {
		t.Fatalf("login throttle lockout = %s, want %s", configuration.LoginThrottleLockout, defaultThrottleLockout)
	}
	if configuration.LoginThrottleFailureLimit != defaultThrottleFailures {
		t.Fatalf("login throttle failure limit = %d, want %d", configuration.LoginThrottleFailureLimit, defaultThrottleFailures)
	}
	if configuration.SecureSessionCookie {
		t.Fatal("secure session cookie = true, want false")
	}
	if configuration.PublicURL != nil {
		t.Fatalf("public URL = %v, want nil", configuration.PublicURL)
	}
	if len(configuration.TrustedProxyPrefixes) != 1 || configuration.TrustedProxyPrefixes[0].String() != defaultTrustedProxyCIDR {
		t.Fatalf("trusted proxy prefixes = %v", configuration.TrustedProxyPrefixes)
	}
	if len(configuration.CorsOrigins) != 2 || configuration.CorsOrigins[0] != "http://localhost:4324" || configuration.CorsOrigins[1] != "http://127.0.0.1:4324" {
		t.Fatalf("cors origins = %v", configuration.CorsOrigins)
	}
}

func TestLoadFromLookupParsesConfiguredValues(t *testing.T) {
	values := map[string]string{
		"IDENTITYD_ADDR":                    "0.0.0.0:9432",
		"IDENTITYD_DATABASE_PATH":           "runtime/identityd.sqlite",
		"IDENTITYD_BOOTSTRAP_IDENTIFIER":    "operator",
		"IDENTITYD_BOOTSTRAP_PASSWORD":      "not-logged",
		"IDENTITYD_SESSION_TTL":             "30m",
		"IDENTITYD_SESSION_IDLE_TTL":        "10m",
		"IDENTITYD_LOGIN_THROTTLE_SECRET":   "test-login-throttle-secret-with-at-least-32-bytes",
		"IDENTITYD_LOGIN_THROTTLE_WINDOW":   "20m",
		"IDENTITYD_LOGIN_THROTTLE_LOCKOUT":  "25m",
		"IDENTITYD_LOGIN_THROTTLE_FAILURES": "7",
		"IDENTITYD_SESSION_SECURE_COOKIE":   "true",
		"IDENTITYD_PUBLIC_URL":              "https://identity.example.internal/crate-api/identity/v1",
		"IDENTITYD_TRUSTED_PROXY_CIDRS":     "127.0.0.1/32, 10.0.0.0/8",
		"IDENTITYD_CORS_ORIGINS":            "http://localhost:4324, http://192.168.0.109:4324",
	}

	configuration, err := LoadFromLookup(valuesLookup(values))
	if err != nil {
		t.Fatalf("load configured values: %v", err)
	}

	if configuration.Address != "0.0.0.0:9432" {
		t.Fatalf("address = %q", configuration.Address)
	}
	if configuration.SessionTTL != 30*time.Minute {
		t.Fatalf("session TTL = %s", configuration.SessionTTL)
	}
	if configuration.SessionIdleTTL != 10*time.Minute {
		t.Fatalf("session idle TTL = %s", configuration.SessionIdleTTL)
	}
	if configuration.LoginThrottleWindow != 20*time.Minute {
		t.Fatalf("login throttle window = %s", configuration.LoginThrottleWindow)
	}
	if configuration.LoginThrottleLockout != 25*time.Minute {
		t.Fatalf("login throttle lockout = %s", configuration.LoginThrottleLockout)
	}
	if configuration.LoginThrottleFailureLimit != 7 {
		t.Fatalf("login throttle failure limit = %d", configuration.LoginThrottleFailureLimit)
	}
	if !configuration.SecureSessionCookie {
		t.Fatal("secure session cookie = false, want true")
	}
	if configuration.PublicURL == nil || configuration.PublicURL.String() != values["IDENTITYD_PUBLIC_URL"] {
		t.Fatalf("public URL = %v", configuration.PublicURL)
	}
	if len(configuration.TrustedProxyPrefixes) != 2 {
		t.Fatalf("trusted proxy count = %d", len(configuration.TrustedProxyPrefixes))
	}
	if len(configuration.CorsOrigins) != 2 || configuration.CorsOrigins[1] != "http://192.168.0.109:4324" {
		t.Fatalf("cors origins = %v", configuration.CorsOrigins)
	}
}

func TestLoadFromLookupRejectsPartialBootstrapConfiguration(t *testing.T) {
	_, err := LoadFromLookup(valuesLookup(map[string]string{
		"IDENTITYD_BOOTSTRAP_IDENTIFIER":  "operator",
		"IDENTITYD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err == nil || !strings.Contains(err.Error(), "must be set together") {
		t.Fatalf("error = %v", err)
	}
}

func TestLoadFromLookupRejectsInvalidTrustedProxyCIDR(t *testing.T) {
	_, err := LoadFromLookup(valuesLookup(map[string]string{
		"IDENTITYD_TRUSTED_PROXY_CIDRS":   "not-a-cidr",
		"IDENTITYD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err == nil || !strings.Contains(err.Error(), "invalid CIDR") {
		t.Fatalf("error = %v", err)
	}
}

func TestLoadFromLookupRejectsIdleTTLAboveSessionTTL(t *testing.T) {
	_, err := LoadFromLookup(valuesLookup(map[string]string{
		"IDENTITYD_SESSION_TTL":           "10m",
		"IDENTITYD_SESSION_IDLE_TTL":      "11m",
		"IDENTITYD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err == nil || !strings.Contains(err.Error(), "must not exceed") {
		t.Fatalf("error = %v", err)
	}
}

func TestLoadFromLookupRejectsMissingOrWeakLoginThrottleSecret(t *testing.T) {
	for _, secret := range []string{"", "too-short"} {
		_, err := LoadFromLookup(valuesLookup(map[string]string{
			"IDENTITYD_LOGIN_THROTTLE_SECRET": secret,
		}))
		if err == nil || !strings.Contains(err.Error(), "IDENTITYD_LOGIN_THROTTLE_SECRET") {
			t.Fatalf("secret %q error = %v", secret, err)
		}
	}
}

func valuesLookup(values map[string]string) func(string) string {
	return func(key string) string {
		return values[key]
	}
}
