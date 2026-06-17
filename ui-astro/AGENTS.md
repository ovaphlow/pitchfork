# AGENTS.md — ui-astro (Skills Index)

## Overview

Astro + React 19 + Tailwind v4 前端 monorepo，服务于 **克拉特 (Crate)** 制造企业员工培训平台。暗色主题，中文优先 UI。

## Quick Start

```bash
cd ui-astro
pnpm install
pnpm dev          # @pitchfork/auth (port 4321)
pnpm dev:admin    # @pitchfork/admin (port 4322)
pnpm dev:worker   # @pitchfork/worker (port 4323)
pnpm build        # 全部 apps 构建 (astro check + astro build)
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
│   └── worker/        (4323)      # 员工移动端 - 学习/考试/知识搜索/扫码
│       └── src/
│           ├── layouts/MobileLayout.astro
│           ├── pages/             # search, training, exam, profile, scan, ...
│           └── components/        # 员工端 React 组件
└── packages/                      # 共享包
    ├── shared/                    # @pitchfork/shared - API 客户端 + TS 类型
    │   └── src/index.ts           # request(), login(), 所有 CRUD API
    └── ui/                        # @pitchfork/ui - React 组件库
        └── src/index.tsx          # Button, Input, Table, Modal, Card, ...
```

## Convention: Apps = Routes, Packages = Logic

- `apps/*/src/pages/` — **仅 Astro 页面**（薄路由层，不做业务逻辑）
- `apps/*/src/components/` — 页面级 React 组件
- `packages/shared/` — API 客户端、TS 类型、工具函数
- `packages/ui/` — 共享 UI 组件（全局复用，不含业务逻辑）

## API 集成

- API base: `PUBLIC_API_URL` env / fallback `http://192.168.0.109:8421/crate-api`
- 认证: JWT Bearer token 存 `localStorage`
- 密码传输: RSA 加密 (`jsencrypt` → 后端 `/auth/v1/public-key`)
- Token 过期自动 401 → 跳转 `/login`

## Available Skills

| Skill | Description |
|-------|-------------|
| [Project Structure](./skills/project-structure.md) | Monorepo 架构与约定详解 |
| [Adding a Page](./skills/add-page.md) | 新增功能页面完整工作流 |
| [API Integration](./skills/api-integration.md) | @pitchfork/shared API 客户端使用 |
| [Styling Guide](./skills/styling-guide.md) | Tailwind v4 主题系统与 OKLCH tokens |
| [UI Component Guide](./skills/ui-component-guide.md) | @pitchfork/ui 组件库参考 |
