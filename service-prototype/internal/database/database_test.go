package database

import (
	"context"
	"errors"
	"strings"
	"sync"
	"testing"
)

// stubConn is a scriptable Conn for tests: it records every executed SQL
// string and can fail Ping, Exec, or both.
type stubConn struct {
	pingErr error
	execErr error

	mu       sync.Mutex
	executed []string
	closed   bool
}

func (s *stubConn) Ping(ctx context.Context) error { return s.pingErr }

func (s *stubConn) Exec(ctx context.Context, sql string) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.executed = append(s.executed, sql)
	return s.execErr
}

func (s *stubConn) Close() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.closed = true
	return nil
}

func (s *stubConn) sqlLog() []string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return append([]string(nil), s.executed...)
}

func (s *stubConn) wasClosed() bool {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.closed
}

// countingOpener wraps an Opener and counts invocations.
func countingOpener(open Opener) (Opener, *int) {
	calls := 0
	return func(ctx context.Context, dsn string) (Conn, error) {
		calls++
		return open(ctx, dsn)
	}, &calls
}

const testDSN = "postgres://ovaphlow:secret@127.0.0.1:5433/ovaphlow"

func TestNewDoesNotTouchNetwork(t *testing.T) {
	// Even a syntactically broken DSN must be accepted at construction time:
	// nothing is parsed or dialed until Connect.
	connector := New("not-a-valid-dsn-at-all")
	if connector == nil {
		t.Fatal("New returned nil")
	}
}

func TestConstructionDoesNotOpen(t *testing.T) {
	opened := false
	open := func(ctx context.Context, dsn string) (Conn, error) {
		opened = true
		return &stubConn{}, nil
	}
	_ = NewWith(testDSN, open, Migrate)
	if opened {
		t.Fatal("opener was called during construction; Connect must be lazy")
	}
}

func TestConnectOpensAndMigratesOnce(t *testing.T) {
	conn := &stubConn{}
	open, calls := countingOpener(func(ctx context.Context, dsn string) (Conn, error) {
		return conn, nil
	})
	connector := NewWith(testDSN, open, Migrate)

	if err := connector.Connect(context.Background()); err != nil {
		t.Fatalf("first Connect: %v", err)
	}
	if *calls != 1 {
		t.Fatalf("opener calls = %d, want 1", *calls)
	}
	executed := conn.sqlLog()
	if len(executed) == 0 {
		t.Fatal("no SQL was executed; migrations did not run")
	}
	if executed[0] != "BEGIN" || executed[len(executed)-1] != "COMMIT" {
		t.Fatalf("expected a transaction around the migration, got %v", executed)
	}

	// A second Connect reuses the open connection without reopening.
	if err := connector.Connect(context.Background()); err != nil {
		t.Fatalf("second Connect: %v", err)
	}
	if *calls != 1 {
		t.Fatalf("opener calls after second Connect = %d, want 1", *calls)
	}
	if len(conn.sqlLog()) != len(executed) {
		t.Fatal("second Connect re-ran migrations")
	}

	if err := connector.Ping(context.Background()); err != nil {
		t.Fatalf("Ping after Connect: %v", err)
	}
}

func TestConnectFailureReturnsErrorWithoutPanic(t *testing.T) {
	wantErr := errors.New("connection refused")
	open := func(ctx context.Context, dsn string) (Conn, error) {
		return nil, wantErr
	}
	connector := NewWith(testDSN, open, Migrate)

	err := connector.Connect(context.Background())
	if err == nil {
		t.Fatal("expected an error from the failing opener")
	}
	if !errors.Is(err, wantErr) {
		t.Fatalf("error %v does not wrap the opener error", err)
	}
	if !strings.Contains(err.Error(), "open database") {
		t.Fatalf("error %q does not explain the failing step", err)
	}
	if pingErr := connector.Ping(context.Background()); !errors.Is(pingErr, ErrNotConnected) {
		t.Fatalf("Ping after failed Connect = %v, want ErrNotConnected", pingErr)
	}
}

func TestMigrateFailureReturnsErrorAndRetriesOnNextConnect(t *testing.T) {
	conn := &stubConn{execErr: errors.New("syntax error")}
	open, calls := countingOpener(func(ctx context.Context, dsn string) (Conn, error) {
		return conn, nil
	})
	connector := NewWith(testDSN, open, Migrate)

	err := connector.Connect(context.Background())
	if err == nil {
		t.Fatal("expected an error from the failing migration")
	}
	if !strings.Contains(err.Error(), "run migrations") {
		t.Fatalf("error %q does not explain the failing step", err)
	}
	if !conn.wasClosed() {
		t.Fatal("the connection was not closed after a migration failure")
	}
	if pingErr := connector.Ping(context.Background()); !errors.Is(pingErr, ErrNotConnected) {
		t.Fatalf("Ping after failed Connect = %v, want ErrNotConnected", pingErr)
	}

	// The connector is unconnected, so a later Connect retries: once the
	// database recovers, migrations run.
	conn.execErr = nil
	if err := connector.Connect(context.Background()); err != nil {
		t.Fatalf("retry Connect: %v", err)
	}
	if *calls != 2 {
		t.Fatalf("opener calls = %d, want 2 after a retry", *calls)
	}
}

func TestPingBeforeConnectReturnsErrNotConnected(t *testing.T) {
	connector := NewWith(testDSN, func(ctx context.Context, dsn string) (Conn, error) {
		return &stubConn{}, nil
	}, Migrate)
	if err := connector.Ping(context.Background()); !errors.Is(err, ErrNotConnected) {
		t.Fatalf("Ping before Connect = %v, want ErrNotConnected", err)
	}
}

func TestCloseReleasesConnection(t *testing.T) {
	conn := &stubConn{}
	connector := NewWith(testDSN, func(ctx context.Context, dsn string) (Conn, error) {
		return conn, nil
	}, Migrate)
	if err := connector.Connect(context.Background()); err != nil {
		t.Fatalf("Connect: %v", err)
	}
	if err := connector.Close(); err != nil {
		t.Fatalf("Close: %v", err)
	}
	if !conn.wasClosed() {
		t.Fatal("Close did not close the underlying connection")
	}
	if err := connector.Ping(context.Background()); !errors.Is(err, ErrNotConnected) {
		t.Fatalf("Ping after Close = %v, want ErrNotConnected", err)
	}
	// Close on an already closed connector is a no-op, not an error.
	if err := connector.Close(); err != nil {
		t.Fatalf("second Close: %v", err)
	}
}

func TestConnectorRejectsInvalidDSNOnlyOnConnect(t *testing.T) {
	// The real pgx opener is used with a syntactically invalid DSN: the
	// connector must be constructible without error, and Connect must fail
	// with a handled error (no panic, no network involved).
	connector := New("postgres://user:pass@host with spaces:5432/db")
	if err := connector.Connect(context.Background()); err == nil {
		t.Fatal("expected an error for an invalid DSN")
	}
}
