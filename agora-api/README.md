# agora-api

Agora 的后端服务：Go 标准库路由、PostgreSQL（pgx）、JSONL 日志。

## 技术栈

- Go 1.26
- `net/http` + `http.ServeMux`（Go 1.22+ 方法与路径模式，无 Web 框架）
- PostgreSQL 17 + `pgx/v5` 连接池
- `log/slog` JSON handler，同时写 stdout 与 `logs/app.jsonl`
- 配置从环境变量读取，启动时自动加载 `.env`（环境变量优先）

## 本地运行

需要 Go 1.26+、Podman 或 Docker。

```bash
cp .env.example .env
podman compose up -d     # 或 docker compose up -d
go mod download
go run ./cmd/api
```

服务默认监听 `:8080`。启动时会 ping 数据库；数据库不可用时进程直接退出。

```bash
go test ./...
go build ./...
```

## 本地数据库

`compose.yaml` 只提供 PostgreSQL，供本机运行的 API 使用；默认账号与 `.env.example` 中的 `DATABASE_URL` 一致。

```bash
podman compose up -d     # 启动，等待 healthcheck 变为 healthy
podman compose ps
podman compose down      # 停止，数据保留在 ./.data/pg
```

| 变量 | 默认值 | 说明 |
|---|---|---|
| `AGORA_DB_PORT` | `5432` | 宿主机端口；被占用时可改为其他端口并同步修改 `DATABASE_URL` |
| `AGORA_DB_PASSWORD` | `agora` | 数据库密码；修改后同步更新 `DATABASE_URL` |

容器名 `pitchfork-agora-db`，只监听 `127.0.0.1`。数据目录 `./.data/pg` 不纳入版本控制。

## 端点

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/healthz` | 存活检查，不访问数据库 |
| `GET` | `/readyz` | 就绪检查，ping PostgreSQL |
| `GET` | `/crate-api/agora/v1/status` | 服务状态，供前端探测 |
| `POST` | `/crate-api/agora/v1/auth/email-link` | 申请一次性登录邮件；入参 `{email, display_name?}`；未知邮箱/限流/投递失败同样返回 `202 {"status":"accepted"}` |
| `POST` | `/crate-api/agora/v1/auth/email-link/exchange` | 兑换邮件中的一次性 token（`{token}`），转发 platformd 会话 Cookie 并返回会话 JSON；token 一次性、10 分钟有效 |
| `POST` | `/crate-api/agora/v1/auth/login` | 密码登录（存量账号兜底）：校验 IDP 凭据并建立会话 |
| `GET` | `/crate-api/agora/v1/auth/session` | 当前会话 + 共享角色/权限 |
| `DELETE` | `/crate-api/agora/v1/auth/session` | 登出并清除会话 Cookie |
| `POST` | `/crate-api/agora/v1/files` | 上传审核材料（营业执照）；multipart 字段名 `file`，仅收 JPG/PNG/WebP/PDF，≤ 8 MB；转发给 platformd 共享 files 并返回 `{id, content_url}` |
| `GET` | `/crate-api/agora/v1/files/{id}/content` | 读取已上传文件；需要有效会话，转发 platformd 共享 files 内容 |
| `GET` | `/crate-api/agora/v1/organizations` | 机构列表；公开调用仅返回已认证机构，支持 `city`、`org_type`、`q`、`limit`、`offset`；平台管理员可用 `status` 筛选任一审核状态，`status=全部` 返回所有状态 |
| `PUT` | `/crate-api/agora/v1/me/intent` | 记录本账号最近选择的身份：`{"user_type":"job_seeker"\|"recruiter"}`；仅偏好，不参与权限 |
| `GET` | `/crate-api/agora/v1/admin/subjects` | Agora 用户列表（管理端「求职方」）；仅平台管理员，以 `subject_intents` 为注册表，再按 ID 向 platformd 批量取账号信息并附 `user_type` |
| `PATCH` | `/crate-api/agora/v1/admin/subjects/{id}` | 启用/禁用平台账号；仅平台管理员，转发 `X-CSRF-Token` 到 platformd |
| `POST` | `/crate-api/agora/v1/organizations` | 创建机构；当前主体成为所有者，状态 `待审核`；`metadata.credit_code` 与 `metadata.license_file_id` 作为审核材料 |
| `GET` | `/crate-api/agora/v1/organizations/mine` | 我所属的机构及其中的角色 |
| `GET` | `/crate-api/agora/v1/organizations/{id}` | 机构详情；未认证机构只对成员可见 |
| `PATCH` | `/crate-api/agora/v1/organizations/{id}` | 修改机构档案；所有者/管理员 |
| `POST` | `/crate-api/agora/v1/organizations/{id}/review` | 审核机构；仅平台管理员 |
| `GET` | `/crate-api/agora/v1/organizations/{id}/members` | 成员列表；所有者/管理员 |
| `POST` | `/crate-api/agora/v1/organizations/{id}/members` | 添加成员；所有者/管理员 |
| `DELETE` | `/crate-api/agora/v1/organizations/{id}/members/{subject_id}` | 移除成员；所有者不可被移除 |
| `GET` | `/crate-api/agora/v1/organizations/{id}/positions` | 本机构全部岗位，含草稿；成员 |
| `GET` | `/crate-api/agora/v1/positions` | 岗位列表；公开，仅 `招聘中` 且未过期，支持 `city`、`category`、`employment_type`、`education`、`experience`、`salary_min`、`organization_id`、`q`、`limit`、`offset` |
| `POST` | `/crate-api/agora/v1/positions` | 新建岗位，初始为 `草稿`；机构成员 |
| `GET` | `/crate-api/agora/v1/positions/{id}` | 岗位详情；未公开岗位只对成员可见，公开访问累加 `view_count` |
| `PATCH` | `/crate-api/agora/v1/positions/{id}` | 修改岗位；机构成员 |
| `POST` | `/crate-api/agora/v1/positions/{id}/status` | 状态流转：`草稿 → 招聘中 → 已暂停/已关闭`，14 天后自动 `已停招`；`已停招` / `已关闭 → 招聘中` 恢复 7 天；上线要求机构已认证 |
| `DELETE` | `/crate-api/agora/v1/positions/{id}` | 删除岗位；招聘专员只能删除自己创建的岗位 |

### 业务规则

- 机构审核状态：`待审核 → 已认证 / 已驳回 / 已停用`，只有 `已认证` 机构能发布岗位。
- 一个主体可以创建并管理多家机构（例如集团、连锁），每家机构独立审核；约束只有 `name + city` 唯一，不限制每个主体的机构数量。机构成员角色按机构隔离。
- 机构审核材料：`metadata.credit_code`（统一社会信用代码）与 `metadata.license_file_id`（营业执照，指向 platformd 共享 files.id）。文件经 `POST /crate-api/agora/v1/files` 上传，营业执照只接受图片与 PDF，单文件不超过 8 MB。
- 机构成员角色：`所有者`（每机构唯一）> `管理员` > `招聘专员`。所有者与管理员可改档案、管成员；三者都可维护岗位。
- `GET /organizations` 默认只看已认证机构；带 `status` 参数时仅 `platform_admin` 可用，其他调用 403，
  避免待审核记录外泄。审核接口 `POST /organizations/{id}/review` 同样只接受平台管理员。
- 岗位发布后进入 14 天（2 周）招聘窗口（`open_until = NOW() + 14 days`）。窗口到期后，
  store 在读取或状态流转时惰性把 `招聘中` 改为 `已停招` 并停止公开展示；不需要后台任务。
- `已停招` 与 `已关闭` 的岗位都可通过 `POST /positions/{id}/status` 提交 `招聘中` 重新开放，
  窗口为点击时刻起 7 天；重新开放同样要求机构已认证。`已停招` 不能由调用方直接设置，
  `已关闭` 也不能直接转为 `已暂停`。
- 机构自定义的 `expires_at` 仍按原规则推导「已过期」，不落状态。
- 公开接口只暴露已认证机构与 `招聘中` 岗位；其余记录对非成员返回 404 而不是 403。

### 认证边界

agora-api 是浏览器与两个内部服务之间的 BFF，浏览器只看到 agora-api：

```text
browser ──cookie──▶ agora-api ──Cookie──▶ platformd  GET /session
                        │
                        └────────Cookie──▶ platformd  GET /crate-api/v1/subject-permissions
