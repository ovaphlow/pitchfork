# Pitchfork — Monorepo AI Agent Context

## Monorepo 结构

service-core-go-stdlib 忽略

```
pitchfork/
├── service-vertx-kotlin/    # Vert.x Kotlin 后端 — 克拉特培训平台核心 API
│   ├── AGENTS.md             # 后端索引 → skills/
│   └── skills/               # Kotlin 后端技能文档
├── ui-astro/                 # Astro + React + Tailwind v4 前端
│   ├── AGENTS.md             # 前端索引 → skills/
│   └── skills/               # 前端技能文档
```

## 快速导航

| 目录 | 技术栈 | AGENTS.md | Skills |
|------|--------|-----------|--------|
| service-vertx-kotlin/ | Kotlin + Vert.x + jOOQ + PostgreSQL | [AGENTS.md](./service-vertx-kotlin/AGENTS.md) | [skills/](./service-vertx-kotlin/skills/) |
| ui-astro/ | Astro + React 19 + Tailwind v4 + pnpm | [AGENTS.md](./ui-astro/AGENTS.md) | [skills/](./ui-astro/skills/) |

## 开发环境要求

- **JDK**: 25 (toolchain), Kotlin 2.3.x
- **Node.js**: 20+ (pnpm workspace)
- **Database**: PostgreSQL 17 (宿主端口 55432)
- **Container**: Podman / Docker (Consul + Traefik 服务治理)

## API 网关架构

Traefik (`:8080`, Consul Catalog Provider) 统一入口 → 内部微服务路径路由：

| 前缀 | 目标 | 端口 |
|------|------|------|
| `/crate-api/*` | service-vertx-kotlin | `:8421` |
| `/core/*` | service-core-go-stdlib | `:8431` |
| `/infra/*` | service-infra-axum | `:8432` |

## 跨项目约定

后端 API 响应格式：`{ records: [...], meta: { total: N } }`（分页），`{ "error": "<message>" }`（错误）。
所有 ID 使用 ULID (26 位 Crockford Base32)。
