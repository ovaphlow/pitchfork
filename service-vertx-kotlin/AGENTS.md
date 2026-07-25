# AGENTS.md — service-vertx-kotlin (Skills Index)

## Overview

Vert.x Kotlin monorepo with two independently deployable products: Trainova (manufacturing training) and Aceso (healthcare operations). Shared libraries provide platform capabilities; product-domain libraries are mounted only by their owning application.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 2.3.x |
| Framework | Vert.x 4.5.x (Web, Config, Auth JWT) |
| Database | PostgreSQL 17 |
| DB Access | jOOQ 3.19 + Flyway 版本化迁移 |
| Build | Gradle 8.14 wrapper (Kotlin DSL) |
| Auth | JWT (HS256) + RSA 加密密码传输 |
| Logging | SLF4J + Logback + Logstash JSON encoder |
| JDK | 21+ (toolchain = 25) |

## API Base

所有 API 统一挂载于 `/crate-api/<module>/v1/<resource>`。Trainova 使用 `8421`，Aceso 使用 `8422`。

## Architecture

```
service-vertx-kotlin/
├── apps/
│   ├── trainova/            # 培训产品入口（8421）
│   └── aceso/               # 医疗运营产品入口（8422）
└── libs/
    ├── auth/                # 登录/注册/JWT
    ├── permissions/          # RBAC + ReBAC + ABAC
    ├── users/               # 员工管理
    ├── knowledge/           # Trainova：知识库
    ├── skills/              # Trainova：技能/岗位/证书
    ├── trainings/           # Trainova：课程/章节/作业
    ├── exams/               # Trainova：题库/试卷/考试记录
    ├── onsite/              # Trainova：现场设备扫码/离线缓存
    ├── analytics/           # Trainova：聚合仪表盘
    ├── inventories/         # Aceso：物资与批次
    ├── pharmacy/            # Aceso：药房
    ├── nursing/             # Aceso：护理
    ├── healthcare/          # 孵化中；未挂载前不得由 app 依赖
    ├── logging/              # JSON 结构化日志
    ├── database/            # DB连接/Flyway/jOOQ codegen
    └── common/              # Ulid, RsaCrypto 工具
```

`settings`、`messages`、`files` 已迁移至 `service-nexus-shared`。Aceso 与其他
业务服务必须通过 Nexus 的 HTTP API（`/crate-api/shared/v1/*`）使用这些能力，
不得恢复对已删除 Kotlin lib 的依赖。

完整产品边界、配置和迁移策略见 [`../docs/architecture.md`](../docs/architecture.md)。

## Available Skills

| Skill | Description |
|-------|-------------|
| [Module Pattern](./skills/module-pattern.md) | Routes.kt / Service.kt 代码规范与模式 |
| [Build & Deploy](./skills/build-and-deploy.md) | Gradle 构建、运行、JAR 过时问题 |
| [Adding a Module](./skills/adding-module.md) | 新增模块完整 Checklist |
| [DB Migration](./skills/db-migration.md) | Flyway + jOOQ codegen 流程 |
| [API Conventions](./skills/api-conventions.md) | 分页/错误/ULID/JSONB 约定 |
| [Common Pitfalls](./skills/common-pitfalls.md) | 常见陷阱与解决方案 |
| [SKILLS.md](./SKILLS.md) | API 端点完整参考（自动生成级） |
