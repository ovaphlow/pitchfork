package database

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"strings"
	"time"

	_ "github.com/lib/pq"
)

type Config struct {
	DSN            string
	MaxConns       int
	Timeout        time.Duration
	TimeZone       string
	ClientEncoding string
}

// ConfigFromEnv reads DB config from environment variables
func ConfigFromEnv() Config {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		// default local
		dsn = "postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable"
	}
	max := 5
	tz := os.Getenv("DATABASE_TIMEZONE")
	enc := os.Getenv("DATABASE_CLIENT_ENCODING")
	return Config{DSN: dsn, MaxConns: max, Timeout: 5 * time.Second, TimeZone: tz, ClientEncoding: enc}
}

// Connect opens a *sql.DB and verifies connectivity with a ping
func Connect(cfg Config) (*sql.DB, error) {
	db, err := sql.Open("postgres", cfg.DSN)
	if err != nil {
		return nil, fmt.Errorf("open db: %w", err)
	}
	db.SetMaxOpenConns(cfg.MaxConns)
	db.SetMaxIdleConns(cfg.MaxConns)
	db.SetConnMaxLifetime(30 * time.Minute)

	ctx, cancel := context.WithTimeout(context.Background(), cfg.Timeout)
	defer cancel()

	if err := db.PingContext(ctx); err != nil {
		db.Close()
		return nil, fmt.Errorf("ping db: %w", err)
	}
	// Apply session-level settings if provided
	if cfg.TimeZone != "" || cfg.ClientEncoding != "" {
		connCtx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		// Use a short-lived transactionless Exec for session settings
		if cfg.TimeZone != "" {
			if _, err := db.ExecContext(connCtx, "SET TIME ZONE "+quoteLiteral(cfg.TimeZone)); err != nil {
				// return error to surface misconfiguration
				db.Close()
				return nil, fmt.Errorf("set time zone: %w", err)
			}
		}
		if cfg.ClientEncoding != "" {
			if _, err := db.ExecContext(connCtx, "SET client_encoding = "+quoteLiteral(cfg.ClientEncoding)); err != nil {
				db.Close()
				return nil, fmt.Errorf("set client_encoding: %w", err)
			}
		}
	}
	return db, nil
}

// quoteLiteral escapes single quotes and wraps the value in single quotes
// so it can be used safely in SET ... statements which don't accept
// parameter placeholders for the right-hand side.
func quoteLiteral(s string) string {
	return "'" + strings.ReplaceAll(s, "'", "''") + "'"
}
