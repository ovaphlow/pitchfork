# agora-ui

Agora 的前端工作区：Astro 静态外壳 + Svelte 客户端渲染 + Tailwind CSS 4。

## 技术栈

- Astro 7（`output: "static"`）
- Svelte 5（唯一页面入口使用 `client:only="svelte"`）
- Tailwind CSS 4（`@tailwindcss/vite`，无 `tailwind.config`）
- TypeScript strict

## 本地运行

需要 Bun 1.4+。依赖、锁文件与脚本统一使用 Bun。

```bash
cp .env.example .env
bun install
bun run dev
```

开发地址：<http://localhost:4321>。依赖锁定在 `bun.lock`。

```bash
bun run check   # astro check + svelte-check
bun run build   # 类型检查并生成 dist/
bun run preview # 预览构建产物
```

## 环境变量

| 变量 | 必填 | 说明 |
|---|---|---|
| `PUBLIC_API_URL` | 是 | `agora-api` 的基础地址，例如 `http://localhost:8080`，不要尾随斜杠 |

未配置 `PUBLIC_API_URL` 时页面仍可打开，但 API 探测会明确报错，不会回退到隐式后端。

局域网访问时，`PUBLIC_API_URL` 必须使用浏览器可达的服务器局域网地址，例如
`http://192.168.0.109:8080`；同时把页面 Origin（如
`http://192.168.0.109:4321`）加入 agora-api 的 `ALLOWED_ORIGINS`，否则请求会被 CORS 拦截。

## 结构

```text
src/
  components/App.svelte          # 会话门禁、顶部认证栏、页签路由、身份工作台
  components/AuthPanel.svelte    # 登录/注册表单
  components/BottomNav.svelte    # 登录后底部导航（首页/搜索/消息/我的）
  components/IntentSwitch.svelte # 求职者/招聘方身份切换
  components/OrganizationCard.svelte     # 机构卡片 + 岗位管理折叠入口
  components/OrganizationPositions.svelte # 机构岗位列表、发布草稿、状态流转
  components/PositionCard.svelte # 岗位卡片（首页与搜索共用）
  components/ProfilePanel.svelte # 「我的」：账号、权限、接口状态与探测
  components/SearchPanel.svelte  # 岗位搜索（关键词 + 城市）
  layouts/Layout.astro           # HTML 文档外壳
  lib/api.ts                     # 唯一 API 入口、认证/机构/岗位/文件上传
  lib/intent.ts                  # 登录身份状态与 localStorage 持久化
  lib/nav.ts                     # 底部导航页签类型
  pages/index.astro              # 静态页面壳，挂载 client:only
  styles/global.css              # Tailwind 入口与主题 token
```

## 底部导航

登录后固定四个页签，求职者与招聘方共用，图标 + 文案，`aria-current="page"` 标记当前页；
登录页不显示导航。

| 页签 | 内容 |
|---|---|
| 首页 | 求职者为岗位信息流；招聘方为所属机构、创建机构与机构内岗位管理 |
| 搜索 | 按关键词 / 城市搜索在招岗位（`GET /positions?q=&city=`），最多展示 20 条并提示缩小范围 |
| 消息 | 占位页：站内消息与通知尚未接入 |
| 我的 | 显示名、账号、主体标识、登录身份、Nexus 角色，以及接口状态面板与「探测接口」按钮 |

底部导航与内容同为列布局成员（`sticky bottom-0`），并通过 `env(safe-area-inset-bottom)`
避让手势区；退出登录回到登录页时页签重置为「首页」。

## 登录身份

登录/注册面板提供「求职者 / 招聘方」分段选择，选择结果只影响登录后的默认
工作台，不进入请求体、不改变认证接口，也不参与权限判定：

- 求职者：登录后读取 `GET /crate-api/agora/v1/positions`，列岀在招岗位（后端只返回
  状态为「招聘中」且未过期的记录），分页 20 条一页，可「加载更多」。
