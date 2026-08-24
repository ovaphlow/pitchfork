# Herdr Agent 启动与编排手册

> 给其它 Agent 参考：在 Herdr 中如何加载密钥、启动 Claude / Codex / Pi，以及如何用 Herdr 编排多 Agent 开发流程。

---

## 1. 环境判断

只有运行在 Herdr 管理的 Pane 内，才需要通过 `herdr` CLI 控制终端或其它 Agent。

```bash
# 确认当前是否在 Herdr 环境
test "${HERDR_ENV:-}" = 1 && echo "in herdr"

# 常用环境变量
echo "$HERDR_WORKSPACE_ID $HERDR_TAB_ID $HERDR_PANE_ID"
```

常用会话信息：

```bash
herdr workspace list
herdr tab list --workspace "$HERDR_WORKSPACE_ID"
herdr pane list --workspace "$HERDR_WORKSPACE_ID"
herdr agent list
```

---

## 2. 加载环境变量与密钥

环境变量分两类：Agent 自身需要的 LLM API 密钥（`~/.api.keys`），和项目开发进程需要的配置（各目录下的 `.env`）。两者都用同样的 shell 惯例注入：

```bash
set -a
source <文件>
set +a
```

### 2.1 Agent 的 API 密钥（~/.api.keys）

用户常用方式是在 shell 中：

```bash
set -a
source ~/.api.keys
set +a
```

在 Herdr 中，先通过 `pane run` 在目标 Pane 的 shell 里加载，再启动 Agent：

```bash
herdr pane run "$pane" "set -a; source ~/.api.keys; set +a"
```

也可以在创建 workspace / tab / pane 时直接传环境变量：

```bash
herdr workspace create --cwd ~/project --label dev \
  --env OPENROUTER_API_KEY=xxx \
  --no-focus
```

> 注意：`agent start` 本身没有 `--env` 参数。要么先 `pane run` source，要么在创建 Pane 时用 `--env`。

### 2.2 项目开发进程的 `.env`

后端、前端等开发进程的密码和测试账号经各自目录下的 `.env` 注入，启动前同样要先 source（`.env` 不入 Git，路径相对仓库根）：

| 文件 | 内容 |
|---|---|
| `service-vertx-kotlin/.env` | 后端数据库密码 `PITCHFORK_DB_PASSWORD` |
| `service-vertx-kotlin/apps/aceso/.env` | Aceso 数据库密码 |
| `ui-astro/apps/aceso/.env` | `PUBLIC_API_URL`、Playwright 测试账号与测试库连接 |

```bash
# 直接在 shell 中（先 cd 到 .env 所在目录）
set -a; source .env; set +a
```

在 Herdr 中同理，注意先切到对应目录或使用绝对路径：

```bash
herdr pane run "$pane" "cd ~/pitchfork/service-vertx-kotlin && set -a; source .env; set +a"
```

集成测试专用密码（`PITCHFORK_TEST_DB_PASSWORD=pitchfork-test-only`）不属于密钥，按 `docs/aceso-test-database.md` 直接 export 即可。

---

## 3. 各类 Agent 启动方法

所有 `agent start` 都要求目标 Pane 先处于可用的 shell 提示符，并且 `--` 后面的参数会原样传给 Agent。

### 3.1 Claude（含 OpenRouter settings）

```bash
# 1. 准备一个 shell pane（示例：新建 workspace）
created=$(herdr workspace create --cwd ~/project --label claude --no-focus)
pane=$(printf '%s' "$created" | jq -r '.result.root_pane.pane_id')

# 2. 加载 API 密钥
herdr pane run "$pane" "set -a; source ~/.api.keys; set +a"

# 3. 启动 Claude，并传入 settings 文件
herdr agent start claude-dev --kind claude --pane "$pane" \
  -- --settings ~/.claude/openrouter.settings.json

# 4. 下发任务并等待
herdr agent prompt claude-dev "请完成 xxx" --wait --timeout 300000
```

### 3.2 Codex（含 OpenRouter profile）

```bash
# 加载密钥
herdr pane run "$pane" "set -a; source ~/.api.keys; set +a"

# 启动 Codex
herdr agent start codex-dev --kind codex --pane "$pane" \
  -- --profile openrouter

herdr agent prompt codex-dev "请完成 xxx" --wait --timeout 300000
```

### 3.3 Pi

```bash
herdr pane run "$pane" "set -a; source ~/.api.keys; set +a"
herdr agent start pi-dev --kind pi --pane "$pane"
herdr agent prompt pi-dev "你好，请简单回复" --wait --timeout 60000
```

### 3.4 OpenCode2（手动启动，Herdr 识别为 opencode）

`agent start --kind opencode2` 目前不推荐：Herdr 的 kind 列表里没有 `opencode2`，直接用它可能启动超时。  
推荐用法：在 Pane 里手动启动 `opencode2`，Herdr 会自动把它识别成 `opencode`，再 `rename` 成方便使用的名字。

```bash
# 1. 准备一个 shell pane
created=$(herdr workspace create --cwd ~/project --label opencode2 --no-focus)
pane=$(printf '%s' "$created" | jq -r '.result.root_pane.pane_id')

# 2. 手动启动 opencode2
herdr pane run "$pane" "opencode2"

# 3. 等待 Herdr 识别后重命名
#    识别后 agent 类型通常是 opencode
herdr agent rename "$pane" oc2

# 4. 下发任务并等待
herdr agent prompt oc2 "你好，请简单回复" --wait --timeout 60000
herdr agent read oc2 --source recent-unwrapped --lines 60
```

