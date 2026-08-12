// Package config loads prototyped configuration from environment
// variables. The loader is lookup-injected (LoadFromLookup) so it is
// testable without mutating the process environment.
package config

import (
	"fmt"
	"os"
	"strconv"
	"strings"
)

const defaultPort = 8423

// Config holds the resolved service configuration.
type Config struct {
	Port int
}

// Address returns the listen address for the HTTP server.
func (configuration Config) Address() string {
	return fmt.Sprintf(":%d", configuration.Port)
}

// Load reads configuration from the process environment.
func Load() (Config, error) {
	return LoadFromLookup(os.Getenv)
}

// LoadFromLookup reads configuration through the given lookup function,
// which makes the loader testable without mutating the process environment.
func LoadFromLookup(lookup func(string) string) (Config, error) {
	port, err := portValue(lookup, "PORT", defaultPort)
	if err != nil {
		return Config{}, err
	}
	return Config{Port: port}, nil
}

// portValue resolves an integer variable with a fallback. The variable is
// optional (the fallback applies when unset or blank), but once provided it
// must parse as an integer between 0 and 65535.
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
