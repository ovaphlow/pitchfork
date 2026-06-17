# Adding a Page — 新增功能页面工作流

## 全流程

```
1. 确定属于哪个 App (auth/admin/worker)
2. 在对应的 apps/<app>/src/pages/ 建 Astro 路由页面
3. 在对应的 apps/<app>/src/components/ 建 React 组件
4. 如需要新 API 调用 → 更新 packages/shared/src/index.ts
5. 如需要新 UI 模式 → 更新 packages/ui/src/index.tsx
```

## 示例：为 admin 新增页面

### Step 1: 创建 Astro 路由页面

`apps/admin/src/pages/reports.astro`：

```astro
---
import AdminLayout from "../layouts/AdminLayout.astro";
import ReportsPage from "../components/ReportsPage";
---
<AdminLayout title="报表中心">
  <ReportsPage client:load />
</AdminLayout>
```

- 页面文件只做 layout 包装和组件挂载
- 使用 `client:load` 让 React 组件在客户端水合

### Step 2: 创建 React 组件

`apps/admin/src/components/ReportsPage.tsx`：

```tsx
import { useEffect, useState } from "react";
import { Card, Table } from "@pitchfork/ui";

export default function ReportsPage() {
  const [data, setData] = useState([]);

  useEffect(() => {
    // 调用 API
  }, []);

  return (
    <div className="space-y-4">
      <Card title="报表">
        <Table columns={columns} data={data} />
      </Card>
    </div>
  );
}
```

### Step 3: API 调用

如需要新的 API 端点，先在 `packages/shared/src/index.ts` 添加函数：

```typescript
export interface Report {
  id: string;
  name: string;
  generated_at: string;
  // ...
}

export async function listReports(params?: {
  limit?: number;
  offset?: number;
}): Promise<Report[]> {
  const q = new URLSearchParams();
  if (params?.limit) q.set("limit", String(params.limit));
  if (params?.offset) q.set("offset", String(params.offset));
  const qs = q.toString();
  const res: { records: Report[] } = await request(`/analytics/v1/reports${qs ? "?" + qs : ""}`);
  return res.records;
}
```

## 样式约定

- 页面间距：`space-y-4` 或 `space-y-6`
- 卡片容器：优先用 `<Card>` 组件
- 表格：优先用 `<Table>` 泛型组件
- 按钮：优先用 `<Button>` 组件
- 不使用内联 style，全部用 Tailwind utility classes

## 严格隔离原则

- 只能修改目标 app 下的文件
- 不修改其他 `apps/` 下的任何文件
- `packages/` 的修改可以跨 app 影响（谨慎）