```

- 注册/登录请求转发到 `platformd`，`Set-Cookie` 原样回传；agora-api 不解析、
  不保存会话 token。
- 登录成功后读取 `platformd` 共享 API 的 `GET /crate-api/v1/subject-permissions?subject_id=`
  （只查当前主体），把角色和权限码附在会话响应里。共享 API 不可用时登录仍成功，
  `nexus_ready=false`（字段名沿用）。
- 所有 platformd 共享调用（权限、文件上传/下载）都带 `X-App-Code: agora`；platformd 按该值
  隔离 settings / files / messages / interactions / roles，Agora 看不到其他产品的数据。
- `subject_intents` 同时是 Agora 用户注册表：每次解析有效 Agora 会话都会确保登记；迁移
  `0006` 允许 `user_type` 为空，并从机构所有者/成员与岗位创建人回填存量用户。管理台只列
  这张表里的主体，因此 Aceso 等其它产品的账号不会出现在「求职方」。
- 登出会把 IDP 的 CSRF Cookie 值作为 `X-CSRF-Token` 转发，确保服务端会话被真正
  撤销，而不是只清浏览器 Cookie。
- 浏览器 Cookie 使用 `SameSite=Lax`；面向互联网部署必须使用 HTTPS，并让 UI 与
  API 位于同一站点（同一 registrable domain）。`ALLOWED_ORIGINS` 必须是精确
  Origin，不支持 `*` + credentials。
- agora-api 位于反向代理后时，把代理 CIDR 配到 `TRUSTED_PROXY_CIDRS`；它会从
  `X-Forwarded-For` 取最右侧非可信地址，并作为 IDP 登录限流的来源地址。直连
  部署下客户端无法通过伪造该头绕过限流。
- `ALLOWED_ORIGINS`、`IDENTITY_BASE_URL`、`NEXUS_BASE_URL` 见环境变量表。

前端开发地址 `http://localhost:4321` 与 `http://127.0.0.1:4321` 默认为允许的 Origin。
通过 LAN IP 或其它端口访问 UI 时，必须把浏览器实际 Origin 加入 `ALLOWED_ORIGINS`，否则 fetch 会被 CORS 拦截。

