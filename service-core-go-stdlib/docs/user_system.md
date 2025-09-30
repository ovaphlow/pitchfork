# 用户系统设计 (User System)

> 本文描述平台“用户账户域” (Identity Store) 的数据结构、与 OIDC Provider 的边界、密码与安全策略、MFA、版本化 (security version) 以及与多租户 / 审计的协同。OIDC 文档只负责协议与令牌，这里负责“用户是谁”的源数据。

## 1. 设计目标
- 单一可信来源 (Single Source of Truth)：所有用户基础与认证状态集中管理。
- 与 OIDC 解耦：OIDC 只消费已认证的用户 ID 与属性，不直接存密码/敏感账号信息。
- 安全 & 可演进：支持密码升级、MFA、强制下线、风控集成。
- 多用户类型 & 多租户：统一结构支持 customer / merchant / staff / admin / partner 等。
- 审计与合规：关键操作可追踪、可回放。

## 2. 逻辑边界
| 域 | 负责 | 不负责 |
|----|------|--------|
| 用户系统 (User System) | 账号注册、密码 & MFA、状态、基本属性、版本 | OIDC 授权码、Refresh、客户端注册、签名密钥 |
| OIDC Provider | 授权流程、Token 签发、客户端、密钥、Refresh 会话 | 密码哈希存储、登录失败计数、MFA 因子密钥 |

## 3. 核心数据模型
建议使用 `public.users`（或单独 `account.users` schema）；下述示例采用 `public`。

### 3.1 users 表
```sql
CREATE EXTENSION IF NOT EXISTS citext; -- 若需要邮箱大小写不敏感

CREATE TABLE public.users (
  id                BIGSERIAL PRIMARY KEY,
  username          TEXT UNIQUE,                 -- 可选：如果以手机号/邮箱为主可为空
  email             CITEXT UNIQUE,               -- 邮箱登录 & 通知；可为空
  email_verified    BOOLEAN NOT NULL DEFAULT false,
  phone_number      TEXT UNIQUE,
  phone_verified    BOOLEAN NOT NULL DEFAULT false,

  password_hash     TEXT,                        -- Argon2id / BCrypt 哈希（含 salt 参数）
  password_algo     TEXT,                        -- 记录算法 & 参数版本，便于升级
  password_updated_at TIMESTAMPTZ,
  must_reset_password BOOLEAN NOT NULL DEFAULT false,

  status            TEXT NOT NULL DEFAULT 'active', -- active / locked / disabled
  login_failed_attempts INT NOT NULL DEFAULT 0,
  locked_until      TIMESTAMPTZ,                 -- 防爆破临时锁定
  last_login_at     TIMESTAMPTZ,

  user_type         TEXT,                        -- customer / merchant / staff / admin / partner
  -- tenant_id       TEXT,                        -- (单租户部署已移除; 若恢复多租户再加回)
  version           BIGINT NOT NULL DEFAULT 1,   -- 权限 / 安全版本 (token.v 对比)

  attributes        JSONB,                       -- 扩展 KV（展示名、头像、偏好）

  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  deactivated_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_users_email ON public.users (email);
CREATE INDEX IF NOT EXISTS idx_users_user_type ON public.users (user_type);
-- CREATE INDEX IF NOT EXISTS idx_users_tenant ON public.users (tenant_id); -- 单租户不创建
```

### 3.2 密码历史 (可选)
```sql
CREATE TABLE public.user_password_history (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  password_hash TEXT NOT NULL,
  password_algo TEXT NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ON public.user_password_history (user_id, created_at DESC);
```
用途：禁止近期重复密码 / 风险排查。

### 3.3 MFA 因子 (可选启用)
```sql
CREATE TABLE public.user_mfa_factor (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT NOT NULL REFERENCES public.users(id) ON DELETE CASCADE,
  type         TEXT NOT NULL,                -- totp / webauthn / sms
  secret_enc   TEXT,                         -- 加密存放 TOTP 秘钥；WebAuthn 公钥材料
  label        TEXT,
  priority     INT DEFAULT 0,
  enabled      BOOLEAN NOT NULL DEFAULT true,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_used_at TIMESTAMPTZ
);
CREATE INDEX ON public.user_mfa_factor (user_id, type, enabled);
```

