# 使用 Podman (rootless) 运行说明

本项目原始编排文件为 `docker-compose.yml`，可直接通过 `podman compose` 运行。下面列出在 Podman 下需要注意的差异与建议。

## 基本启动
```bash
podman compose -f docker-compose.yml -f podman-compose.override.yml up -d --build
```

若你已设置 alias `docker=podman`，也可以直接：
```bash
podman compose up -d --build
```
(添加 override 文件以修改端口映射请使用 -f 追加方式)

## 端口调整
- rootless 模式下绑定 <1024 端口（例如 80）可能失败，因此 override 中将 Traefik HTTP 映射改为 `8080:80`。
- 控制台 Dashboard 改为 `8081:8080`。

访问：
- 入口路由: http://localhost:8080/core/health
- Dashboard: http://localhost:8081
- Consul UI: http://localhost:8500

## 数据库端口
Postgres 映射在主 compose 中为 `55432:5432`，override 中保留一致。若宿主再冲突，可自定义:
```yaml
postgres:
  ports:
    - "65432:5432"
```
并同步更新宿主机直接调试的 `DATABASE_URL`（容器内仍保持 postgres:5432）。

## Connect Sidecar 注意事项
`consul-dataplane-core` 使用 `network_mode: service:service-core-go-stdlib`。某些 Podman 版本对该模式支持不完全，如果报错：
- 方案 A：去掉该字段，直接放入同一个用户定义网络 (backend)，并给 dataplane 容器添加 `--service-node` 等参数和显式端口。
- 方案 B：使用 Podman Pod：
  ```bash
  podman pod create -n corepod
  podman run --pod corepod ... (core service)
  podman run --pod corepod ... (dataplane)
  ```
- 方案 C：改用 Consul transparent proxy 模式 (需添加 iptables/nft 支持，rootless 受限)。

## 文件权限与卷
如需挂载本地目录写入 (例如未来添加迁移 SQL)，rootless 下需确保宿主机 UID 与容器内用户兼容。当前 distroless 运行 `nonroot` 用户 (UID 65532)，若需要写入请改为 scratch/alpine + 指定用户或通过 initContainer 方式预处理。

## 兼容性差异
| 方面 | Docker | Podman rootless | 备注 |
|------|--------|-----------------|------|
| network_mode: service | 支持 | 部分版本不支持 | 如不支持需替代方案 |
| 挂载 docker.sock | 常见 | 无意义 | 已移除 registrator，无需 | 
| `cap_add` 等特权 | 支持 | rootless 受限 | 本项目未使用 |

## 故障排查
1. Traefik 端口无法访问: 检查是否占用 8080/8081；修改 override 文件重新启动。
2. Consul sidecar 未注册：查看 `consul-dataplane-core` 日志；确认 `-grpc-addr consul:8502` 可达。
3. 数据库连接失败：确认 `postgres` 容器是否启动，或本地 `~/.config/containers/containers.conf` 内网络策略。

## 清理
```bash
podman compose down -v
```

## 后续可选增强
- 添加 Podman secret：`podman secret create db_pass ./secret.txt` 然后在 compose 使用 `secrets:` 注入
- 使用 Podman Quadlet (systemd 管理)：生成 systemd 单元以便开机自启

如需我直接生成去掉 `network_mode: service:...` 的兼容版本，再告诉我。 
