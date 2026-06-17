# Pitchfork — Monorepo AI Agent Context

## 项目简介

Pitchfork 是哈尔滨乔汉科技有限公司的招聘与培训一体化平台，包含 **龙招聘 / 龙江学子就业网** 以及 **克拉特 (Crate) 制造企业员工培训与质量管理平台**。

## Monorepo 结构

```
pitchfork/
├── service-vertx-kotlin/    # Vert.x Kotlin 后端 — 克拉特培训平台核心 API
│   ├── AGENTS.md             # 后端索引 → skills/
│   └── skills/               # Kotlin 后端技能文档
├── ui-astro/                 # Astro + React + Tailwind v4 前端
│   ├── AGENTS.md             # 前端索引 → skills/
│   └── skills/               # 前端技能文档
├── service-core-go-stdlib/   # Go 后端微服务 (龙招聘核心)
├── service-infra-axum/       # Rust/Axum 基础架构服务
├── service-consul-image/     # Consul Docker 镜像
├── service-traefik-image/    # Traefik Docker 镜像
├── consul.d/                 # Consul 服务发现配置
├── traefik/                  # Traefik 动态路由配置
├── docker-compose.yml        # Consul + Traefik + PostgreSQL 开发环境
├── PODMAN.md                 # Podman 部署说明
├── project2606.md            # 项目设计文档
└── package.json              # pnpm workspace root
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
