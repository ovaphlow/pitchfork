package database

import (
	"context"
	"fmt"

	"github.com/ovaphlow/pitchfork/service-prototype/db/migrations"
)

// Migrate runs every embedded migration against the connection, in strictly
// increasing version order. Each migration executes inside a transaction so
// a failure leaves no partial schema behind; the error is returned to the
// caller, which decides whether to keep going without a database.
func Migrate(ctx context.Context, conn Conn) error {
	list, err := migrations.Parse(migrations.Files)
	if err != nil {
		return err
	}
	for _, migration := range list {
		if err := runMigration(ctx, conn, migration); err != nil {
			return fmt.Errorf("migration %06d_%s: %w", migration.Version, migration.Name, err)
		}
	}
	return nil
}

func runMigration(ctx context.Context, conn Conn, migration migrations.Migration) error {
	if err := conn.Exec(ctx, "BEGIN"); err != nil {
		return err
	}
	if err := conn.Exec(ctx, migration.SQL); err != nil {
		_ = conn.Exec(ctx, "ROLLBACK")
		return err
	}
	if err := conn.Exec(ctx, "COMMIT"); err != nil {
		_ = conn.Exec(ctx, "ROLLBACK")
		return err
	}
	return nil
}
