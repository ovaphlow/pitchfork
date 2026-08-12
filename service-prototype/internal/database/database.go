// Package database provides the lazy PostgreSQL connection and migration
// bootstrap of prototyped. Constructing a Connector never touches the
// network; the first Connect call opens the connection and runs the
// embedded migrations. Connection and migration failures are returned as
// errors (never panics) so the caller decides how to react — prototyped
// logs them and keeps serving without a database.
package database

import (
	"context"
	"errors"
	"fmt"
	"sync"
)

// ErrNotConnected is returned by Ping before the first successful Connect.
var ErrNotConnected = errors.New("database: not connected")

// Conn is the minimal database surface prototyped uses.
type Conn interface {
	Ping(ctx context.Context) error
	// Exec runs a single SQL string. Implementations must accept strings
	// containing multiple statements (migrations).
	Exec(ctx context.Context, sql string) error
	Close() error
}

// Opener opens a connection for the given DSN. It is injectable so tests
// can stub connection failures without a real PostgreSQL server.
type Opener func(ctx context.Context, dsn string) (Conn, error)

// Migrator runs the pending migrations against an open connection. It is
// injectable so tests can stub migration failures.
type Migrator func(ctx context.Context, conn Conn) error

// Connector owns a lazily opened database connection. All methods are safe
// for concurrent use.
type Connector struct {
	dsn      string
	opener   Opener
	migrator Migrator

	mu   sync.Mutex
	conn Conn
}

// New creates a Connector for the given DSN. No network activity happens
// until the first successful Connect call.
func New(dsn string) *Connector {
	return NewWith(dsn, openPG, Migrate)
}

// NewWith creates a Connector with injected opener and migrator, for tests.
func NewWith(dsn string, opener Opener, migrator Migrator) *Connector {
	return &Connector{dsn: dsn, opener: opener, migrator: migrator}
}

// Connect opens the connection if it is not already open and runs the
// embedded migrations. On failure the connector stays unconnected, so a
// later Connect call retries from scratch.
func (c *Connector) Connect(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn != nil {
		return nil
	}
	conn, err := c.opener(ctx, c.dsn)
	if err != nil {
		return fmt.Errorf("open database: %w", err)
	}
	if err := c.migrator(ctx, conn); err != nil {
		_ = conn.Close()
		return fmt.Errorf("run migrations: %w", err)
	}
	c.conn = conn
	return nil
}

// Ping reports whether the connection is open and reachable. It returns
// ErrNotConnected when Connect has not succeeded yet.
func (c *Connector) Ping(ctx context.Context) error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return ErrNotConnected
	}
	return c.conn.Ping(ctx)
}

// Close releases the underlying connection, if any. A closed connector can
// be reconnected by a later Connect call.
func (c *Connector) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.conn == nil {
		return nil
	}
	err := c.conn.Close()
	c.conn = nil
	return err
}
