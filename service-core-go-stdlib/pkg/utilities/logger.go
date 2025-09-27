package utilities

import (
	"fmt"
	"os"
	"path/filepath"
	"time"

	rotatelogs "github.com/lestrrat-go/file-rotatelogs"
	"go.uber.org/zap"
	"go.uber.org/zap/zapcore"
)

type Config struct {
	Level string
	Dev   bool
}

// ConfigFromEnv reads minimal config from env vars.
func ConfigFromEnv() Config {
	dev := os.Getenv("LOG_DEV") == "1"
	lvl := os.Getenv("LOG_LEVEL")
	if lvl == "" {
		if dev {
			lvl = "debug"
		} else {
			lvl = "info"
		}
	}
	return Config{Level: lvl, Dev: dev}
}

func levelFromString(l string) zapcore.Level {
	switch l {
	case "debug":
		return zapcore.DebugLevel
	case "info":
		return zapcore.InfoLevel
	case "warn", "warning":
		return zapcore.WarnLevel
	case "error":
		return zapcore.ErrorLevel
	default:
		return zapcore.InfoLevel
	}
}

// Init initializes and returns a *zap.Logger
func Init(cfg Config) (*zap.Logger, error) {
	lvl := levelFromString(cfg.Level)
	if cfg.Dev {
		c := zap.NewDevelopmentConfig()
		c.Level = zap.NewAtomicLevelAt(lvl)
		return c.Build()
	}

	encoderCfg := zap.NewProductionEncoderConfig()
	encoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder

	// Default WriteSyncer to stdout
	// We'll build two cores: one console (human-friendly) to stdout, and one JSON to rotating file.
	consoleEncoderCfg := zap.NewDevelopmentEncoderConfig()
	consoleEncoderCfg.EncodeTime = zapcore.ISO8601TimeEncoder
	consoleEncoder := zapcore.NewConsoleEncoder(consoleEncoderCfg)

	jsonEncoder := zapcore.NewJSONEncoder(encoderCfg)

	consoleWS := zapcore.AddSync(os.Stdout)

	// Try to create ./logs directory and use a rotatelogs writer for daily files
	logsDir := "./logs"
	if err := os.MkdirAll(logsDir, 0o755); err == nil {
		// pattern like ./logs/app-%Y%m%d.log
		pattern := filepath.Join(logsDir, "app-%Y%m%d.log")
		// Create rotatelogs writer which rotates every 24h and keeps logs for 7 days
		rl, err := rotatelogs.New(
			pattern,
			rotatelogs.WithRotationTime(24*time.Hour),
			rotatelogs.WithLinkName(filepath.Join(logsDir, "app.log")),
			rotatelogs.WithMaxAge(7*24*time.Hour),
		)
		if err == nil {
			// keep rotatelogs writer
			fileWS := zapcore.AddSync(rl)

			// create two cores: console -> stdout (text), file -> json
			consoleCore := zapcore.NewCore(consoleEncoder, consoleWS, lvl)
			fileCore := zapcore.NewCore(jsonEncoder, fileWS, lvl)

			core := zapcore.NewTee(consoleCore, fileCore)
			opts := []zap.Option{zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel)}
			return zap.New(core, opts...), nil
		} else {
			_, _ = fmt.Fprintf(os.Stderr, "warning: failed to create rotatelogs writer: %v, using stdout only\n", err)
		}
	} else {
		_, _ = os.Stderr.WriteString("warning: failed to create ./logs directory, using stdout only\n")
	}

	// fallback: no rotatelogs available; log to stdout using console encoder
	core := zapcore.NewCore(consoleEncoder, consoleWS, lvl)
	opts := []zap.Option{zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel)}
	return zap.New(core, opts...), nil
}