### 3.4 登录审计 (可选)
可复用 OIDC 的审计表；如果希望账号域单独：
```sql
CREATE TABLE public.user_login_audit (
  id           BIGSERIAL PRIMARY KEY,
  user_id      BIGINT,
  event_type   TEXT NOT NULL,            -- login_success / login_failed / lock / unlock / password_change
  ip_addr      INET,
  user_agent   TEXT,
  detail       JSONB,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX ON public.user_login_audit (user_id, created_at DESC);
```

## 4. 密码与认证策略
| 项目 | 建议 |
|------|------|
| 哈希算法 | Argon2id (memory>=64MB, time>=3)；备选 BCrypt(cost>=12) |
| 轮换升级 | 登录成功后检测参数是否过期，必要时透明 re-hash |
| 失败计数 | 超过阈值 (5~8) 锁定：`locked_until = now() + interval '15 min'` |
| 强制下线 | `version++` + 撤销 Refresh 会话 |
| 初次登录改密 | 创建时 `must_reset_password=true`，验证后强制改密 |
| 密码历史 | 保留最近 N 条（如 3~5）避免重复 |

## 5. MFA 设计
| 因子 | 要点 | 存储 |
|------|------|------|
| TOTP | base32 秘钥，设备时间漂移校准 | secret_enc (加密后存) |
| SMS | 短信验证码速率限制 | 不持久化因子（按手机号发送） |
| WebAuthn | 公钥凭证、签名计数 | secret_enc / JSONB detail |

登录流程中，初级认证（密码）通过 → 判断用户类型 / 风险（IP 变化、设备指纹、上次活动） → 若需 step-up 则下发 MFA challenge → 验证后签发授权码。

## 6. 与 OIDC 的字段映射
| users 字段 | Token Claim | 说明 |
|------------|-------------|------|
| id | sub | 用户全局唯一标识 |
| user_type | user_type | 授权策略 / RBAC 分支 |
| (单租户移除 tenant_id) |  |  |
| version | v | 版本化失效控制 |
| email (已验证) | email / email_verified | 标准 OIDC Claim (在 scope 包含 email 时) |
| attributes->>'name' | name | 自定义映射 |

OIDC 服务读取用户数据后生成 ID Token / Access Token；不写回用户表（除 login 成功更新 last_login_at 可通过用户服务 API 完成）。

## 7. 典型登录时序（与 OIDC）
```
[Client] -> /login (username+password) -> [User Service]
  验证密码 / 状态 / 锁定
  可选：MFA 判定
  ↓ 成功
[Client] -> /authorize?... (带 session/nonce) -> [OIDC]
[OIDC] 产生授权码 -> 回调 -> /token 交换 -> 发放 tokens
```
MFA Step-up 可在 /login 内完成，也可由 OIDC 端在初级认证结果后触发（推荐将逻辑统一在用户服务，以保证策略集中）。

## 8. 安全与审计要点
| 目标 | 手段 |
|------|------|
| 防爆破 | 失败计数 + 锁定 + IP 速率限制 (网关/Redis) |
| 防撞库 | Argon2id + 登录节流 + 异常提醒 |
| 强制失效 | version++ + 撤销 refresh sessions |
| 高危操作留痕 | 密码修改 / MFA 绑定 -> user_login_audit / oidc_audit_log |
| 数据最小暴露 | API 不回传 password_hash / factor 秘钥 |

## 9. 多租户策略（当前为单租户，以下仅作为将来参考）
- 简单模式：users.tenant_id + 业务侧按字段过滤。  
- 强隔离：Row Level Security (RLS) + `ALTER TABLE public.users ENABLE ROW LEVEL SECURITY;`。  
- 约束：必要时创建 `(tenant_id, username)`、`(tenant_id, email)` 组合唯一索引满足租户内唯一。

示例：
```sql
-- CREATE UNIQUE INDEX IF NOT EXISTS idx_users_tenant_email ON public.users (tenant_id, email); -- 单租户不需要
```

## 10. 与权限系统的协作
- 如果后续引入独立权限/角色服务（RBAC/ABAC），`version` 字段在任何权限更新后递增。
- 权限服务可以异步或事件（`permission_changed`）通知用户服务 bump 版本；OIDC 校验时即刻失效旧 token。

