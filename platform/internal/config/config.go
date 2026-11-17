package config

import (
	"fmt"
	"net/netip"
	"net/url"
	"os"
	"strconv"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/platform/internal/mail"
)

const (
	defaultAddress          = "127.0.0.1:8420"
	defaultDatabasePath     = ".data/platformd.sqlite"
	defaultSessionTTL       = 12 * time.Hour
	defaultSessionIdleTTL   = 30 * time.Minute
	defaultThrottleWindow   = 15 * time.Minute
	defaultThrottleLockout  = 15 * time.Minute
	defaultThrottleFailures = 5
	defaultTrustedProxyCIDR = "127.0.0.1/32"
	defaultAppCodes         = "agora,aceso,trainova"

	defaultEmailLinkTTL         = 10 * time.Minute
	defaultEmailLinkRateWindow  = 15 * time.Minute
	defaultEmailLinkEmailLimit  = 3
	defaultEmailLinkSourceLimit = 10
	defaultSMTPTimeout          = 10 * time.Second

	// 共享模块（原 service-nexus-shared，见 docs/plans/055）。
	defaultFilesDir             = ".data/files"
	defaultMaxUploadBytes int64 = 20971520
)

type Config struct {
	Address                   string
	DatabasePath              string
	BootstrapIdentifier       string
	BootstrapPassword         string
	SessionTTL                time.Duration
	SessionIdleTTL            time.Duration
	LoginThrottleSecret       []byte
	LoginThrottleWindow       time.Duration
	LoginThrottleLockout      time.Duration
	LoginThrottleFailureLimit int
	SecureSessionCookie       bool
	PublicURL                 *url.URL
	TrustedProxyPrefixes      []netip.Prefix
	CorsOrigins               []string
	// AppCodes is the allowlist accepted in the shared APIs' X-App-Code header.
	AppCodes []string

	// 共享模块：文件存储目录与单次上传上限（字节）。
	FilesDir       string
	MaxUploadBytes int64

	// 邮件链接登录。SMTPAddress 为空表示不启用该登录方式。
	EmailLoginLinkTTL          time.Duration
	EmailLoginLinkRedirectURIs []string
	EmailLoginLinkRateWindow   time.Duration
	EmailLoginLinkEmailLimit   int
	EmailLoginLinkSourceLimit  int
	SMTPAddress                string
	SMTPFrom                   string
	SMTPUsername               string
	SMTPPassword               string
	SMTPTLSMode                string
	SMTPTimeout                time.Duration
}

func Load() (Config, error) {
	return LoadFromLookup(os.Getenv)
}

