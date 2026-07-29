# Nexus Shared

Nexus Shared 提供 Settings、Messages、Files、Interactions 四项跨业务基础能力。服务使用 Rust、Axum、SQLite/WAL，并通过 `service-idp-go` 的 HTTP 会话端点认证所有业务请求。

## Run

```bash
cp .env.example .env
cargo run
```

默认监听 `127.0.0.1:8421`。首次启动会创建 SQLite 文件、应用 `/migrations` 中的迁移，并创建 `NEXUS_FILES_DIR` 指定的本地文件目录。

运行前必须启动 IDP。Nexus 会把请求中的 `Cookie` 头转发到 `GET /crate-api/identity/v1/session`；没有有效 `完整` 会话的请求返回 RFC 9457 `401` 响应。Nexus 不解析、不保存浏览器会话 token。

## HTTP API

- 健康检查：`GET /healthz`
- 受保护根路径：`/crate-api/shared/v1`
- Settings：`/settings`
- Messages：`/messages`
- Files：`/files`，上传为 `POST /files/upload` 的 `multipart/form-data`，字段名 `file`
- Interactions：`/interactions`

列表使用 `page`（默认 `1`）和 `page_size`（默认 `20`，最大 `100`），直接返回 JSON 数组。错误使用 RFC 9457 Problem Details。Aceso 可将其 `/crate-api/shared/v1/*` 请求透明代理到本服务；生产环境通过 Aceso 的 `nexus.base-url` 配置指定地址。
