package utilities

import (
	"os"

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
	core := zapcore.NewCore(zapcore.NewJSONEncoder(encoderCfg), zapcore.AddSync(os.Stdout), lvl)
	opts := []zap.Option{zap.AddCaller(), zap.AddStacktrace(zapcore.ErrorLevel)}
	return zap.New(core, opts...), nil
}
