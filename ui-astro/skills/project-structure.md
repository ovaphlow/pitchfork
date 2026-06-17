# Project Structure — ui-astro 架构与约定

## 架构原则

**Apps = 路由层，Packages = 逻辑层。**

```
ui-astro/
├── apps/                          # pnpm workspace 应用
│   ├── auth/          (4321)      # 登录 + 员工管理 + 部门设置
│   ├── admin/         (4322)      # 管理后台
│   └── worker/        (4323)      # 员工移动端
└── packages/                      # 共享包
    ├── shared/                    # API 客户端 + TS 类型定义
    └── ui/                        # React UI 组件库
```

## 三个 Apps 的职责

### @pitchfork/auth (port 4321)

面向管理员/HR 的登录与基础管理：

| 页面 | 路径 | Description |
|------|------|-------------|
| 登录 | `/login` | LoginForm |
| 注册 | `/register` | RegisterForm |
| 仪表盘 | `/dashboard` | 概览 |
| 员工管理 | `/users` | UsersManagement |
| 部门设置 | `/settings/departments` | DepartmentsManagement |

### @pitchfork/admin (port 4322)

管理后台核心功能：

| 页面 | 路径 | Description |
|------|------|-------------|
| 首页 | `/` | 首页 |
| 仪表盘 | `/dashboard` | DashboardPage |
| 部门 | `/departments` | DepartmentList |
| 员工 | `/employees` | EmployeeList |
| 知识库 | `/knowledge` | KnowledgeList |
| 知识编辑器 | `/knowledge/editor` | KnowledgeEditor |
| 知识审批 | `/knowledge/approvals` | KnowledgeApprovals |
| 课程 | `/courses` | CourseList |
| 课程编辑 | `/courses/editor` | CourseEditor |
| 岗位 | `/positions` | PositionList |
| 技能矩阵 | `/skills` | SkillMatrix |
| 题库 | `/questions` | QuestionBank |
| 考试 | `/exams` | ExamList |
| 培训任务 | `/training-tasks` | TrainingTaskManager |
| 分析 | `/analytics` | AnalyticsPage |

### @pitchfork/worker (port 4323)

面向一线员工，移动端优先：

| 页面 | 路径 | Description |
|------|------|-------------|
| 首页 | `/` | 首页 |
| 知识搜索 | `/search` | KnowledgeSearch |
| 知识详情 | `/knowledge/[id]` | KnowledgeDetail |
| 培训 | `/training` | TrainingTaskList |
| 培训详情 | `/training/[id]` | CourseLearning |
| 考试 | `/exam/[id]` | ExamPage |
| 技能 | `/skills` | SkillProfile |
| 扫码 | `/scan` | ScanResult |
| 个人设置 | `/profile` | ProfileSettings |

## 共享包

### @pitchfork/shared (`packages/shared/`)

API 客户端入口 `src/index.ts`：

- `request<T>(path, options)` — 基础 HTTP 客户端（自动注入 JWT、401 处理）
- `encryptedPost<T>(path, email, password)` — RSA 加密密码 POST
- 所有 CRUD 函数: `listXxx()`, `createXxx()`, `updateXxx()`, `deleteXxx()`
- 所有 TS 类型接口: `KnowledgeEntry`, `Course`, `Question`, `EmployeeSkill`...

### @pitchfork/ui (`packages/ui/`)

React 组件库入口 `src/index.tsx`：

- `Button` — primary/secondary/ghost/danger variants
- `Input` — 带 label/error 的 input
- `Card` — 带 title/actions 的卡片容器
- `Modal` — 模态对话框
- `Table<T>` — 泛型表格（排序、loading、空态）
- `Badge` — success/warning/danger/info 标签
- `LoadingSpinner` — 旋转加载
- `EmptyState` — 空数据占位

## 禁止修改其它 App

迁移或新增功能时，只能修改目标 app 的代码。`packages/` 的修改可以影响所有 app，`apps/` 的修改必须严格限定在目标 app 内。
