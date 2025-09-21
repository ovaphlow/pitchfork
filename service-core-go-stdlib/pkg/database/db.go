package database

import (
	"context"
	"database/sql"
	"fmt"
	"os"
	"time"

	_ "github.com/lib/pq"
)

type Config struct {
	DSN      string
	MaxConns int
	Timeout  time.Duration
}

// ConfigFromEnv reads DB config from environment variables
func ConfigFromEnv() Config {
	dsn := os.Getenv("DATABASE_URL")
	if dsn == "" {
		// default local
		dsn = "postgres://postgres:postgres@localhost:5432/postgres?sslmode=disable"
	}
	max := 5
	return Config{DSN: dsn, MaxConns: max, Timeout: 5 * time.Second}
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
	return db, nil
}