已实测：手动 `opencode2` 启动后，`pane get` 显示 `agent: opencode`，`rename` 后可以正常 `agent prompt` / `agent read`。

### 3.5 其它支持的 Agent kind

查看完整列表：

```bash
herdr agent start --help
```

目前支持：`pi, claude, codex, gemini, cursor, devin, agy, cline, omp, mastracode, opencode, copilot, kimi, kiro, droid, amp, grok, hermes, kilo, qodercli, maki`

---

## 4. Herdr 三个基本原语

| 原语 | 职责 | 常用命令 |
|---|---|---|
| Layout | workspace / tab / pane 拓扑 | `workspace create`、`tab create`、`pane split`、`pane move` |
| Pane | 控制原始终端：命令、输入、输出 | `pane run`、`pane send-text`、`pane send-keys`、`pane read`、`pane wait-output` |
| Agent | 控制已识别的编码 Agent 和生命周期 | `agent start`、`agent prompt`、`agent wait`、`agent read`、`agent send-keys` |

### Pane 常用命令

```bash
# 运行普通命令（测试、服务、CI watcher）
herdr pane run "$pane" "python3 test_demo.py"

# 等待输出关键字
herdr pane wait-output "$pane" --regex "OK|FAILED|Traceback" --timeout 60000

# 读取输出
herdr pane read "$pane" --source recent-unwrapped --lines 80
```

### Agent 常用命令

```bash
# 启动 Agent
herdr agent start reviewer --kind claude --pane "$pane" -- --settings ...

# 下发任务并等待结束
herdr agent prompt reviewer "请评审当前 diff" --wait --timeout 120000

# 等待特定状态（例如阻塞等待人工审批）
herdr agent wait reviewer --until blocked --timeout 120000

# 读取 Agent 输出
herdr agent read reviewer --source recent-unwrapped --lines 120

# 处理 Agent 的提问/审批 UI
herdr agent send-keys reviewer esc
```

状态说明：

- `idle`：Agent 就绪，可接收输入
- `done`：后台工作完成后的 idle 状态
- `blocked`：Agent 正在等待确认/提问
- `unknown`：无法明确分类，不能当作成功

---

## 5. 用 Herdr 编排多 Agent 开发流程

典型用途：**不同角色放在不同 Pane/Workspace，由脚本或调度 Agent 串联**。

推荐模式：

```bash
# 1. 创建 workspace，每个角色一个 tab/pane
created=$(herdr workspace create --cwd ~/project --label pipeline --no-focus)
ws=$(printf '%s' "$created" | jq -r '.result.workspace.workspace_id')
root_pane=$(printf '%s' "$created" | jq -r '.result.root_pane.pane_id')

# 创建更多 tab
tab=$(herdr tab create --workspace "$ws" --cwd ~/project --label reviewer --no-focus)
review_pane=$(printf '%s' "$tab" | jq -r '.result.root_pane.pane_id')

# 2. 加载密钥
herdr pane run "$root_pane" "set -a; source ~/.api.keys; set +a"
herdr pane run "$review_pane" "set -a; source ~/.api.keys; set +a"

# 3. 启动开发 Agent
herdr agent start developer --kind claude --pane "$root_pane" -- --settings ~/.claude/openrouter.settings.json
herdr agent prompt developer "请实现 xxx" --wait --timeout 300000

# 4. 启动独立评审 Agent
herdr agent start reviewer --kind codex --pane "$review_pane" -- --profile openrouter
herdr agent prompt reviewer "请评审当前 diff，只读，不要改代码" --wait --timeout 120000
```

阶段映射：

| 阶段 | 推荐方式 |
|---|---|
| 需求分析 | 启动 Agent 写需求文档 |
| 任务拆解 | Agent 读取需求，产出 task 清单 |
| 开发 | 开发 Agent 实现代码 |
| 单元测试 | 独立 Pane 运行测试命令 + `pane wait-output` |
| 数据库测试 | 独立 Pane 前台启动测试库（`podman compose up`，不加 `-d`），另一 Pane 运行集成测试 |
| 浏览器 E2E | 独立 Pane 运行 Playwright/Cypress |
| 评审 | 独立只读 Agent 审查 diff/产物 |

完整可运行示例见：

```bash
/home/ovaphlow/pitchfork/scripts/herdr_pipeline_demo.sh
```

---

## 6. 注意事项

- `agent start` 前 Pane 必须回到 shell 提示符，否则报 `agent_pane_busy`。
- 创建临时资源时建议加 `--no-focus`，避免抢走用户 UI 焦点。
- `--` 后面的参数会原样传给 Agent，不要放在 `--` 前面。
- 工作目录要使用 Herdr 主机可见的路径；不要在沙箱 `/tmp` 下创建临时目录后直接给 Herdr 用，可能两边不同步。
- 临时 workspace 用完后可以 `herdr workspace close <ws>` 清理；如果想在 UI 保留，就别 close，并可用 `herdr workspace focus <ws>` 切换过去。
- `agent prompt --wait` 不保证任务“成功”，只表示 Agent 进入 settled 状态；正确性要靠测试、文件和评审确认。
- 容器类开发进程（如测试数据库）与其它开发进程一样前台运行、手动管理：用 `podman compose up`（不加 `-d`），占用一个独立 Pane，Ctrl+C 即停止；不要把容器留在后台，避免遗留无人管理的进程。