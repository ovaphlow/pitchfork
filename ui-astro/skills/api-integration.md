# API Integration — @pitchfork/shared 客户端使用指南

## API Base URL

每个应用都必须在自己的 `.env` 中配置 `PUBLIC_API_URL`。从同目录 `.env.example` 复制后再启动开发服务器：

```bash
# Trainova apps (auth/admin/worker)
PUBLIC_API_URL=http://localhost:8421/crate-api

# Aceso
PUBLIC_API_URL=http://localhost:8422/crate-api
```

客户端没有默认后端地址。缺少该变量时，首次 API 调用会失败，而不会误连到另一个产品。

## 产品作用域导入

```typescript
// auth, admin, worker
import { listKnowledgeEntries, login } from "@pitchfork/shared/trainova";

// aceso
import { listUsers, login } from "@pitchfork/shared/aceso";
```

不要从另一个产品的入口导入 API，也不要在 app 中手写另一个产品的 API URL。共享客户端内部会自动处理：
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

## 认证流程

### 登录 (RSA 加密密码)

```typescript
import { login, setToken } from "@pitchfork/shared/trainova";

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
import { getToken, setToken, clearToken } from "@pitchfork/shared/trainova";

getToken()    // 获取当前 token (服务端渲染时返回 null)
setToken(t)   // 存储 token
clearToken()  // 登出时清除
```

### 登出

```typescript
import { clearToken, clearPublicKeyCache } from "@pitchfork/shared/trainova";

function logout() {
  clearToken();
  clearPublicKeyCache();
  window.location.href = "/login";
}
```

## 添加新 API 函数

1. 先确认端点属于 Trainova、Aceso 或真正的平台能力
2. 在对应产品的共享 API 实现中添加 TS 类型与 CRUD 函数
3. 只从该产品的入口导入，例如 `import { listXxx, Xxx } from "@pitchfork/shared/trainova"`
4. 在另一个产品需要同一能力时，先定义产品无关的后端和数据契约，再提升到平台层
