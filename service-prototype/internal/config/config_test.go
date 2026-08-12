package config

import (
	"strings"
	"testing"
)

func TestLoadFromLookupUsesDefaultPort(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{}))
	if err != nil {
		t.Fatalf("load defaults: %v", err)
	}
	if configuration.Port != defaultPort {
		t.Fatalf("port = %d, want default %d", configuration.Port, defaultPort)
	}
	if configuration.Address() != ":8423" {
		t.Fatalf("address = %q, want %q", configuration.Address(), ":8423")
	}
}

func TestLoadFromLookupEnvironmentOverridesDefaultPort(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{"PORT": "9432"}))
	if err != nil {
		t.Fatalf("load configured port: %v", err)
	}
	if configuration.Port != 9432 {
		t.Fatalf("port = %d, want 9432", configuration.Port)
	}
	if configuration.Address() != ":9432" {
		t.Fatalf("address = %q, want %q", configuration.Address(), ":9432")
	}
}

func TestLoadFromLookupRejectsInvalidPort(t *testing.T) {
	for _, value := range []string{"not-a-number", "1.5", "-1", "65536"} {
		_, err := LoadFromLookup(valuesLookup(map[string]string{"PORT": value}))
		if err == nil {
			t.Fatalf("PORT = %q: expected an error", value)
		}
		if !strings.Contains(err.Error(), "PORT") {
			t.Fatalf("PORT = %q: error %q does not mention the variable name", value, err)
		}
	}
}

func valuesLookup(values map[string]string) func(string) string {
	return func(key string) string {
		return values[key]
	}
}
