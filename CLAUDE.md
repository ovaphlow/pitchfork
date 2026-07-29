# Pitchfork — Monorepo

根 `AGENTS.md` 已自动加载。按需参考以下子项目文档：

## 后端 (service-vertx-kotlin)

- 读取 `service-vertx-kotlin/AGENTS.md` 了解模块结构
- 按需参考 `service-vertx-kotlin/skills/` 下的技能文档（API 约定、模块模式、构建部署、数据库迁移等）

## 身份服务 (service-idp-go)

- 读取 `service-idp-go/AGENTS.md` 了解限制与操作命令
- Go 标准库 + SQLite 身份认证服务，端口 `8420`

## 共享服务 (service-nexus-shared)

- 读取 `service-nexus-shared/AGENTS.md` 了解限制与操作命令
- Rust + Axum + SQLite 通用数据服务

## 前端 (ui-astro)

- 读取 `ui-astro/AGENTS.md` 了解 app 架构
- 按需参考 `ui-astro/skills/` 下的技能文档（组件库、样式指南、API 集成、页面工作流等）