func LoadFromLookup(lookup func(string) string) (Config, error) {
	sessionTTL, err := durationValue(lookup, "PLATFORMD_SESSION_TTL", defaultSessionTTL)
	if err != nil {
		return Config{}, err
	}
	sessionIdleTTL, err := durationValue(lookup, "PLATFORMD_SESSION_IDLE_TTL", defaultSessionIdleTTL)
	if err != nil {
		return Config{}, err
	}
	if sessionIdleTTL > sessionTTL {
		return Config{}, fmt.Errorf("PLATFORMD_SESSION_IDLE_TTL must not exceed PLATFORMD_SESSION_TTL")
	}
	loginThrottleSecret, err := throttleSecretValue(lookup("PLATFORMD_LOGIN_THROTTLE_SECRET"))
	if err != nil {
		return Config{}, err
	}
	loginThrottleWindow, err := durationValue(lookup, "PLATFORMD_LOGIN_THROTTLE_WINDOW", defaultThrottleWindow)
	if err != nil {
		return Config{}, err
	}
	loginThrottleLockout, err := durationValue(lookup, "PLATFORMD_LOGIN_THROTTLE_LOCKOUT", defaultThrottleLockout)
	if err != nil {
		return Config{}, err
	}
	loginThrottleFailureLimit, err := positiveIntegerValue(lookup, "PLATFORMD_LOGIN_THROTTLE_FAILURES", defaultThrottleFailures)
	if err != nil {
		return Config{}, err
	}

	secureCookie, err := booleanValue(lookup, "PLATFORMD_SESSION_SECURE_COOKIE", false)
	if err != nil {
		return Config{}, err
	}

	publicURL, err := publicURLValue(lookup("PLATFORMD_PUBLIC_URL"))
	if err != nil {
		return Config{}, err
	}

	trustedProxyPrefixes, err := trustedProxyValues(lookup("PLATFORMD_TRUSTED_PROXY_CIDRS"))
	if err != nil {
		return Config{}, err
	}

	corsOrigins, err := corsOriginsValue(lookup("PLATFORMD_CORS_ORIGINS"))
	if err != nil {
		return Config{}, err
	}

	appCodes, err := appCodeValues(stringValue(lookup, "PLATFORMD_APP_CODES", defaultAppCodes))
	if err != nil {
		return Config{}, err
	}

	maxUploadBytes, err := positiveInt64Value(lookup, "PLATFORMD_MAX_UPLOAD_BYTES", defaultMaxUploadBytes)
	if err != nil {
		return Config{}, err
	}

	bootstrapIdentifier := strings.TrimSpace(lookup("PLATFORMD_BOOTSTRAP_IDENTIFIER"))
	bootstrapPassword := lookup("PLATFORMD_BOOTSTRAP_PASSWORD")
	if (bootstrapIdentifier == "") != (bootstrapPassword == "") {
		return Config{}, fmt.Errorf("PLATFORMD_BOOTSTRAP_IDENTIFIER and PLATFORMD_BOOTSTRAP_PASSWORD must be set together")
	}

	emailLinkTTL, err := durationValue(lookup, "PLATFORMD_EMAIL_LINK_TTL", defaultEmailLinkTTL)
	if err != nil {
		return Config{}, err
	}
	if emailLinkTTL > time.Hour {
		return Config{}, fmt.Errorf("PLATFORMD_EMAIL_LINK_TTL must not exceed one hour")
	}
	emailLinkRateWindow, err := durationValue(lookup, "PLATFORMD_EMAIL_LINK_RATE_WINDOW", defaultEmailLinkRateWindow)
	if err != nil {
		return Config{}, err
	}
	emailLinkEmailLimit, err := positiveIntegerValue(lookup, "PLATFORMD_EMAIL_LINK_EMAIL_LIMIT", defaultEmailLinkEmailLimit)
	if err != nil {
		return Config{}, err
	}
	emailLinkSourceLimit, err := positiveIntegerValue(lookup, "PLATFORMD_EMAIL_LINK_SOURCE_LIMIT", defaultEmailLinkSourceLimit)
	if err != nil {
		return Config{}, err
	}
	emailLinkRedirectURIs, err := redirectURIValues(lookup("PLATFORMD_EMAIL_LINK_REDIRECT_URIS"))
	if err != nil {
		return Config{}, err
	}
	smtpTimeout, err := durationValue(lookup, "PLATFORMD_SMTP_TIMEOUT", defaultSMTPTimeout)
	if err != nil {
		return Config{}, err
	}

	smtpAddress := strings.TrimSpace(lookup("PLATFORMD_SMTP_ADDR"))
	smtpFrom := strings.TrimSpace(lookup("PLATFORMD_SMTP_FROM"))
	smtpUsername := strings.TrimSpace(lookup("PLATFORMD_SMTP_USERNAME"))
	smtpPassword := lookup("PLATFORMD_SMTP_PASSWORD")
	smtpTLSMode := strings.ToLower(stringValue(lookup, "PLATFORMD_SMTP_TLS", mail.TLSModeStartTLS))
	switch smtpTLSMode {
	case mail.TLSModeStartTLS, mail.TLSModeImplicit, mail.TLSModeNone:
	default:
		return Config{}, fmt.Errorf("PLATFORMD_SMTP_TLS must be starttls, implicit or none")
	}
	if (smtpUsername == "") != (smtpPassword == "") {
		return Config{}, fmt.Errorf("PLATFORMD_SMTP_USERNAME and PLATFORMD_SMTP_PASSWORD must be set together")
	}
	if smtpTLSMode == mail.TLSModeNone && smtpUsername != "" {
		return Config{}, fmt.Errorf("PLATFORMD_SMTP_USERNAME requires TLS")
	}
	if smtpAddress == "" {
		if smtpFrom != "" || smtpUsername != "" || smtpPassword != "" {
			return Config{}, fmt.Errorf("PLATFORMD_SMTP_FROM, PLATFORMD_SMTP_USERNAME and PLATFORMD_SMTP_PASSWORD require PLATFORMD_SMTP_ADDR")
		}
	} else {
		if smtpFrom == "" {
			return Config{}, fmt.Errorf("PLATFORMD_SMTP_FROM is required when PLATFORMD_SMTP_ADDR is set")
		}
		if len(emailLinkRedirectURIs) == 0 {
			return Config{}, fmt.Errorf("PLATFORMD_EMAIL_LINK_REDIRECT_URIS is required when PLATFORMD_SMTP_ADDR is set")
		}
	}

	return Config{
		Address:                   stringValue(lookup, "PLATFORMD_ADDR", defaultAddress),
		DatabasePath:              stringValue(lookup, "PLATFORMD_DATABASE_PATH", defaultDatabasePath),
		BootstrapIdentifier:       bootstrapIdentifier,
		BootstrapPassword:         bootstrapPassword,
		SessionTTL:                sessionTTL,
		SessionIdleTTL:            sessionIdleTTL,
		LoginThrottleSecret:       loginThrottleSecret,
		LoginThrottleWindow:       loginThrottleWindow,
		LoginThrottleLockout:      loginThrottleLockout,
		LoginThrottleFailureLimit: loginThrottleFailureLimit,
		SecureSessionCookie:       secureCookie,
		PublicURL:                 publicURL,
		TrustedProxyPrefixes:      trustedProxyPrefixes,
		CorsOrigins:               corsOrigins,
		AppCodes:                  appCodes,
		FilesDir:                  stringValue(lookup, "PLATFORMD_FILES_DIR", defaultFilesDir),
		MaxUploadBytes:            maxUploadBytes,

		EmailLoginLinkTTL:          emailLinkTTL,
		EmailLoginLinkRedirectURIs: emailLinkRedirectURIs,
		EmailLoginLinkRateWindow:   emailLinkRateWindow,
		EmailLoginLinkEmailLimit:   emailLinkEmailLimit,
		EmailLoginLinkSourceLimit:  emailLinkSourceLimit,
		SMTPAddress:                smtpAddress,
		SMTPFrom:                   smtpFrom,
		SMTPUsername:               smtpUsername,
		SMTPPassword:               smtpPassword,
		SMTPTLSMode:                smtpTLSMode,
		SMTPTimeout:                smtpTimeout,
	}, nil
}

