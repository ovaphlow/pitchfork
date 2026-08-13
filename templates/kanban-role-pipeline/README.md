# kanban-role-pipeline 模板 (通用版)

在**任意 git 项目**上搭「计划 → 开发 → 测试 → 终审」四角色 Hermes kanban 流水线。
不绑定任何具体仓库;复制到目标项目,配 3 个值,跑一条命令,剩下交给 dispatcher。

## 两种模式

| | 模式 A: 纯提示词 | 模式 C: 薄 SOUL (推荐) |
|---|---|---|
| 角色要求载体 | `prompts/*.md` 直接作 codex 提示词参数 | 每个角色 profile + 薄 SOUL.md |
| 派单方式 | 手动/脚本 `codex exec` + 手动更新看板 | dispatcher 自动拉起 worker |
| 建 profile | 不需要 | 需要 (setup.sh 代劳) |
| 适用 | 临时任务 / 不想动 ~/.hermes | 常驻流水线 / 需要心跳与审计 |

## 快速开始 (模式 C)

```bash
# 1. 复制模板到项目(或直接在模板目录操作,路径参数化)
cd /path/to/your-project
cp -r /home/ovaphlow/pitchfork/templates/kanban-role-pipeline .kanban-role/

# 2. 初始化: 建角色 profiles + 建 board + 写入薄 SOUL
bash .kanban-role/setup.sh --project /path/to/your-project --board <slug>

# 3. (可选) 配置角色 → codex profile 映射,默认:
#    plan=terra  dev=deepseek  tester=luna  review=luna
#    通过环境变量覆盖: KANBAN_ROLE_CODEX_PLAN=xxx bash .kanban-role/setup.sh ...

# 4. 钉死硬性验证命令(可选但推荐,确定性闸门)
TEST_CMD="pnpm -C ui-astro build" bash .kanban-role/chain.sh "实现 xxx"

# 5. 建卡链: T1计划 → T2开发 → T3测试 → T4终审
bash .kanban-role/chain.sh "任务描述" [--no-wait]

# 6. 盯看板(另一终端)
hermes kanban --board <slug> watch
# 终审通过后,人工 merge loop/<slug> 分支 —— merge 永远是人的决定
```

## 模式 A (纯提示词,无 SOUL)

```bash
# 生成一张卡 + 该阶段对应的 codex exec 命令清单(含完整角色提示词)
bash .kanban-role/chain.sh "任务描述" --mode prompt --dry-run
# 按输出逐条执行,跑完用 hermes kanban --board <slug> complete <id> 更新看板
```

## 卡链与状态语义

```
T1 计划(plan) → T2 开发(dev) → T3 测试(tester) → T4 终审(review)
每张卡 parent=前一张卡: 顺序执行,父卡 summary+metadata 自动注入子卡 context
```

| Hermes 状态 | 含义 |
|---|---|
| todo | 等前置卡 done |
| ready | 可派单(dispatcher 自动) |
| running | 执行中(dispatcher 自动) |
| review | 评审门(计划/测试/终审共用,`request_review` 进入) |
| blocked | 等人/失败停转(needs_input)或等依赖(dependency) |
| done | 终审通过 / 阶段完成 |
| archived | 清理归档 |

## 失败回退约定 (写进每个 SOUL 与提示词)

- **测试失败**: 测试 worker 评论失败报告(前置/复现/预期/实际/证据) → 建「修复 #N」卡
  (assignee=dev, parent=测试卡) 或 `kanban_block(kind=needs_input)` 等人工;
  验收口径问题 → 「计划修订 #N」卡 (assignee=plan)
- **终审不通过**: 同测试失败,「修复 #N」回 dev 或「计划修订 #N」回 plan
- **轮次上限 3**: 超过后评审 worker 直接 `kanban_block(needs_input)` 转人工仲裁
- **环境问题**(401/429/缺服务): 不烧修复轮次,blocked 等人修环境后 unblock

## 注意事项

- 一个 repo 同时只跑一条流水线:chain.sh 有锁(项目下 `.hermes/kanban-loop.lock`),
  重叠启动会被拒绝;`--force` 或 `LOCK_STALE_AFTER` 处理陈旧锁
- worker 报告目录 `$HERMES_KANBAN_WORKSPACE/.hermes/loop/` 必须 gitignore
  (模板 setup.sh 已追加;手工复制时记得)
- chain.sh 要求 git 工作区干净(保护你的未提交改动)
- 阶段细节(验收口径、契约、命名)写进**卡 body**,SOUL 只放不可谈判的
  行为约束 —— 见 `bodies.md` 模板
