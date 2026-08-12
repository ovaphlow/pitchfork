package database

import (
	"context"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

// openPG opens a lazy pgx connection pool for the given DSN. Creating the
// pool parses the DSN without network activity; Ping performs the first
// real connection, so an unavailable database surfaces as an error here
// rather than at construction time.
func openPG(ctx context.Context, dsn string) (Conn, error) {
	pool, err := pgxpool.New(ctx, dsn)
	if err != nil {
		return nil, err
	}
	if err := pool.Ping(ctx); err != nil {
		pool.Close()
		return nil, err
	}
	return &pgConn{pool: pool}, nil
}

// pgConn is the pgx-backed implementation of Conn.
type pgConn struct {
	pool *pgxpool.Pool
}

func (c *pgConn) Ping(ctx context.Context) error {
	return c.pool.Ping(ctx)
}

// Exec runs a single SQL string. Migrations may contain multiple
// statements, so the simple protocol is used, which executes the whole
// string server-side.
func (c *pgConn) Exec(ctx context.Context, sql string) error {
	conn, err := c.pool.Acquire(ctx)
	if err != nil {
		return err
	}
	defer conn.Release()
	_, err = conn.Exec(ctx, sql, pgx.QueryExecModeSimpleProtocol)
	return err
}

func (c *pgConn) Close() error {
	c.pool.Close()
	return nil
}