// redirectURIValues 解析逗号分隔的邮件链接落地页白名单。
// 与身份域 normalizeRedirectURI 规则一致：http(s)、带主机、无用户信息、无片段。
func redirectURIValues(value string) ([]string, error) {
	if strings.TrimSpace(value) == "" {
		return nil, nil
	}

	parts := strings.Split(value, ",")
	uris := make([]string, 0, len(parts))
	for _, part := range parts {
		raw := strings.TrimSpace(part)
		if raw == "" {
			return nil, fmt.Errorf("PLATFORMD_EMAIL_LINK_REDIRECT_URIS contains an empty entry")
		}
		parsed, err := publicURLValue(raw)
		if err != nil || parsed == nil {
			return nil, fmt.Errorf("PLATFORMD_EMAIL_LINK_REDIRECT_URIS contains an invalid URL")
		}
		if parsed.Scheme != "http" && parsed.Scheme != "https" {
			return nil, fmt.Errorf("PLATFORMD_EMAIL_LINK_REDIRECT_URIS contains an unsupported scheme")
		}
		if parsed.Fragment != "" || parsed.User != nil {
			return nil, fmt.Errorf("PLATFORMD_EMAIL_LINK_REDIRECT_URIS must not contain user info or fragments")
		}
		uris = append(uris, parsed.String())
	}
	return uris, nil
}

