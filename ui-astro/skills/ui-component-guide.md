# UI Component Guide — @pitchfork/ui 组件库参考

## 安装

组件库已预配置，所有 app 中可直接 import：

```typescript
import { Button, Input, Card, Table, Modal, Badge, LoadingSpinner, EmptyState } from "@pitchfork/ui";
```

## 组件参考

### Button

```tsx
<Button variant="primary" size="md" loading={false} onClick={handleClick}>
  保存
</Button>
```

| Prop | Type | Default | Description |
|------|------|---------|-------------|
| variant | `primary \| secondary \| ghost \| danger` | `primary` | 样式变体 |
| size | `sm \| md \| lg` | `md` | 按钮尺寸 |
| loading | `boolean` | `false` | 加载态 (显示 spinner) |
| disabled | `boolean` | — | 禁用态 |

### Input

```tsx
<Input label="姓名" value={name} onChange={e => setName(e.target.value)} error={nameError} placeholder="请输入姓名" />
```

| Prop | Type | Description |
|------|------|-------------|
| label | `string` | 标签文字 (自动生成 id) |
| error | `string` | 错误提示 (显示红色边框 + 文字) |
| value / onChange / placeholder | HTML input props | 标准受控 input |

### Card

```tsx
<Card title="用户列表" actions={<Button size="sm">添加</Button>}>
  {/* children */}
</Card>
```

| Prop | Type | Description |
|------|------|-------------|
| title | `string` | 卡片标题 |
| actions | `ReactNode` | 标题右侧操作区域 |
| className | `string` | 额外样式 |

### Table

```tsx
import type { Column } from "@pitchfork/ui";

const columns: Column<MyType>[] = [
  { key: "name", header: "姓名", sortable: true },
  { key: "email", header: "邮箱" },
  { key: "status", header: "状态",
    render: (row) => <Badge variant={row.status === "active" ? "success" : "default"}>{row.status}</Badge>
  },
];

<Table
  columns={columns}
  data={myData}
  keyField="id"
  loading={false}
  emptyMessage="暂无数据"
  sortKey={sortKey}
  sortDir={sortDir}
  onSort={handleSort}
/>
```

| Prop | Type | Description |
|------|------|-------------|
| columns | `Column<T>[]` | 列定义 (key + header + render) |
| data | `T[]` | 数据数组 |
| keyField | `string` | 行 key 字段名 (默认 `id`) |
| loading | `boolean` | 加载态 |
| emptyMessage | `string` | 空数据消息 (默认 "暂无数据") |
| sortKey / sortDir / onSort | — | 排序控制 |

### Modal

```tsx
<Modal open={showModal} onClose={() => setShowModal(false)} title="确认删除">
  <p className="text-fg-muted text-sm">确定要删除这条记录吗？</p>
  <div className="flex justify-end gap-3 mt-6">
    <Button variant="ghost" onClick={() => setShowModal(false)}>取消</Button>
    <Button variant="danger" onClick={handleDelete}>删除</Button>
  </div>
</Modal>
```

| Prop | Type | Description |
|------|------|-------------|
| open | `boolean` | 显示/隐藏 |
| onClose | `() => void` | 关闭回调 (点击蒙层) |
| title | `string` | 标题 |
| width | `string` | Tailwind max-width (默认 `max-w-lg`) |

### Badge

```tsx
<Badge variant="success">已通过</Badge>
<Badge variant="warning">待审核</Badge>
<Badge variant="danger">已拒绝</Badge>
<Badge variant="info">进行中</Badge>
<Badge variant="default">草稿</Badge>
```

| Prop | Type | Description |
|------|------|-------------|
| variant | `default \| success \| warning \| danger \| info` | 语义颜色 |

### LoadingSpinner

```tsx
<LoadingSpinner size={32} />
```

### EmptyState

```tsx
<EmptyState
  icon="📭"
  title="暂无数据"
  description="还没有任何记录，点击按钮添加"
  action={<Button onClick={handleAdd}>添加</Button>}
/>
```

## 组件开发原则

- 不包含业务逻辑（只负责渲染和交互模式）
- 使用 Tailwind utility classes，不使用内联 style
- 支持 `className` prop 扩展样式
- TypeScript 泛型（Table 支持泛型数据类型）
