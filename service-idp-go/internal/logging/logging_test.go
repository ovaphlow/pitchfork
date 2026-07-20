package logging

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestLoggerWritesTextToConsoleAndWarningJSONToDailyFile(t *testing.T) {
	var console bytes.Buffer
	logDirectory := t.TempDir()
	logger, closeLogs, err := New(logDirectory, &console)
	if err != nil {
		t.Fatalf("create logger: %v", err)
	}

	logger.Info("service started", "applied", 9)
	logger.Warn("session nearing expiry", "subject_id", "01J8Z4Q5W6V7B8N9M0K1L2P3Q4")
	logger.Error("database unavailable", "error", "temporary failure")

	if err := closeLogs(); err != nil {
		t.Fatalf("close logs: %v", err)
	}

	consoleOutput := console.String()
	for _, level := range []string{"INFO", "WARN", "ERROR"} {
		if !strings.Contains(consoleOutput, "level="+level) {
			t.Fatalf("console output does not contain %s: %q", level, consoleOutput)
		}
	}
	if strings.Contains(consoleOutput, "{\"level\"") {
		t.Fatalf("console output should use slog text format: %q", consoleOutput)
	}

	logFiles, err := filepath.Glob(filepath.Join(logDirectory, "identityd-*.jsonl"))
	if err != nil {
		t.Fatalf("glob JSON log files: %v", err)
	}
	if len(logFiles) != 1 {
		t.Fatalf("JSON log file count = %d, want 1", len(logFiles))
	}

	contents, err := os.ReadFile(logFiles[0])
	if err != nil {
		t.Fatalf("read JSON log file: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(string(contents)), "\n")
	if len(lines) != 2 {
		t.Fatalf("JSON log line count = %d, want 2: %q", len(lines), contents)
	}

	levels := make([]string, 0, len(lines))
	for _, line := range lines {
		var record map[string]any
		if err := json.Unmarshal([]byte(line), &record); err != nil {
			t.Fatalf("parse JSON log line %q: %v", line, err)
		}
		level, ok := record["level"].(string)
		if !ok {
			t.Fatalf("JSON log level = %#v", record["level"])
		}
		levels = append(levels, level)
	}
	if strings.Join(levels, ",") != "WARN,ERROR" {
		t.Fatalf("JSON log levels = %v, want [WARN ERROR]", levels)
	}
}