## 11. 清理与维护
| 任务 | 周期 | 说明 |
|------|------|------|
| 锁定释放 | 每分钟 | `locked_until < now()` 自动解锁（或登录时判断） |
| 审计归档 | 每日/每周 | 迁移历史 `user_login_audit` 到冷存储 |
| 密码历史裁剪 | 每日 | 保留最近 N 条 |
| 账号停用清理 | 业务策略 | 根据合规要求匿名化或删除 |

## 12. Roadmap (示例)
阶段1：基础密码登录 + version 失效机制  
阶段2：TOTP MFA + 登录审计  
阶段3：WebAuthn + 风控接入（IP 画像 / 风险评分）  
阶段4：权限服务事件驱动 version bump + RLS  
阶段5：统一身份编排（社交登录 / 企业 IdP 联邦）  

## 13. 与现有代码整合建议
- 在 `internal` 下新增 `user` 或 `account` 目录：`repo/ service/ handler/ dto/`。
-- 提供面向 OIDC 的只读接口：`GetUserForAuth(id)` 返回最小字段（id, user_type, version, email_verified）。(单租户不含 tenant_id)
- 写操作（密码修改、锁定、MFA 绑定）集中在用户服务，避免 OIDC 直接改写用户数据。

## 14. 最佳实践速览
| 场景 | 做法 |
|------|------|
| 防止旧 Token 继续访问 | version++ + refresh 撤销 |
| 提升密码强度 | 登录成功后检测算法参数，过期则透明升级 |
| 分离关注点 | 用户系统: 谁 / OIDC: 如何颁发凭证 |
| 最小数据输出 | API 避免返回敏感列（hash、secret_enc） |
| 快速审计 | 统一结构化事件，建立索引和检索视图 |

## 15. 数据访问实现说明（sqlx 选型）
当前代码使用标准库 `database/sql` + `sqlx` 的组合：

| 层 | 做法 | 说明 |
|----|------|------|
| 连接 | `pkg/database` 统一建立 *sql.DB | 轻量，可后续替换 pgx |
| 结构体映射 | 手写 `entity.User` | 仅保留必要字段，JSONB 原样保留为 []byte |
| 查询 | 原生 SQL 字符串 | 保持对锁、索引、更新表达式的控制 |
| 列映射 | sqlx 驼峰→下划线 | 关键字段可加 `db` tag 以显式控制 |
| 扩展字段 | `security_metadata` JSONB | 仅放非高频 / 可选安全属性 |

### 15.1 Repo 约定
`internal/user/repo/user_repo.go` 提供：
- EnsureTable（开发阶段）
- Create / GetByID / GetByEmail / GetByUsername
- IncrementFailedLogin / LockIfThreshold / UnlockIfExpired / ResetLoginSuccess
- BumpVersion / UpdatePassword / Deactivate / Reactivate

### 15.2 未来演进
| 需求 | 调整 |
|------|------|
| 更强类型安全 | 引入 sqlc 生成查询（与现有并存逐步替换） |
| 性能 & PG 特性 | 切换驱动为 pgx（sqlx 接口保持相似） |
| 复杂过滤/分页 | 封装 Query Builder 仅用于这一类（保留核心认证 SQL 手写） |
| Schema 演进 | 使用 golang-migrate 或 atlas 取代 EnsureTable |

### 15.3 使用示例
```go
repo := repo.NewUserRepo(sqlxDB)
id, _ := repo.Create(ctx, &entity.User{Username: &uname, Status: "active", Version: 1})
view, _ := repo.GetMinimalAuthView(ctx, id)
```

### 15.4 注意事项
- Create 期望调用前已做密码哈希。
- 并发失败计数：`IncrementFailedLogin` + `LockIfThreshold` 链式调用；业务层决定阈值与锁定时长。
- Version bump 后需同步触发 Refresh 会话撤销（交由上层 service）。
- JSONB 写入时若需结构校验，建议在 service 层解析后再入库。


---
后续可扩展：社交登录绑定表（user_social_identity）、用户属性分层缓存、密码泄露黑名单（Pwned Passwords 过滤）等。如需示例 Go repository / service 接口或 migration 脚手架可提出。
