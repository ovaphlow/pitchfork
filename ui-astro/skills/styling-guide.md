# Styling Guide — Tailwind v4 主题系统

## Theme Tokens

统一在 `apps/*/src/styles/global.css` 中使用 `@theme` 指令定义（**不是** tailwind.config）：

```css
@import "tailwindcss";

@theme {
  /* 布局尺寸 */
  --sidebar-w: 260px;
  --sidebar-collapsed-w: 64px;
  --topbar-h: 60px;

  /* 暗色背景层 */
  --color-bg: oklch(14% 0.008 250);                /* 最底层背景 */
  --color-surface: oklch(17% 0.008 250);           /* 卡片/容器表面 */
  --color-surface-overlay: oklch(22% 0.012 250);   /* 模态/弹窗 */
  --color-surface-alt: oklch(19% 0.01 250);        /* 悬停/选中 */

  /* 边框 */
  --color-border: oklch(25% 0.01 250);
  --color-border-light: oklch(22% 0.008 250);

  /* 文字 */
  --color-fg: oklch(85% 0.008 250);                /* 主体 */
  --color-fg-emphasis: oklch(95% 0.005 250);       /* 标题/强调 */
  --color-fg-muted: oklch(55% 0.015 250);          /* 次要 */
  --color-fg-dimmed: oklch(38% 0.015 250);         /* 禁用/占位 */

  /* 语义色 */
  --color-accent: oklch(62% 0.16 235);             /* 主色调 (蓝色) */
  --color-accent-subtle: oklch(62% 0.16 235 / 0.12);

  --color-success: oklch(58% 0.15 150);            /* 成功 (绿色) */
  --color-success-bg: oklch(58% 0.15 150 / 0.12);
  --color-warning: oklch(68% 0.14 78);             /* 警告 (黄色) */
  --color-warning-bg: oklch(68% 0.14 78 / 0.12);
  --color-danger: oklch(58% 0.16 30);              /* 危险 (红色) */
  --color-danger-bg: oklch(58% 0.16 30 / 0.12);
  --color-info: oklch(60% 0.12 200);               /* 信息 (青色) */
  --color-info-bg: oklch(60% 0.12 200 / 0.12);

  /* 字体 */
  --font-body: "Inter", "Noto Sans SC", system-ui, -apple-system, sans-serif;
  --font-mono: "JetBrains Mono", "Fira Code", ui-monospace, monospace;

  /* 阴影 */
  --shadow-overlay: 0 8px 32px oklch(0% 0 0 / 0.4);
}

html, body {
  font-family: var(--font-body);
}
```

## 使用方式

直接在 Astro 或 React 组件中使用 Tailwind utility classes：

```tsx
<div className="bg-surface text-fg border border-border rounded-lg p-4">
  <h2 className="text-fg-emphasis text-lg font-semibold">标题</h2>
  <p className="text-fg-muted text-sm">次要文字</p>
</div>
```

### 常用组合

| 用途 | Classes |
|------|---------|
| 卡片容器 | `rounded-lg border border-border bg-surface` |
| 页面间距 | `space-y-4` 或 `space-y-6` |
| 弹性布局 | `flex items-center justify-between` |
| 按钮 | 用 `<Button>` 组件 |
| 表单标签 | `text-sm font-medium text-fg-muted` |
| 禁用文字 | `text-fg-dimmed` |
| 成功标签 | `<Badge variant="success">` |
| 分割线 | `border-b border-border` |

## 三个 App 共享同一套 Token

所有 `apps/*/src/styles/global.css` 内容一致。如要修改主题色，需要在三个文件中同步更新。
