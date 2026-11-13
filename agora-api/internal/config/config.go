package config

import (
	"errors"
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/joho/godotenv"
)

// Config is the process configuration loaded from the environment.
type Config struct {
	HTTPAddr         string
	DatabaseURL      string
	AllowedOrigins   []string
	LogLevel         string
	LogFile          string
	DBMaxConns       int32
	DBConnectTimeout time.Duration
	ReadinessTimeout time.Duration
	ShutdownTimeout  time.Duration
	IdentityBaseURL  string
	NexusBaseURL     string
	AuthHTTPTimeout  time.Duration
	FilesHTTPTimeout time.Duration
	TrustedProxies   []netip.Prefix
	AutoMigrate      bool
	// EmailLinkRedirectURI 是邮件链接的落地页（Agora UI 根地址）。
	// 必须与 platformd 的 PLATFORMD_EMAIL_LINK_REDIRECT_URIS 完全一致。
	EmailLinkRedirectURI string
}

// Load reads .env when present, then applies environment variables. Values
// already exported in the process environment win over .env entries.
func Load() (Config, error) {
	_ = godotenv.Load()

	maxConns, err := envInt("DB_MAX_CONNS", 10)
	if err != nil {
		return Config{}, err
	}
	if maxConns < 1 {
		return Config{}, errors.New("DB_MAX_CONNS must be greater than zero")
	}

	connectTimeout, err := envDuration("DB_CONNECT_TIMEOUT", 5*time.Second)
	if err != nil {
		return Config{}, err
	}

	readinessTimeout, err := envDuration("READINESS_TIMEOUT", 2*time.Second)
	if err != nil {
		return Config{}, err
	}

	shutdownTimeout, err := envDuration("SHUTDOWN_TIMEOUT", 10*time.Second)
	if err != nil {
		return Config{}, err
	}

	identityBaseURL, err := envURL("IDENTITY_BASE_URL", "http://127.0.0.1:8420")
	if err != nil {
		return Config{}, err
	}

	nexusBaseURL, err := envURL("NEXUS_BASE_URL", "http://127.0.0.1:8421")
	if err != nil {
		return Config{}, err
	}

	authHTTPTimeout, err := envDuration("AUTH_HTTP_TIMEOUT", 3*time.Second)
	if err != nil {
		return Config{}, err
	}

	filesHTTPTimeout, err := envDuration("FILES_HTTP_TIMEOUT", 30*time.Second)
	if err != nil {
		return Config{}, err
	}

	trustedProxies, err := envPrefixes("TRUSTED_PROXY_CIDRS", "127.0.0.1/32,::1/128")
	if err != nil {
		return Config{}, err
	}

	autoMigrate, err := envBool("AUTO_MIGRATE", true)
	if err != nil {
		return Config{}, err
	}

	// 不用 envURL：它会裁掉结尾的 "/"，而 platformd 的白名单按 URL 原文精确匹配。
	emailLinkRedirectURI, err := envRedirectURI("AGORA_EMAIL_LINK_REDIRECT_URI", "http://localhost:4321/")
	if err != nil {
		return Config{}, err
	}

	cfg := Config{
		HTTPAddr:         env("HTTP_ADDR", ":8080"),
		DatabaseURL:      strings.TrimSpace(os.Getenv("DATABASE_URL")),
		AllowedOrigins:   splitList(env("ALLOWED_ORIGINS", "http://localhost:4321,http://127.0.0.1:4321")),
		LogLevel:         env("LOG_LEVEL", "info"),
		LogFile:          env("LOG_FILE", "logs/app.jsonl"),
		DBMaxConns:       int32(maxConns),
		DBConnectTimeout: connectTimeout,
		ReadinessTimeout: readinessTimeout,
		ShutdownTimeout:  shutdownTimeout,
		IdentityBaseURL:  identityBaseURL,
		NexusBaseURL:     nexusBaseURL,
		AuthHTTPTimeout:  authHTTPTimeout,
		FilesHTTPTimeout: filesHTTPTimeout,
		TrustedProxies:   trustedProxies,
		AutoMigrate:      autoMigrate,

		EmailLinkRedirectURI: emailLinkRedirectURI,
	}

	if cfg.DatabaseURL == "" {
		return Config{}, errors.New("DATABASE_URL is required")
	}

	return cfg, nil
}

func env(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func envInt(key string, fallback int) (int, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}

	value, err := strconv.Atoi(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be an integer: %w", key, err)
	}
	return value, nil
}

func envBool(key string, fallback bool) (bool, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}

	value, err := strconv.ParseBool(raw)
	if err != nil {
		return false, fmt.Errorf("%s must be true or false: %w", key, err)
	}
	return value, nil
}

func envPrefixes(key, fallback string) ([]netip.Prefix, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		raw = fallback
	}

	parts := strings.Split(raw, ",")
	prefixes := make([]netip.Prefix, 0, len(parts))
	for _, part := range parts {
		prefix, err := netip.ParsePrefix(strings.TrimSpace(part))
		if err != nil {
			return nil, fmt.Errorf("%s contains an invalid CIDR: %w", key, err)
		}
		prefixes = append(prefixes, prefix.Masked())
	}
	return prefixes, nil
}

func envURL(key, fallback string) (string, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		raw = fallback
	}

	parsed, err := url.Parse(raw)
	if err != nil || !parsed.IsAbs() || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return "", fmt.Errorf("%s must be an absolute http(s) URL", key)
	}
	return strings.TrimRight(raw, "/"), nil
}

// envRedirectURI 校验邮件链接落地页：必须是绝对的 http(s) URL、带主机、
// 不含用户信息与片段，且保留原始路径（含结尾 "/"）——身份服务按原文精确匹配。
func envRedirectURI(key, fallback string) (string, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		raw = fallback
	}

	parsed, err := url.Parse(raw)
	if err != nil || !parsed.IsAbs() || parsed.Host == "" || (parsed.Scheme != "http" && parsed.Scheme != "https") {
		return "", fmt.Errorf("%s must be an absolute http(s) URL", key)
	}
	if parsed.User != nil || parsed.Fragment != "" {
		return "", fmt.Errorf("%s must not contain user info or a fragment", key)
	}
	return parsed.String(), nil
}

func envDuration(key string, fallback time.Duration) (time.Duration, error) {
	raw := strings.TrimSpace(os.Getenv(key))
	if raw == "" {
		return fallback, nil
	}

	value, err := time.ParseDuration(raw)
	if err != nil {
		return 0, fmt.Errorf("%s must be a duration such as 5s: %w", key, err)
	}
	if value <= 0 {
		return 0, fmt.Errorf("%s must be greater than zero", key)
	}
	return value, nil
}

func splitList(value string) []string {
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, strings.TrimRight(trimmed, "/"))
		}
	}
	return out
}
