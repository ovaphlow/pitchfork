package logging

import (
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
)

// Config describes the JSONL logger.
type Config struct {
	Level   string
	File    string
	Service string
}

type nopCloser struct{}

func (nopCloser) Close() error { return nil }

// New creates a slog logger that writes one JSON object per line to stdout and,
// when File is set, appends the same records to that file.
func New(cfg Config) (*slog.Logger, io.Closer, error) {
	level, err := parseLevel(cfg.Level)
	if err != nil {
		return nil, nil, err
	}

	var writer io.Writer = os.Stdout
	var closer io.Closer = nopCloser{}

	file := strings.TrimSpace(cfg.File)
	if file != "" && file != "-" {
		if dir := filepath.Dir(file); dir != "." {
			if err := os.MkdirAll(dir, 0o755); err != nil {
				return nil, nil, err
			}
		}

		logFile, err := os.OpenFile(file, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
		if err != nil {
			return nil, nil, err
		}

		writer = io.MultiWriter(os.Stdout, logFile)
		closer = logFile
	}

	handler := slog.NewJSONHandler(writer, &slog.HandlerOptions{Level: level})
	logger := slog.New(handler)
	if cfg.Service != "" {
		logger = logger.With("service", cfg.Service)
	}

	return logger, closer, nil
}

func parseLevel(raw string) (slog.Level, error) {
	value := strings.TrimSpace(raw)
	if value == "" {
		return slog.LevelInfo, nil
	}

	var level slog.Level
	if err := level.UnmarshalText([]byte(value)); err != nil {
		return 0, errors.New("LOG_LEVEL must be one of debug, info, warn, or error")
	}
	return level, nil
}
