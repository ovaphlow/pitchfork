# AGENTS.md — service-idp-go

## 限制

**开发服务仅限用户操作。** AI agent 不得启动/停止/管理 identityd 开发服务进程（包括 `go run ./cmd/identityd/`、`./bin/identityd`）。AI agent 仅可执行编译或构建操作：

- `go build ./cmd/identityd/` — 编译
- `go test ./...` — 测试

## 快速参考

| 操作 | 命令 | 谁可执行 |
|------|------|----------|
| 启动服务 | `./bin/identityd`（需 `.env`） | 仅用户 |
| 编译 | `go build -o bin/identityd ./cmd/identityd/` | AI agent |
| 测试 | `go test ./...` | AI agent |

数据库：SQLite，路径 `.data/identityd.sqlite`。端口 `8420`。
