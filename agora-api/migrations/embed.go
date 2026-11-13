// Package migrations embeds the SQL files applied by internal/migrate at
// process start. Keeping the embed directive here means the SQL files stay in
// their own directory and the binary needs no migration path at runtime.
package migrations

import "embed"

//go:embed *.sql
var FS embed.FS
