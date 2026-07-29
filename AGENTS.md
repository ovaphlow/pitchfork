# Pitchfork — Monorepo AI Agent Context

## Monorepo 结构

```
pitchfork/
├── service-vertx-kotlin/    # Vert.x Kotlin 后端 — Trainova 与 Aceso API
│   ├── AGENTS.md             # 后端索引 → skills/
│   └── skills/               # Kotlin 后端技能文档
├── ui-astro/                 # Astro + React + Tailwind v4 前端
│   ├── AGENTS.md             # 前端索引 → skills/
│   └── skills/               # 前端技能文档
├── docs/                     # 规划文档与设计规格
│   ├── plans/                # 实施计划
│   └── specs/                # 设计规格
└── service-core-go-stdlib/   # Go 标准库服务（不在此文档覆盖范围）
```

## 服务端口

| 服务 | 端口 | 说明 |
|------|------|------|
| Trainova API (`apps:trainova`) | `8421` | 数据库 `ovaphlow`，路径前缀 `/crate-api/*` |
| Aceso API (`apps:aceso`) | `8422` | 数据库 `aceso`，路径前缀 `/crate-api/*` |
| PostgreSQL | `5432` | Aceso Compose 容器名 `pitchfork-aceso-db` |

前端应用按需启动：Trainova 的 auth/admin/worker 使用 `4321`/`4322`/`4323`，Aceso 使用 `4324`。

## 开发环境要求

- **JDK**: 25 (toolchain), Kotlin 2.3.x
- **Node.js**: 20+ (pnpm workspace)
- **Database**: PostgreSQL 17（Aceso Compose 容器 `pitchfork-aceso-db`，宿主端口 `5432`）
- **Container**: Podman / Docker

## 快速导航

| 目录 | 技术栈 | AGENTS.md | Skills |
|------|--------|-----------|--------|
| service-vertx-kotlin/ | Kotlin + Vert.x + jOOQ + PostgreSQL | [AGENTS.md](./service-vertx-kotlin/AGENTS.md) | [skills/](./service-vertx-kotlin/skills/) |
| ui-astro/ | Astro + React 19 + Tailwind v4 + pnpm | [AGENTS.md](./ui-astro/AGENTS.md) | [skills/](./ui-astro/skills/) |

完整的产品边界、端口和配置规则见 [docs/architecture.md](./docs/architecture.md)。

## 跨项目约定

### API 响应格式

**分页列表：**

```json
{
  "records": [...],
  "meta": { "total": 123 }
}
```

- 空结果返回 `{ "records": [], "meta": { "total": 0 } }`

**单条记录：** 直接返回 JSON 对象，不需要 `records` 包装。

**错误响应：**

```json
{ "error": "<message>" }
```

**HTTP 状态码：**

| 状态码 | 含义 |
|--------|------|
| `400` | 请求参数错误 |
| `401` | 未认证 / Token 过期 |
| `403` | 无权限 |
| `404` | 资源不存在 |
| `500` | 服务器内部错误 |

### ID 生成

所有表 ID 使用 **ULID**（26 位 Crockford Base32）：

```
01J8Z4Q5W6V7B8N9M0K1L2P3Q4
```

### 路由前缀

后端 API 统一格式：`/crate-api/<module>/v1/<resource>`

### 认证机制

- JWT（HS256）Bearer token 认证
- 密码传输使用 **RSA 公钥加密**（前端获取公钥 → `jsencrypt` 加密 → 后端解密）
- 前端产品作用域的 `@pitchfork/shared/*` 客户端会自动处理：
  - `Content-Type: application/json`
  - JWT 从 `localStorage` 注入 `Authorization: Bearer <token>`
  - 401 响应 → 清除 token → 跳转 `/login`

### 分页参数

- 请求：`limit` / `offset`（URL query params）
- 响应：`{ records: [...], meta: { total: N } }`

### 时间戳

- 列名：`created_at` / `updated_at`
- 类型：`OffsetDateTime`（带时区）

### JSONB 列

- 扩展属性列统一命名为 `metadata`，类型 `JSONB`

### 中文枚举值

业务枚举使用全中文（而非英文 code）：

| 业务域 | 枚举值 |
|--------|--------|
| 课程类型 | `线上` / `线下实操` |
| 作业下发 | `手动指派` / `自动触发` |
| 题目类型 | `单选` / `多选` / `判断` / `填空` / `看图识错` |
| 状态开关 | `启用` / `禁用` |
| 反馈评价 | `有用` / `没用` |

### Flyway 迁移文件

每个新增领域表由其所属 lib 独立管理迁移，放在自身模块内：

```
libs/<module>/src/main/resources/db/migration/
```

Flyway 通过 `classpath:db/migration` 在运行时聚合当前 app 所依赖的所有 lib jar 中的迁移文件。每个 app 仅执行其 `build.gradle.kts` 中依赖范围内的迁移——不依赖某 lib 的 app 不会收到该 lib 的迁移文件。Trainova 的 `apps/trainova` 中 `V5-V7` 是既有基线，不可移动或复制；后续变更必须归属到对应 lib。

版本号采用 `V{100×n}{2位序号}` 号段隔离：

| 号段  | lib         |
|-------|-------------|
| V1–99 | 已废弃（原 users） |
| V100+ | 已废弃（原 settings） |
| V200+ | inventories |
| V300+ | pharmacy    |
| V400+ | nursing     |
| V500+ | healthcare  |
| V600+ | Trainova 领域预留号段（knowledge 起） |

新增 lib 时按最大号段步进 100，避免冲突。完整号段表见 [docs/architecture.md](./docs/architecture.md)。

### CLI 工具 — rtk（Token 优化代理）

`rtk` 是安装在 `~/.local/bin/rtk` 的 CLI 代理，用于包装常见命令以减少 token 消耗。
**在 `bash` 中执行命令时**，优先使用 `rtk` 包装版本：

| 原始命令 | 替换为 | 效果 |
|----------|--------|------|
| `ls` | `rtk ls` | 移除颜色、精简输出 |
| `git status / diff / log` | `rtk git <sub>` | 紧凑 diff，仅变化的行 |
| `rg <pattern>` | `rtk rg <pattern>` | 去空白、分组输出 |
| `cat/head/tail <file>` | `rtk read <file>` | 智能过滤、截断 |
| `find ...` | `rtk find ...` | 紧凑树状输出 |
| `go test ...` | `rtk go test ...` | 仅显示失败 |
| `pnpm run / test` | `rtk pnpm ...` | 精简 npm 日志 |
| `grep ...` | `rtk grep ...` | 去空白、按文件分组 |

**不替换的场景**：管道组合（`rtk` 不解析管道）、交互式命令、不需要输出的命令。
**无需记忆**：支持的命令会自动匹配；不匹配时 `rtk` 透传原命令。

### 代码理解工具 — codegraph MCP

`codegraph` 是一个 MCP 插件（`codegraph serve --mcp`），提供 `codegraph_explore` 工具用于代码理解。**对于任何代码理解任务**（架构调查、符号查找、变更影响分析、代码库探索），必须优先使用 `mcp__codegraph__codegraph_explore`，而非内置的 `explore` 子代理工具或手动 `grep`/`read_file` 组合。

**使用规则：**
- 任何涉及"这个模块怎么工作的"、"这个符号定义在哪里"、"这些文件之间的关系"等代码理解问题 → **先用 codegraph_explore**
- 仅当 codegraph_explore 返回不充分时，回退到内置 `explore` 子代理或 `grep` + `code_index` 组合
- codegraph_explore 的返回值视同已读取源码，无需再 `read_file` 打开它返回的文件
