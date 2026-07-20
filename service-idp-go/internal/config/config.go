package config

import (
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"strings"
	"time"
)

const (
	defaultAddress          = "127.0.0.1:8432"
	defaultDatabasePath     = ".data/identityd.sqlite"
	defaultSessionTTL       = 12 * time.Hour
	defaultSessionIdleTTL   = 30 * time.Minute
	defaultTrustedProxyCIDR = "127.0.0.1/32"
)

type Config struct {
	Address              string
	DatabasePath         string
	BootstrapIdentifier  string
	BootstrapPassword    string
	SessionTTL           time.Duration
	SessionIdleTTL       time.Duration
	SecureSessionCookie  bool
	PublicURL            *url.URL
	TrustedProxyPrefixes []netip.Prefix
}

func Load() (Config, error) {
	return LoadFromLookup(os.Getenv)
}

func LoadFromLookup(lookup func(string) string) (Config, error) {
	sessionTTL, err := durationValue(lookup, "IDENTITYD_SESSION_TTL", defaultSessionTTL)
	if err != nil {
		return Config{}, err
	}
	sessionIdleTTL, err := durationValue(lookup, "IDENTITYD_SESSION_IDLE_TTL", defaultSessionIdleTTL)
	if err != nil {
		return Config{}, err
	}
	if sessionIdleTTL > sessionTTL {
		return Config{}, fmt.Errorf("IDENTITYD_SESSION_IDLE_TTL must not exceed IDENTITYD_SESSION_TTL")
	}

	secureCookie, err := booleanValue(lookup, "IDENTITYD_SESSION_SECURE_COOKIE", false)
	if err != nil {
		return Config{}, err
	}

	publicURL, err := publicURLValue(lookup("IDENTITYD_PUBLIC_URL"))
	if err != nil {
		return Config{}, err
	}

	trustedProxyPrefixes, err := trustedProxyValues(lookup("IDENTITYD_TRUSTED_PROXY_CIDRS"))
	if err != nil {
		return Config{}, err
	}

	bootstrapIdentifier := strings.TrimSpace(lookup("IDENTITYD_BOOTSTRAP_IDENTIFIER"))
	bootstrapPassword := lookup("IDENTITYD_BOOTSTRAP_PASSWORD")
	if (bootstrapIdentifier == "") != (bootstrapPassword == "") {
		return Config{}, fmt.Errorf("IDENTITYD_BOOTSTRAP_IDENTIFIER and IDENTITYD_BOOTSTRAP_PASSWORD must be set together")
	}

	return Config{
		Address:              stringValue(lookup, "IDENTITYD_ADDR", defaultAddress),
		DatabasePath:         stringValue(lookup, "IDENTITYD_DATABASE_PATH", defaultDatabasePath),
		BootstrapIdentifier:  bootstrapIdentifier,
		BootstrapPassword:    bootstrapPassword,
		SessionTTL:           sessionTTL,
		SessionIdleTTL:       sessionIdleTTL,
		SecureSessionCookie:  secureCookie,
		PublicURL:            publicURL,
		TrustedProxyPrefixes: trustedProxyPrefixes,
	}, nil
}

func stringValue(lookup func(string) string, key string, fallback string) string {
	if value := strings.TrimSpace(lookup(key)); value != "" {
		return value
	}
	return fallback
}

func durationValue(lookup func(string) string, key string, fallback time.Duration) (time.Duration, error) {
	value := strings.TrimSpace(lookup(key))
	if value == "" {
		return fallback, nil
	}

	duration, err := time.ParseDuration(value)
	if err != nil || duration <= 0 {
		return 0, fmt.Errorf("%s must be a positive Go duration", key)
	}
	return duration, nil
}

func booleanValue(lookup func(string) string, key string, fallback bool) (bool, error) {
	value := strings.TrimSpace(lookup(key))
	if value == "" {
		return fallback, nil
	}

	switch strings.ToLower(value) {
	case "1", "true":
		return true, nil
	case "0", "false":
		return false, nil
	default:
		return false, fmt.Errorf("%s must be true or false", key)
	}
}

func publicURLValue(value string) (*url.URL, error) {
	value = strings.TrimSpace(value)
	if value == "" {
		return nil, nil
	}

	parsedURL, err := url.Parse(value)
	if err != nil || !parsedURL.IsAbs() || parsedURL.Host == "" {
		return nil, fmt.Errorf("IDENTITYD_PUBLIC_URL must be an absolute URL")
	}
	return parsedURL, nil
}

func trustedProxyValues(value string) ([]netip.Prefix, error) {
	if strings.TrimSpace(value) == "" {
		value = defaultTrustedProxyCIDR
	}

	parts := strings.Split(value, ",")
	prefixes := make([]netip.Prefix, 0, len(parts))
	for _, part := range parts {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(part))
		if err != nil {
			return nil, fmt.Errorf("IDENTITYD_TRUSTED_PROXY_CIDRS contains an invalid CIDR: %w", err)
		}
		prefixes = append(prefixes, prefix)
	}
	return prefixes, nil
}
