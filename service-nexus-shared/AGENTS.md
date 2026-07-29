# AGENTS.md — service-nexus-shared

## 限制

**开发服务仅限用户操作。** AI agent 不得启动/停止/管理 nexus 开发服务进程（包括 `cargo run`）。AI agent 仅可执行编译或构建操作：

- `cargo build` — 编译
- `cargo test` — 测试
- `cargo check` — 类型检查

## 快速参考

| 操作 | 命令 | 谁可执行 |
|------|------|----------|
| 启动服务 | `cargo run`（需 `.env`） | 仅用户 |
| 编译 | `cargo build` | AI agent |
| 类型检查 | `cargo check` | AI agent |
| 测试 | `cargo test` | AI agent |

数据库：SQLite，`sqlx` migrate。日志：`logs/nexus.jsonl.YYYY-MM-DD`。
