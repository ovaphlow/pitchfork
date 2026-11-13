package shared

import (
	"database/sql"
	"strings"
	"time"

	"github.com/ovaphlow/pitchfork/platform/internal/identity"
)

const timestampLayout = "2006-01-02T15:04:05.000Z"

var errNoRows = sql.ErrNoRows

// newID 生成共享模块使用的 26 位 ULID。
func newID() (string, error) {
	return identity.NewULID(time.Now().UTC())
}

// timestampNow 生成与 Nexus SQLite 默认值同形的 UTC 时间戳（毫秒精度）。
func timestampNow() string {
	return time.Now().UTC().Format(timestampLayout)
}

// trimSpace 是 strings.TrimSpace 的简写，保持模块内校验代码简短。
func trimSpace(value string) string {
	return strings.TrimSpace(value)
}
