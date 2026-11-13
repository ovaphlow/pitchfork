package logging

import (
	"bufio"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"
)

func TestNewWritesOneJSONObjectPerLine(t *testing.T) {
	path := filepath.Join(t.TempDir(), "app.jsonl")

	logger, closer, err := New(Config{Level: "debug", File: path, Service: "agora-api"})
	if err != nil {
		t.Fatalf("New: %v", err)
	}
	logger.Info("ready", "port", 8080)
	if err := closer.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}

	file, err := os.Open(path)
	if err != nil {
		t.Fatalf("open log: %v", err)
	}
	defer file.Close()

	scanner := bufio.NewScanner(file)
	count := 0
	for scanner.Scan() {
		count++
		var record map[string]any
		if err := json.Unmarshal(scanner.Bytes(), &record); err != nil {
			t.Fatalf("line %d is not JSON: %v", count, err)
		}
		if record["msg"] != "ready" || record["service"] != "agora-api" {
			t.Fatalf("record %d = %#v", count, record)
		}
	}
	if err := scanner.Err(); err != nil {
		t.Fatalf("scan log: %v", err)
	}
	if count != 1 {
		t.Fatalf("lines = %d, want 1", count)
	}
}