```bash
curl -s http://localhost:8080/healthz
curl -s http://localhost:8080/readyz
curl -s http://localhost:8080/crate-api/agora/v1/status
```

## 环境变量

| 变量 | 默认值 | 说明 |
|---|---|---|
| `HTTP_ADDR` | `:8080` | HTTP 监听地址 |
| `DATABASE_URL` | 无 | 必填，PostgreSQL DSN |
| `DB_MAX_CONNS` | `10` | 连接池上限 |
| `DB_CONNECT_TIMEOUT` | `5s` | 启动时连接超时 |
| `READINESS_TIMEOUT` | `2s` | `/readyz` ping 超时 |
| `SHUTDOWN_TIMEOUT` | `10s` | 优雅退出超时 |
| `AUTO_MIGRATE` | `true` | 启动时执行 `migrations/` 下未应用的 SQL；设为 `false` 则由部署流程单独执行 |
| `ALLOWED_ORIGINS` | `http://localhost:4321,http://127.0.0.1:4321` | 逗号分隔的 CORS 来源；精确匹配 |
| `IDENTITY_BASE_URL` | `http://127.0.0.1:8420` | `platformd` 地址 |
| `NEXUS_BASE_URL` | `http://127.0.0.1:8420` | `platformd` 共享 API 地址（`/crate-api/v1`） |
| `AUTH_HTTP_TIMEOUT` | `3s` | 调用 platformd 身份端点与共享 API 的超时 |
| `FILES_HTTP_TIMEOUT` | `30s` | 访问 platformd 共享 files 的超时；上传需要读取与哈希请求体，故独立配置 |
| `TRUSTED_PROXY_CIDRS` | `127.0.0.1/32,::1/128` | 允许提供 `X-Forwarded-For` 的反向代理 |
| `LOG_LEVEL` | `info` | `debug` / `info` / `warn` / `error` |
| `LOG_FILE` | `logs/app.jsonl` | JSONL 文件；设为 `-` 时只写 stdout |

## 日志

每条日志由 `slog.JSONHandler` 输出为单行 JSON，包含 `time`、`level`、`msg`、`service`。HTTP 请求额外记录 `request_id`、`method`、`path`、`status`、`bytes`、`duration_ms`、`remote_addr`。

```json
{"time":"2026-10-27T12:00:00.000Z","level":"INFO","msg":"http request","service":"agora-api","request_id":"...","method":"GET","path":"/healthz","status":200,"bytes":15,"duration_ms":0.12,"remote_addr":"127.0.0.1:50000"}
```

日志文件以追加方式写入，启动时自动创建 `logs/`。当前不内置轮转，部署时由外部日志采集或轮转工具处理。

## 结构

```text
compose.yaml             # 本地 PostgreSQL（Podman / Docker）
migrations/              # 内嵌 SQL 迁移 + embed.go；规则见 migrations/README.md
cmd/api/                 # 进程入口、HTTP server 生命周期、启动迁移
internal/apierr/         # 领域错误类型与 HTTP 状态映射
internal/config/         # 环境变量与 .env
internal/database/      # pgx 连接池
internal/dbx/            # 列表查询的 WHERE 条件拼装
internal/httpapi/        # 原生 ServeMux、中间件、响应、认证 BFF、机构与岗位接口
internal/id/             # 26 位 Crockford Base32 ULID
internal/identityclient/ # platformd 会话与注册调用
internal/migrate/        # 进程内迁移 runner（advisory lock + schema_migrations）
internal/nexusclient/    # platformd 共享 API（/crate-api/v1）基础数据读取与 files 上传/下载代理
internal/organizations/  # 机构、成员、审核状态机
internal/positions/      # 招聘岗位与发布状态机
internal/logging/        # JSONL slog
```

## 迁移与测试

迁移文件内嵌进二进制，启动时按文件名顺序执行，记录在 `schema_migrations`。
已应用的迁移文件若被修改，启动会失败（checksum 漂移保护）。字段切分规则、
`metadata` 契约与「JSONB 键提列」的做法见 [`migrations/README.md`](migrations/README.md)。

```bash
go test ./...                       # 单元测试，不访问数据库
go build ./...

# 契约与 SQL 联合测试：需要一个一次性数据库，默认跳过
podman run -d --name agora-it-db -e POSTGRES_PASSWORD=check -e POSTGRES_USER=check \
  -e POSTGRES_DB=check -p 127.0.0.1:55432:5432 docker.io/library/postgres:17
AGORA_TEST_DATABASE_URL='postgres://check:check@127.0.0.1:55432/check?sslmode=disable' \
  go test -tags integration -count=1 ./internal/integration/
podman rm -f agora-it-db
```

`internal/integration` 会 `TRUNCATE` 业务表，只能指向一次性或可销毁的测试库。
