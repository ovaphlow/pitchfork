# AGENTS.md — service-idp-go

## 服务运行授权

**用户已明确授权（2026-08-02）AI agent 可启动/停止/管理 identityd 开发服务进程**（包括 `go run ./cmd/identityd/`、`./bin/identityd`）。此授权覆盖原「开发服务仅限用户操作」的限制。

## 快速参考

| 操作 | 命令 | 谁可执行 |
|------|------|----------|
| 启动服务 | `./bin/identityd`（需 `.env`） | AI agent（已获授权）/ 用户 |
| 停止服务 | 终止对应进程 | AI agent（已获授权）/ 用户 |
| 编译 | `go build -o bin/identityd ./cmd/identityd/` | AI agent |
| 测试 | `go test ./...` | AI agent |

数据库：SQLite，路径 `.data/identityd.sqlite`。端口 `8420`。
