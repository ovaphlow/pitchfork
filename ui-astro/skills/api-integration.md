# API Integration — @pitchfork/shared 客户端使用指南

## API Base URL

```
PUBLIC_API_URL = http://192.168.0.109:8421/crate-api (默认)
```

在 `apps/*/.env` 中通过 `PUBLIC_API_URL` 配置。`packages/shared/src/index.ts` 自动读取：

```typescript
const API_BASE =
  import.meta.env.PUBLIC_API_URL ?? "http://192.168.0.109:8421/crate-api";
```

## 基础请求函数

```typescript
import { request } from "@pitchfork/shared";

// GET
const data = await request("/knowledge/v1/categories");

// POST
const result = await request("/knowledge/v1/entries", {
  method: "POST",
  body: JSON.stringify({ title: "xxx", type: "article" }),
});

// PUT / PATCH / DELETE 同理
```

`request()` 自动处理：
- `Content-Type: application/json` 头
- JWT Bearer token 注入（从 `localStorage` 读取 `auth_token`）
- 401 响应 → 清除 token → 跳转 `/login`
- 统一错误抛出（`new Error(message)`，message 来自后端 `{ error }` 字段）

## 分页 API 模式

后端分页返回 `{ records: [...], meta: { total: N } }`。

前端调用模式（`packages/shared` 中的函数自动展开 `records`）：

```typescript
export async function listKnowledgeEntries(params?: {
  type?: string;
  status?: string;
  search?: string;
  limit?: number;
  offset?: number;
}): Promise<KnowledgeEntry[]> {
  const q = new URLSearchParams();
  if (params?.type) q.set("type", params.type);
  if (params?.limit) q.set("limit", String(params.limit));
  if (params?.offset) q.set("offset", String(params.offset));
  const qs = q.toString();
  const res: { records: KnowledgeEntry[]; total?: number } =
    await request(`/knowledge/v1/entries${qs ? "?" + qs : ""}`);
  return res.records;
}
```

如需要获取 total，直接调用 `request()` 拿到原始响应：

```typescript
const res = await request<{ records: KnowledgeEntry[]; total: number }>(
  "/knowledge/v1/entries?limit=50&offset=0"
);
setRecords(res.records);
setTotal(res.total);
```

## 认证流程

### 登录 (RSA 加密密码)

```typescript
import { login, setToken } from "@pitchfork/shared";

const { token, user } = await login(email, password);
setToken(token); // 持久化到 localStorage
```

密码传输流程：
1. 前端 GET `/auth/v1/public-key` 获取 RSA 公钥
2. 公钥缓存到 `localStorage` （`auth_public_key`）
3. 用 `jsencrypt` 加密密码
4. POST 加密后的密码到 `/auth/v1/login`
5. 后端返回 JWT token

### Token 管理

```typescript
import { getToken, setToken, clearToken } from "@pitchfork/shared";

getToken()    // 获取当前 token (服务端渲染时返回 null)
setToken(t)   // 存储 token
clearToken()  // 登出时清除
```

### 登出

```typescript
import { clearToken, clearPublicKeyCache } from "@pitchfork/shared";

function logout() {
  clearToken();
  clearPublicKeyCache();
  window.location.href = "/login";
}
```

## 添加新 API 函数

1. 在 `packages/shared/src/index.ts` 添加 TS 类型接口
2. 添加 CRUD 函数（遵循 `request()` 模式）
3. 类型在组件中 import：`import { listXxx, Xxx } from "@pitchfork/shared"`