- 招聘方：登录后读取 `GET /crate-api/agora/v1/organizations/mine`，列出所属
  机构与其成员角色；没有机构时提示先创建机构并通过平台认证，而不是拒绝登录。
  工作台内提供「创建机构」表单（名称、类型、简称、统一社会信用代码、营业执照）：
  先 `POST /crate-api/agora/v1/files` 上传营业执照取得文件 id，再
  `POST /crate-api/agora/v1/organizations` 携带 `credit_code` 与 `license_file_id`
  创建机构。创建后当前账号成为 `所有者`，机构状态为「待审核」，认证前不能发岗位。
  已有机构时列表下方仍保留「＋ 创建机构」：一个账号可以拥有多家机构（集团、连锁），
  每家独立审核，角色按机构隔离。
  已有成员身份的成员录入仍由所有者/管理员通过成员接口完成。

### 岗位发布与管理

招聘方首页的机构卡片下方有「岗位」折叠区，展开后读取
`GET /crate-api/agora/v1/organizations/{id}/positions`（含草稿）。
「发布岗位」表单提交 `POST /crate-api/agora/v1/positions`，新建岗位状态为 `草稿`：

- 表单字段：岗位名称（必填）、用工类型、工作方式、招聘人数、薪资（面议或下限/上限 + 单位）、
  学历要求、经验要求、城市、岗位职责、任职要求。枚举值与后端 `positions` 包保持一致。
- 状态流转通过 `POST /crate-api/agora/v1/positions/{id}/status`：
  `草稿 → 发布（招聘中）`；`招聘中 → 暂停 / 关闭`；`已暂停 → 恢复发布 / 关闭`。
- 机构状态非 `已认证` 时，「发布」按钮禁用并提示先完成认证（后端同样拒绝）；
  `已驳回` / `已停用` 机构连草稿也会被后端拒绝。
- 发布后的岗位会出现在求职者首页与搜索页（后端只返回「招聘中」且未过期的岗位）。
- 当前列表取前 50 条，暂无分页与编辑/删除入口。
- 身份在登录页选定并写入 `localStorage["qimu.intent"]`，登录后固定不变；
  需要换身份时退出登录，在登录页重新选择。

招聘方能否真正发布岗位仍由后端机构成员角色（`所有者` / `管理员` / `招聘专员`）
决定。

## 多语言

- 默认跟随浏览器/操作系统语言：`navigator.languages` 中第一个受支持的语言；
  支持 `zh-*`（简体中文）与 `en-*`，其余回退中文。
- 用户手动切换后写入 `localStorage["qimu.locale"]`，优先于浏览器语言；未手动
  切换时每次启动都会重新跟随系统。
- 文案位于 `src/i18n/messages.ts`；中英文 key 必须一致，新增文案需同时补齐。
- 页面会同步更新 `<html lang>`、标题和 meta description；时间与复数按 locale
  格式化。
- 语言按钮位于顶栏，显示的是“切换到”的目标语言（`EN` / `中`）。

## 移动端布局

界面只面向移动端：

- 单列布局，内容列 `max-w-[27rem]`（432px）居中；不提供桌面布局，也不使用 `sm:` / `md:` / `lg:` 断点。
- `Layout.astro` 使用 `viewport-fit=cover`，顶栏与底部操作栏通过 `env(safe-area-inset-*)` 避让刘海与手势区。
- 高度使用 `dvh`；所有可点击目标至少 44px，主操作常驻底部拇指区。
- 交互只用 `active:` 触控反馈，不依赖 hover。

## CSR 约定

- 页面外壳由 Astro 静态输出，交互界面只在客户端挂载。
- 新增页面组件时使用 `client:only="svelte"`，不要依赖 SSR 数据。
- API 请求统一经过 `src/lib/api.ts`，不要在组件里散落 `fetch`。
- 认证接口：`POST /crate-api/agora/v1/auth/register`、`POST /crate-api/agora/v1/auth/login`、
  `GET|DELETE /crate-api/agora/v1/auth/session`；所有浏览器请求带
  `credentials: "include"`，会话由 agora-api 转发的 HttpOnly Cookie 维持。
- 页面挂载时先请求 session：未登录显示登录/注册，已登录才加载状态面板和 Nexus
  角色。登录成功后的会话响应包含 `roles`、`permissions` 与 `nexus_ready`。
- UI 与 API 必须位于同一站点（registrable domain）并同时使用 HTTPS；否则
  `SameSite=Lax` 会话 Cookie 不会随跨站请求发送。
- 当前探测接口：`GET /crate-api/agora/v1/status` 与 `GET /readyz`。
