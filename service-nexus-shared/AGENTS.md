# AGENTS.md — service-nexus-shared

## 服务运行授权

**用户已明确授权（2026-08-02）AI agent 可启动/停止/管理 nexus 开发服务进程**（包括 `cargo run`）。此授权覆盖原「开发服务仅限用户操作」的限制。

## 快速参考

| 操作 | 命令 | 谁可执行 |
|------|------|----------|
| 启动服务 | `cargo run`（需 `.env`） | AI agent（已获授权）/ 用户 |
| 停止服务 | 终止对应进程 | AI agent（已获授权）/ 用户 |
| 编译 | `cargo build` | AI agent |
| 类型检查 | `cargo check` | AI agent |
| 测试 | `cargo test` | AI agent |

数据库：SQLite，`sqlx` migrate。日志：`logs/nexus.jsonl.YYYY-MM-DD`。
