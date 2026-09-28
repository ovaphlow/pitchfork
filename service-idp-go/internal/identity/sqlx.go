package identity

import (
	"context"
	"database/sql"

	"github.com/jmoiron/sqlx"
)

// querier 是 sqlx 在事务内外共用的最小句柄接口。
// *sqlx.DB 与 *sqlx.Tx 都满足它，因此领域函数可以用同一套 helper
// 在「直连」和「事务内」两条路径间无缝切换，不会出现事务内误用非事务句柄
// 导致的连接池死锁（SQLite 连接池是 SetMaxOpenConns(1)）。
type querier interface {
	GetContext(ctx context.Context, dest any, query string, args ...any) error
	SelectContext(ctx context.Context, dest any, query string, args ...any) error
	ExecContext(ctx context.Context, query string, args ...any) (sql.Result, error)
	Rebind(query string) string
}

// newQuerier 把标准库连接包装成 sqlx 句柄。
// 对外 API 仍然只暴露 *sql.DB，调用方不需要感知 sqlx。
func newQuerier(database *sql.DB) *sqlx.DB {
	return sqlx.NewDb(database, "sqlite")
}

// bindNamed 把 :name 占位符替换成驱动可用的位置占位符。
// args 为 nil 时按「无参数查询」处理——sqlx.Named 直接收到 nil 会 panic。
func bindNamed(handle querier, query string, args map[string]any) (string, []any, error) {
	if args == nil {
		args = map[string]any{}
	}
	bound, values, err := sqlx.Named(query, args)
	if err != nil {
		return "", nil, err
	}
	return handle.Rebind(bound), values, nil
}

// namedGet 执行期望至多一行的查询，把结果扫描进 dest。
// dest 既可以是带 db tag 的结构体指针，也可以是 *int64 / *string 这类标量。
func namedGet(ctx context.Context, handle querier, dest any, query string, args map[string]any) error {
	bound, values, err := bindNamed(handle, query, args)
	if err != nil {
		return err
	}
	return handle.GetContext(ctx, dest, bound, values...)
}

// namedSelect 执行返回多行的查询，把结果扫描进 dest（必须是切片指针）。
func namedSelect(ctx context.Context, handle querier, dest any, query string, args map[string]any) error {
	bound, values, err := bindNamed(handle, query, args)
	if err != nil {
		return err
	}
	return handle.SelectContext(ctx, dest, bound, values...)
}

// namedExec 执行不关心影响行数的写操作。
func namedExec(ctx context.Context, handle querier, query string, args map[string]any) error {
	_, err := namedExecRows(ctx, handle, query, args)
	return err
}

// namedExecRows 执行写操作并返回受影响行数。
// 领域层用返回值为 0 判断「条件不匹配，什么都没改」。
func namedExecRows(ctx context.Context, handle querier, query string, args map[string]any) (int64, error) {
	bound, values, err := bindNamed(handle, query, args)
	if err != nil {
		return 0, err
	}
	result, err := handle.ExecContext(ctx, bound, values...)
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}
