# AGENTS.md — platform

## 服务运行授权

**用户已明确授权（2026-08-02）AI agent 可启动/停止/管理 platformd 开发服务进程**（包括 `go run ./cmd/platformd/`、`./bin/platformd`）。此授权覆盖原「开发服务仅限用户操作」的限制。

## 快速参考

| 操作 | 命令 | 谁可执行 |
|------|------|----------|
| 启动服务 | `./bin/platformd`（需 `.env`） | AI agent（已获授权）/ 用户 |
| 停止服务 | 终止对应进程 | AI agent（已获授权）/ 用户 |
| 编译 | `go build -o bin/platformd ./cmd/platformd/` | AI agent |
| 测试 | `go test ./...` | AI agent |
| 迁移 Nexus 数据 | `go run ./cmd/nexus-import -dry-run`（先停写流量） | AI agent |

数据库：SQLite，路径 `.data/platformd.sqlite`。端口 `8420`。

共享能力：`/crate-api/v1/*`（原 `service-nexus-shared`，见 `../docs/plans/055.nexus-shared-merge-into-platform.md`）。文件目录 `PLATFORMD_FILES_DIR`（默认 `.data/files`），上传上限 `PLATFORMD_MAX_UPLOAD_BYTES`（默认 `20971520`）。