func corsOriginsValue(value string) ([]string, error) {
	if strings.TrimSpace(value) == "" {
		return []string{"http://localhost:4324", "http://127.0.0.1:4324"}, nil
	}

	parts := strings.Split(value, ",")
	origins := make([]string, 0, len(parts))
	for _, part := range parts {
		origin := strings.TrimSpace(part)
		parsed, err := url.Parse(origin)
		if origin == "" || err != nil || parsed.Scheme == "" || parsed.Host == "" || parsed.Path != "" || parsed.RawQuery != "" || parsed.Fragment != "" || parsed.User != nil {
			return nil, fmt.Errorf("PLATFORMD_CORS_ORIGINS contains an invalid origin")
		}
		if parsed.Scheme != "http" && parsed.Scheme != "https" {
			return nil, fmt.Errorf("PLATFORMD_CORS_ORIGINS contains an unsupported origin scheme")
		}
		origins = append(origins, origin)
	}
	return origins, nil
}

// appCodeValues 解析逗号分隔的产品 app_code 白名单（小写字母、数字、连字符）。
func appCodeValues(value string) ([]string, error) {
	parts := strings.Split(value, ",")
	codes := make([]string, 0, len(parts))
	seen := map[string]bool{}
	for _, part := range parts {
		code := strings.ToLower(strings.TrimSpace(part))
		if code == "" {
			return nil, fmt.Errorf("PLATFORMD_APP_CODES contains an empty entry")
		}
		valid := code[0] >= 'a' && code[0] <= 'z'
		for _, character := range code {
			if (character >= 'a' && character <= 'z') ||
				(character >= '0' && character <= '9') ||
				character == '-' {
				continue
			}
			valid = false
			break
		}
		if !valid || len(code) > 32 {
			return nil, fmt.Errorf("PLATFORMD_APP_CODES contains an invalid code")
		}
		if seen[code] {
			return nil, fmt.Errorf("PLATFORMD_APP_CODES contains a duplicate code")
		}
		seen[code] = true
		codes = append(codes, code)
	}
	return codes, nil
}

func throttleSecretValue(value string) ([]byte, error) {
	secret := []byte(strings.TrimSpace(value))
	if len(secret) < 32 {
		return nil, fmt.Errorf("PLATFORMD_LOGIN_THROTTLE_SECRET must contain at least 32 bytes")
	}
	return secret, nil
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

func positiveIntegerValue(lookup func(string) string, key string, fallback int) (int, error) {
	value := strings.TrimSpace(lookup(key))
	if value == "" {
		return fallback, nil
	}

	parsed, err := strconv.Atoi(value)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", key)
	}
	return parsed, nil
}

func positiveInt64Value(lookup func(string) string, key string, fallback int64) (int64, error) {
	value := strings.TrimSpace(lookup(key))
	if value == "" {
		return fallback, nil
	}

	parsed, err := strconv.ParseInt(value, 10, 64)
	if err != nil || parsed <= 0 {
		return 0, fmt.Errorf("%s must be a positive integer", key)
	}
	return parsed, nil
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
		return nil, fmt.Errorf("PLATFORMD_PUBLIC_URL must be an absolute URL")
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
			return nil, fmt.Errorf("PLATFORMD_TRUSTED_PROXY_CIDRS contains an invalid CIDR: %w", err)
		}
		prefixes = append(prefixes, prefix)
	}
	return prefixes, nil
}
