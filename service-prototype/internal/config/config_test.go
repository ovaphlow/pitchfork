package config

import (
	"os"
	"strings"
	"testing"
)

func TestLoadFromLookupUsesDefaultPort(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{"PITCHFORK_DB_PASSWORD": "test-pw"}))
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
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{"PORT": "9432", "PITCHFORK_DB_PASSWORD": "test-pw"}))
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
		_, err := LoadFromLookup(valuesLookup(map[string]string{"PORT": value, "PITCHFORK_DB_PASSWORD": "test-pw"}))
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

// ─── #28 数据层配置（DATABASE_URL / PITCHFORK_DB_* / dotenv） ─────────────

func TestLoadFromLookupRequiresPasswordWithoutDatabaseURL(t *testing.T) {
	_, err := LoadFromLookup(valuesLookup(map[string]string{}))
	if err == nil {
		t.Fatal("expected an error when PITCHFORK_DB_PASSWORD is missing and DATABASE_URL is not set")
	}
	if !strings.Contains(err.Error(), "PITCHFORK_DB_PASSWORD") {
		t.Fatalf("error %q does not mention PITCHFORK_DB_PASSWORD", err)
	}
}

func TestLoadFromLookupDatabaseURLWins(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"DATABASE_URL":         "postgres://custom:secret@db.example:5544/custom",
		"PITCHFORK_DB_PASSWORD": "", // 离散变量完全不参与
		"PITCHFORK_DB_HOST":     "ignored.example",
	}))
	if err != nil {
		t.Fatalf("DATABASE_URL present: %v", err)
	}
	if configuration.DatabaseURL != "postgres://custom:secret@db.example:5544/custom" {
		t.Fatalf("DatabaseURL = %q, want DATABASE_URL verbatim", configuration.DatabaseURL)
	}
}

func TestLoadFromLookupAssemblesDiscreteVars(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"PITCHFORK_DB_HOST":     "10.0.0.5",
		"PITCHFORK_DB_PORT":     "5433",
		"PITCHFORK_DB_USER":     "aceso",
		"PITCHFORK_DB_NAME":     "prototype_db",
		"PITCHFORK_DB_PASSWORD": "pw-123",
	}))
	if err != nil {
		t.Fatalf("discrete vars: %v", err)
	}
	want := "postgres://aceso:pw-123@10.0.0.5:5433/prototype_db"
	if configuration.DatabaseURL != want {
		t.Fatalf("DatabaseURL = %q, want %q", configuration.DatabaseURL, want)
	}
}

func TestLoadFromLookupDiscreteDefaults(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"PITCHFORK_DB_PASSWORD": "pw",
	}))
	if err != nil {
		t.Fatalf("defaults: %v", err)
	}
	want := "postgres://ovaphlow:pw@127.0.0.1:5433/ovaphlow"
	if configuration.DatabaseURL != want {
		t.Fatalf("DatabaseURL = %q, want %q", configuration.DatabaseURL, want)
	}
}

func TestLoadDotEnvParsesFile(t *testing.T) {
	path := t.TempDir() + "/.env"
	content := "# comment\nPORT=9432\nDATABASE_URL='postgres://a:b@h:1/n'\nexport PITCHFORK_DB_PASSWORD=\"pw\"\n"
	if err := os.WriteFile(path, []byte(content), 0o600); err != nil {
		t.Fatal(err)
	}
	values, err := LoadDotEnv(path)
	if err != nil {
		t.Fatalf("LoadDotEnv: %v", err)
	}
	if values["PORT"] != "9432" || values["DATABASE_URL"] != "postgres://a:b@h:1/n" ||
		values["PITCHFORK_DB_PASSWORD"] != "pw" {
		t.Fatalf("parsed values = %#v", values)
	}
}

func TestLoadWithDotEnvLookupEnvOverridesFile(t *testing.T) {
	path := t.TempDir() + "/.env"
	if err := os.WriteFile(path, []byte("PORT=9001\nPITCHFORK_DB_PASSWORD=file-pw\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	configuration, err := LoadWithDotEnvLookup(valuesLookup(map[string]string{
		"PORT":                 "9002",
		"PITCHFORK_DB_PASSWORD": "env-pw",
	}), path)
	if err != nil {
		t.Fatalf("LoadWithDotEnvLookup: %v", err)
	}
	if configuration.Port != 9002 {
		t.Fatalf("Port = %d, want env value 9002 (env > dotenv)", configuration.Port)
	}
	if !strings.Contains(configuration.DatabaseURL, "env-pw") {
		t.Fatalf("DatabaseURL %q should use env password (env > dotenv)", configuration.DatabaseURL)
	}
}

func TestLoadWithDotEnvMissingFileIsNotError(t *testing.T) {
	configuration, err := LoadWithDotEnvLookup(valuesLookup(map[string]string{
		"PITCHFORK_DB_PASSWORD": "pw",
	}), t.TempDir()+"/does-not-exist.env")
	if err != nil {
		t.Fatalf("missing dotenv file must not be an error: %v", err)
	}
	if configuration.Port != defaultPort {
		t.Fatalf("Port = %d, want default %d", configuration.Port, defaultPort)
	}
}

// ─── #29 CORS 允许列表配置 ───────────────────────────────────────────────

func TestLoadFromLookupParsesCORSAllowedOrigins(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"CORS_ALLOWED_ORIGINS": "https://a.example, https://b.example ,",
		"PITCHFORK_DB_PASSWORD": "pw",
	}))
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	want := []string{"https://a.example", "https://b.example"}
	if len(configuration.CORSAllowedOrigins) != len(want) {
		t.Fatalf("CORSAllowedOrigins = %#v, want %#v", configuration.CORSAllowedOrigins, want)
	}
	for i := range want {
		if configuration.CORSAllowedOrigins[i] != want[i] {
			t.Fatalf("CORSAllowedOrigins = %#v, want %#v", configuration.CORSAllowedOrigins, want)
		}
	}
}

func TestLoadFromLookupCORSAllowedOriginsDefaultEmpty(t *testing.T) {
	configuration, err := LoadFromLookup(valuesLookup(map[string]string{
		"PITCHFORK_DB_PASSWORD": "pw",
	}))
	if err != nil {
		t.Fatalf("load: %v", err)
	}
	if configuration.CORSAllowedOrigins == nil || len(configuration.CORSAllowedOrigins) != 0 {
		t.Fatalf("CORSAllowedOrigins = %#v, want empty slice", configuration.CORSAllowedOrigins)
	}
}
