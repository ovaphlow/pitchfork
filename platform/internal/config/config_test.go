package config

import (
	"strings"
	"testing"
	"time"
)

func TestLoadFromLookupUsesDefaults(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"PLATFORMD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
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
		"PLATFORMD_ADDR":                    "0.0.0.0:9432",
		"PLATFORMD_DATABASE_PATH":           "runtime/platformd.sqlite",
		"PLATFORMD_BOOTSTRAP_IDENTIFIER":    "operator",
		"PLATFORMD_BOOTSTRAP_PASSWORD":      "not-logged",
		"PLATFORMD_SESSION_TTL":             "30m",
		"PLATFORMD_SESSION_IDLE_TTL":        "10m",
		"PLATFORMD_LOGIN_THROTTLE_SECRET":   "test-login-throttle-secret-with-at-least-32-bytes",
		"PLATFORMD_LOGIN_THROTTLE_WINDOW":   "20m",
		"PLATFORMD_LOGIN_THROTTLE_LOCKOUT":  "25m",
		"PLATFORMD_LOGIN_THROTTLE_FAILURES": "7",
		"PLATFORMD_SESSION_SECURE_COOKIE":   "true",
		"PLATFORMD_PUBLIC_URL":              "https://identity.example.internal/crate-api/identity/v1",
		"PLATFORMD_TRUSTED_PROXY_CIDRS":     "127.0.0.1/32, 10.0.0.0/8",
		"PLATFORMD_CORS_ORIGINS":            "http://localhost:4324, http://192.168.0.109:4324",
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
	if configuration.PublicURL == nil || configuration.PublicURL.String() != values["PLATFORMD_PUBLIC_URL"] {
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
		"PLATFORMD_BOOTSTRAP_IDENTIFIER":  "operator",
		"PLATFORMD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err == nil || !strings.Contains(err.Error(), "must be set together") {
		t.Fatalf("error = %v", err)
	}
}

