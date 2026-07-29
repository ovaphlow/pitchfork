# AGENTS.md — ui-astro (Skills Index)

## Overview

Astro + React 19 + Tailwind v4 frontend monorepo for two products: Trainova (manufacturing training) and Aceso (healthcare operations). The UI is dark themed and Chinese first.

## 限制

**开发服务仅限用户操作。** AI agent 不得启动/停止/管理 Astro dev server 进程（`pnpm dev`, `pnpm dev:*`, `pnpm preview`, `astro dev`）。AI agent 仅可执行编译或构建操作：

- `pnpm build` — 全部 apps 构建 (astro check + astro build)
- `pnpm --filter <app> build` — 单个 app 构建

## Quick Start

```bash
cd ui-astro
pnpm install
pnpm dev          # @pitchfork/auth (port 4321) — 仅用户
pnpm dev:admin    # @pitchfork/admin (port 4322) — 仅用户
pnpm dev:worker   # @pitchfork/worker (port 4323) — 仅用户
pnpm dev:aceso    # @pitchfork/aceso (port 4324) — 仅用户
pnpm build        # 全部 apps 构建 (AI agent 可执行)
```

## Architecture

```
ui-astro/
├── apps/                          # 路由页面应用 (pnpm workspace)
│   ├── auth/          (4321)      # 登录注册、员工管理、部门设置
│   │   └── src/
│   │       ├── layouts/           # AuthLayout, DashboardLayout (.astro)
│   │       ├── pages/             # 路由页面 (.astro), 仅路由职责
│   │       ├── components/        # 页面级 React 组件
│   │       └── styles/global.css  # 主题配置 (@theme OKLCH tokens)
│   ├── admin/         (4322)      # 管理后台 - 知识库/课程/考试/技能矩阵
│   │   └── src/
│   │       ├── layouts/AdminLayout.astro
│   │       ├── pages/             # dashboard, courses, knowledge, exams, ...
│   │       └── components/        # 管理功能 React 组件
│   ├── worker/        (4323)      # 员工移动端 - 学习/考试/知识搜索/扫码
│   │   └── src/
│   │       ├── layouts/MobileLayout.astro
│   │       ├── pages/             # search, training, exam, profile, scan, ...
│   │       └── components/        # 员工端 React 组件
│   └── aceso/         (4324)      # 医疗运营后台
│       └── src/                   # Aceso routes and page components
└── packages/                      # 共享包
    ├── shared/                    # @pitchfork/shared - 产品作用域 API 客户端 + TS 类型
    │   └── src/{trainova,aceso}.ts # 每个产品的允许 API surface
    └── ui/                        # @pitchfork/ui - React 组件库
        └── src/index.tsx          # Button, Input, Table, Modal, Card, ...
```

## Convention: Apps = Routes, Packages = Logic

- `apps/*/src/pages/` — **仅 Astro 页面**（薄路由层，不做业务逻辑）
- `apps/*/src/components/` — 页面级 React 组件
- `packages/shared/` — API 客户端、TS 类型、工具函数
- `packages/ui/` — 共享 UI 组件（全局复用，不含业务逻辑）
- Trainova app 只能从 `@pitchfork/shared/trainova` 导入 API
- Aceso 只能从 `@pitchfork/shared/aceso` 导入 API

## API 集成

- API base: 每个 app 的 `.env` 中必须设置 `PUBLIC_API_URL`；没有后备地址
- 认证: JWT Bearer token 存 `localStorage`
- 密码传输: RSA 加密 (`jsencrypt` → 后端 `/auth/v1/public-key`)
- Token 过期自动 401 → 跳转 `/login`

完整产品边界、端口和环境变量见 [`../docs/architecture.md`](../docs/architecture.md)。

## Available Skills

| Skill | Description |
|-------|-------------|
| [Project Structure](./skills/project-structure.md) | Monorepo 架构与约定详解 |
| [Adding a Page](./skills/add-page.md) | 新增功能页面完整工作流 |
| [API Integration](./skills/api-integration.md) | @pitchfork/shared API 客户端使用 |
| [Styling Guide](./skills/styling-guide.md) | Tailwind v4 主题系统与 OKLCH tokens |
| [UI Component Guide](./skills/ui-component-guide.md) | @pitchfork/ui 组件库参考 |
