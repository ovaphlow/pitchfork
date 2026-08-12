// Package config loads prototyped configuration from environment variables
// and an optional .env file. The loader is lookup-injected (LoadFromLookup,
// LoadWithDotEnvLookup) so it is testable without mutating the process
// environment or touching the network.
package config

import (
	"bufio"
	"fmt"
	"net/url"
	"os"
	"strconv"
	"strings"
)

const (
	defaultPort         = 8423
	defaultDatabaseHost = "127.0.0.1"
	defaultDatabasePort = 5433
	defaultDatabaseUser = "ovaphlow"
	defaultDatabaseName = "ovaphlow"
)

// Config holds the resolved service configuration.
type Config struct {
	Port int
	// DatabaseURL is the complete PostgreSQL DSN. It comes verbatim from
	// DATABASE_URL when provided, otherwise it is assembled from the
	// discrete PITCHFORK_DB_* variables.
	DatabaseURL string
	// CORSAllowedOrigins is the comma-separated CORS allow list from
	// CORS_ALLOWED_ORIGINS. Empty when the variable is unset.
	CORSAllowedOrigins []string
}

// Address returns the listen address for the HTTP server.
func (configuration Config) Address() string {
	return fmt.Sprintf(":%d", configuration.Port)
}

// Load reads configuration from the process environment merged with the
// .env file in the working directory, when present.
func Load() (Config, error) {
	return LoadWithDotEnvLookup(os.Getenv, ".env")
}

// LoadFromLookup reads configuration through the given lookup function,
// which makes the loader testable without mutating the process environment.
func LoadFromLookup(lookup func(string) string) (Config, error) {
	port, err := portValue(lookup, "PORT", defaultPort)
	if err != nil {
		return Config{}, err
	}
	databaseURL, err := databaseURLValue(lookup)
	if err != nil {
		return Config{}, err
	}
	return Config{
		Port:               port,
		DatabaseURL:        databaseURL,
		CORSAllowedOrigins: splitCSV(lookup("CORS_ALLOWED_ORIGINS")),
	}, nil
}

// splitCSV splits a comma-separated list, trimming whitespace and dropping
// empty entries. An empty input yields an empty (non-nil) slice.
func splitCSV(value string) []string {
	value = strings.TrimSpace(value)
	if value == "" {
		return []string{}
	}
	parts := strings.Split(value, ",")
	out := make([]string, 0, len(parts))
	for _, part := range parts {
		if trimmed := strings.TrimSpace(part); trimmed != "" {
			out = append(out, trimmed)
		}
	}
	return out
}

// LoadWithDotEnv loads configuration from the process environment merged
// with the values of the given dotenv file. A missing dotenv file is not an
// error; the environment is used on its own.
func LoadWithDotEnv(path string) (Config, error) {
	return LoadWithDotEnvLookup(os.Getenv, path)
}

// LoadWithDotEnvLookup is LoadWithDotEnv with an injectable environment
// lookup, for tests. Precedence is: real environment variables, then dotenv
// file values, then defaults.
func LoadWithDotEnvLookup(envLookup func(string) string, path string) (Config, error) {
	fileValues, err := LoadDotEnv(path)
	if err != nil {
		return Config{}, err
	}
	return LoadFromLookup(mergedLookup(envLookup, fileValues))
}

// LoadDotEnv parses a dotenv file into a map of KEY=value pairs. Blank
// lines and lines starting with '#' are ignored, an optional leading
// "export " is stripped, and values may be wrapped in single or double
// quotes. A missing file yields an empty map.
func LoadDotEnv(path string) (map[string]string, error) {
	values := make(map[string]string)
	file, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return values, nil
		}
		return nil, fmt.Errorf("read dotenv file %s: %w", path, err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	lineNumber := 0
	for scanner.Scan() {
		lineNumber++
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") {
			continue
		}
		line = strings.TrimPrefix(line, "export ")
		key, value, found := strings.Cut(line, "=")
		if !found || strings.TrimSpace(key) == "" {
			return nil, fmt.Errorf("dotenv file %s:%d: expected KEY=VALUE", path, lineNumber)
		}
		values[strings.TrimSpace(key)] = unquoteDotEnvValue(strings.TrimSpace(value))
	}
	if err := scanner.Err(); err != nil {
		return nil, fmt.Errorf("read dotenv file %s: %w", path, err)
	}
	return values, nil
}

// unquoteDotEnvValue strips one pair of surrounding single or double quotes.
// Escape sequences are intentionally not interpreted; templates stay simple.
func unquoteDotEnvValue(value string) string {
	if len(value) >= 2 {
		first, last := value[0], value[len(value)-1]
		if (first == '"' && last == '"') || (first == '\'' && last == '\'') {
			return value[1 : len(value)-1]
		}
	}
	return value
}

// mergedLookup returns a lookup that consults the real environment first
// and the dotenv file values second.
func mergedLookup(envLookup func(string) string, fileValues map[string]string) func(string) string {
	return func(key string) string {
		if value := strings.TrimSpace(envLookup(key)); value != "" {
			return value
		}
		return fileValues[key]
	}
}

// databaseURLValue resolves the PostgreSQL DSN. DATABASE_URL wins and is
// used verbatim as the complete DSN; otherwise the discrete PITCHFORK_DB_*
// variables are assembled into a postgres:// URL. PITCHFORK_DB_PASSWORD is
// required only when DATABASE_URL is not provided.
func databaseURLValue(lookup func(string) string) (string, error) {
	if databaseURL := strings.TrimSpace(lookup("DATABASE_URL")); databaseURL != "" {
		return databaseURL, nil
	}

	host := stringValue(lookup, "PITCHFORK_DB_HOST", defaultDatabaseHost)
	port, err := portValue(lookup, "PITCHFORK_DB_PORT", defaultDatabasePort)
	if err != nil {
		return "", err
	}
	user := stringValue(lookup, "PITCHFORK_DB_USER", defaultDatabaseUser)
	name := stringValue(lookup, "PITCHFORK_DB_NAME", defaultDatabaseName)
	password := strings.TrimSpace(lookup("PITCHFORK_DB_PASSWORD"))
	if password == "" {
		return "", fmt.Errorf("PITCHFORK_DB_PASSWORD must be set when DATABASE_URL is not provided")
	}

	return (&url.URL{
		Scheme: "postgres",
		User:   url.UserPassword(user, password),
		Host:   fmt.Sprintf("%s:%d", host, port),
		Path:   "/" + name,
	}).String(), nil
}

func stringValue(lookup func(string) string, key string, fallback string) string {
	if value := strings.TrimSpace(lookup(key)); value != "" {
		return value
	}
	return fallback
}

func portValue(lookup func(string) string, key string, fallback int) (int, error) {
	value := strings.TrimSpace(lookup(key))
	if value == "" {
		return fallback, nil
	}
	parsed, err := strconv.Atoi(value)
	if err != nil || parsed < 0 || parsed > 65535 {
		return 0, fmt.Errorf("%s must be an integer between 0 and 65535", key)
	}
	return parsed, nil
}