func TestLoadFromLookupRejectsInvalidTrustedProxyCIDR(t *testing.T) {
	_, err := LoadFromLookup(valuesLookup(map[string]string{
		"PLATFORMD_TRUSTED_PROXY_CIDRS":   "not-a-cidr",
		"PLATFORMD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err == nil || !strings.Contains(err.Error(), "invalid CIDR") {
		t.Fatalf("error = %v", err)
	}
}

func TestLoadFromLookupRejectsIdleTTLAboveSessionTTL(t *testing.T) {
	_, err := LoadFromLookup(valuesLookup(map[string]string{
		"PLATFORMD_SESSION_TTL":           "10m",
		"PLATFORMD_SESSION_IDLE_TTL":      "11m",
		"PLATFORMD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}))
	if err == nil || !strings.Contains(err.Error(), "must not exceed") {
		t.Fatalf("error = %v", err)
	}
}

func TestLoadFromLookupRejectsMissingOrWeakLoginThrottleSecret(t *testing.T) {
	for _, secret := range []string{"", "too-short"} {
		_, err := LoadFromLookup(valuesLookup(map[string]string{
			"PLATFORMD_LOGIN_THROTTLE_SECRET": secret,
		}))
		if err == nil || !strings.Contains(err.Error(), "PLATFORMD_LOGIN_THROTTLE_SECRET") {
			t.Fatalf("secret %q error = %v", secret, err)
		}
	}
}

func TestLoadFromLookupParsesEmailLoginLinkConfiguration(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"PLATFORMD_LOGIN_THROTTLE_SECRET":    "test-login-throttle-secret-with-at-least-32-bytes",
		"PLATFORMD_EMAIL_LINK_TTL":           "15m",
		"PLATFORMD_EMAIL_LINK_REDIRECT_URIS": "https://agora.example.com/, http://localhost:4321/",
		"PLATFORMD_EMAIL_LINK_RATE_WINDOW":   "5m",
		"PLATFORMD_EMAIL_LINK_EMAIL_LIMIT":   "2",
		"PLATFORMD_EMAIL_LINK_SOURCE_LIMIT":  "8",
		"PLATFORMD_SMTP_ADDR":                "smtp.example.com:587",
		"PLATFORMD_SMTP_FROM":                "no-reply@example.com",
		"PLATFORMD_SMTP_USERNAME":            "relay",
		"PLATFORMD_SMTP_PASSWORD":            "secret",
		"PLATFORMD_SMTP_TLS":                 "implicit",
		"PLATFORMD_SMTP_TIMEOUT":             "3s",
	}))
	if err != nil {
		t.Fatalf("load email login link configuration: %v", err)
	}

	if configuration.EmailLoginLinkTTL != 15*time.Minute {
		t.Fatalf("email link TTL = %s", configuration.EmailLoginLinkTTL)
	}
	if len(configuration.EmailLoginLinkRedirectURIs) != 2 ||
		configuration.EmailLoginLinkRedirectURIs[0] != "https://agora.example.com/" ||
		configuration.EmailLoginLinkRedirectURIs[1] != "http://localhost:4321/" {
		t.Fatalf("redirect URIs = %v", configuration.EmailLoginLinkRedirectURIs)
	}
	if configuration.EmailLoginLinkRateWindow != 5*time.Minute {
		t.Fatalf("rate window = %s", configuration.EmailLoginLinkRateWindow)
	}
	if configuration.EmailLoginLinkEmailLimit != 2 || configuration.EmailLoginLinkSourceLimit != 8 {
		t.Fatalf("limits = %d/%d", configuration.EmailLoginLinkEmailLimit, configuration.EmailLoginLinkSourceLimit)
	}
	if configuration.SMTPAddress != "smtp.example.com:587" || configuration.SMTPFrom != "no-reply@example.com" {
		t.Fatalf("smtp address/from = %q/%q", configuration.SMTPAddress, configuration.SMTPFrom)
	}
	if configuration.SMTPTLSMode != "implicit" || configuration.SMTPTimeout != 3*time.Second {
		t.Fatalf("smtp TLS/timeout = %q/%s", configuration.SMTPTLSMode, configuration.SMTPTimeout)
	}
}

func TestLoadFromLookupRejectsIncompleteEmailLoginLinkConfiguration(t *testing.T) {
	secret := map[string]string{
		"PLATFORMD_LOGIN_THROTTLE_SECRET": "test-login-throttle-secret-with-at-least-32-bytes",
	}

	tests := []struct {
		name   string
		values map[string]string
		want   string
	}{
		{
			name:   "SMTP without from",
			values: map[string]string{"PLATFORMD_SMTP_ADDR": "smtp.example.com:587", "PLATFORMD_EMAIL_LINK_REDIRECT_URIS": "http://localhost:4321/"},
			want:   "PLATFORMD_SMTP_FROM",
		},
		{
			name:   "SMTP without redirect URIs",
			values: map[string]string{"PLATFORMD_SMTP_ADDR": "smtp.example.com:587", "PLATFORMD_SMTP_FROM": "no-reply@example.com"},
			want:   "PLATFORMD_EMAIL_LINK_REDIRECT_URIS",
		},
		{
			name:   "SMTP artefacts without address",
			values: map[string]string{"PLATFORMD_SMTP_FROM": "no-reply@example.com"},
			want:   "require PLATFORMD_SMTP_ADDR",
		},
		{
			name:   "credentials without password",
			values: map[string]string{"PLATFORMD_SMTP_USERNAME": "relay"},
			want:   "must be set together",
		},
		{
			name:   "credentials without TLS",
			values: map[string]string{"PLATFORMD_SMTP_USERNAME": "relay", "PLATFORMD_SMTP_PASSWORD": "secret", "PLATFORMD_SMTP_TLS": "none"},
			want:   "requires TLS",
		},
		{
			name:   "unknown TLS mode",
			values: map[string]string{"PLATFORMD_SMTP_TLS": "ssl"},
			want:   "PLATFORMD_SMTP_TLS",
		},
		{
			name:   "redirect URI with fragment",
			values: map[string]string{"PLATFORMD_EMAIL_LINK_REDIRECT_URIS": "http://localhost:4321/#fragment"},
			want:   "PLATFORMD_EMAIL_LINK_REDIRECT_URIS",
		},
		{
			name:   "non-http redirect URI",
			values: map[string]string{"PLATFORMD_EMAIL_LINK_REDIRECT_URIS": "ftp://localhost/"},
			want:   "PLATFORMD_EMAIL_LINK_REDIRECT_URIS",
		},
		{
			name:   "email link TTL above one hour",
			values: map[string]string{"PLATFORMD_EMAIL_LINK_TTL": "2h"},
			want:   "PLATFORMD_EMAIL_LINK_TTL",
		},
	}

	for _, test := range tests {
		t.Run(test.name, func(t *testing.T) {
			values := map[string]string{}
			for key, value := range secret {
				values[key] = value
			}
			for key, value := range test.values {
				values[key] = value
			}
			_, err := LoadFromLookup(valuesLookup(values))
			if err == nil || !strings.Contains(err.Error(), test.want) {
				t.Fatalf("error = %v, want it to mention %q", err, test.want)
			}
		})
	}
}

func valuesLookup(values map[string]string) func(string) string {
	return func(key string) string {
		return values[key]
	}
}
