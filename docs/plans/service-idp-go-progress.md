# service-idp-go 实施检查点

更新时间：2026-07-23

本文件用于在中断开发或更换会话后快速恢复 `service-idp-go` 的实施上下文。
代码和文档均已写入工作区；请在审阅后通过 Git 提交形成可恢复的版本点。

## 已完成

- Go 标准库 HTTP 服务、`modernc.org/sqlite`、嵌入式顺序迁移、SQLite 初始化参数和结构化日志。
- 首次启动 bootstrap 管理员、Argon2id 密码、服务端不透明 Session、CSRF、登出和 `identity.admin` 授权。
- 持久化登录限流：部署密钥 HMAC 化的账号标识与客户端地址、固定失败窗口和锁定。
- `sqlc` 查询源码及生成 Go 代码策略：生成代码必须提交，构建产物不提交。
- 管理员主体 API：列表、创建、详情、禁用；禁用提升安全版本、撤销活跃 Session、写审计，并保护最后一个启用的管理员。
- Tailwind/HTMX 服务端管理页：Go `html/template` 渲染；浏览器页面、HTMX 行片段和 JSON API 复用同一组 `/crate-api/identity/v1/subjects` 资源及同一领域事务。
- Node 仅用于构建期依赖管理：`make assets` 按 `pnpm-lock.yaml` 安装依赖，生成 Tailwind CSS 与本地 HTMX 文件，随后由 Go 二进制嵌入。
- 管理员可为主体设置临时密码，凭据标记为 `需更新`；下次登录仅生成可改密、可退出的受限会话。
- 当前主体可通过 CSRF 保护的密码页和 JSON API 修改密码；凭据修订号乐观锁、主体安全版本递增、会话撤销与 `凭据变更` 审计在同一事务中完成。
- 所有 HTTP 异常正文（含业务错误、未匹配路由和不支持的请求方法）统一采用 RFC 9457 Problem Details，媒体类型为 `application/problem+json`，并包含 `type`、`title`、`status`、`detail` 和 `instance`；浏览器表单的既有重定向流程不产生错误正文。

## 已验证

以下命令在当前工作区通过：

```bash
cd service-idp-go
make assets
GOCACHE=/tmp/identityd-go-build-cache go test ./...
GOCACHE=/tmp/identityd-go-build-cache go vet ./...
GOCACHE=/tmp/identityd-go-build-cache go build -o /tmp/identityd-web-verify ./cmd/identityd
go mod verify
```

`make check-generated` 要求生成的 `internal/database/sqlc/*.go` 与查询源码
完全同步；每次修改 `db/queries/` 后先运行 `make generate`，并将生成代码与源码
一并提交。

## 重要文件

- `service-idp-go/internal/identity/management.go`：创建、查询与禁用主体的事务及最后管理员保护。
- `service-idp-go/internal/httpapi/handler.go`：JSON/HTML/HTMX 内容协商、Session、CSRF 和静态资源路由。
- `service-idp-go/internal/httpapi/templates.go`：登录、控制台和主体管理服务端模板。
- `service-idp-go/web/assets/app.css`：Tailwind 源文件。
- `service-idp-go/web/scripts/build-assets.mjs`：跨平台 Tailwind/HTMX 构建脚本。
- `service-idp-go/package.json`、`service-idp-go/pnpm-lock.yaml`：构建期前端依赖与锁定版本。

## 下一步建议

1. 实现管理员角色授予与撤销：撤销最后一个启用管理员时继续保持同一保护规则。
2. 增加审计事件列表和会话管理页面。
3. 最后补充部署、备份恢复和 Windows/Linux 交付文档。
