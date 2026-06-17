# AGENTS.md — service-vertx-kotlin (Skills Index)

## Overview

**Crate** (克拉特) — 制造企业员工培训与质量管理平台。Vert.x Kotlin 后端，管理员工技能发展的全生命周期：知识库、培训课程、考试评估、现场设备扫码、AI 问答、主动推送规则、RBAC/ReBAC/ABAC 权限控制、分析仪表盘。

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

所有 API 统一挂载于 `/crate-api/<module>/v1/<resource>`，端口 `8421`。

## Architecture

```
service-vertx-kotlin/
├── apps/service/            # 入口 (Main.kt, 子路由挂载)
└── libs/                    # 领域模块
    ├── auth/                # 登录/注册/JWT
    ├── settings/            # 部门管理 + KV 配置
    ├── files/               # 静态文件服务
    ├── permission/          # RBAC + ReBAC + ABAC
    ├── messages/            # 通知消息
    ├── users/               # 员工管理
    ├── knowledge/           # 知识库
    ├── skills/              # 技能/岗位/证书
    ├── training/            # 课程/章节/作业
    ├── exam/                # 题库/试卷/考试记录
    ├── onsite/              # 现场设备扫码/离线缓存
    ├── ai-assistant/        # AI 问答/FAQ/推送规则
    ├── analytics/           # 聚合仪表盘
    ├── database/            # DB连接/Flyway/jOOQ codegen
    └── common/              # Ulid, RsaCrypto 工具
```

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
