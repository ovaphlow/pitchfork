# 卡 body 模板与变量说明

chain.sh 已按下面模板生成 T1–T4 的 body。手工建卡时按需修改。

## T1 计划卡 (assignee=plan, 无 parent)
```
任务: <任务描述>

分支: loop/<slug>
计划文件: docs/plans/NNN.<slug>.md (按此编号与路径写入 docs/plans/)
轮次: 1

硬性验证命令(调度者钉死, 必须全部字面执行且 exit 0, 不得修改/重解释/跳过):
<TEST_CMD>

角色: 计划。分析任务→撰写实施计划(含「角色、职责与交接」章节)→
验收标准只写构建/单元测试/路由测试可验证的内容(不写 e2e/数据库/浏览器验收)→
提交后以 metadata.plan_path 交付。
```

## T2 开发卡 (assignee=dev, parent=T1)
```
任务: <任务描述>
分支: loop/<slug>
计划路径: 以父卡 metadata.plan_path 为准, 默认 docs/plans/NNN.<slug>.md
轮次: 1

角色: 开发。按计划实现并提交, 跑默认验证(不访问数据库)。
```

## T3 测试卡 (assignee=tester, parent=T2)
```
任务: <任务描述>
分支: loop/<slug>
计划路径: docs/plans/NNN.<slug>.md
轮次: 1

硬性验证命令(…):
<TEST_CMD>

角色: 测试。逐条验证验收标准, 硬性命令字面执行; 不执行 e2e/数据库集成测试;
构建+单测全过即通过; 失败 → test-report.json + 评论失败报告 + 建「修复 #N」卡。
```

## T4 终审卡 (assignee=review, parent=T3)
```
任务: <任务描述>
分支: loop/<slug>
计划路径: docs/plans/NNN.<slug>.md
轮次: 1

角色: 终审(评审角色, 最终验收)。只读审查开发 diff、测试证据与残余风险, 抽查 1-3 条关键命令;
通过 → 评论交付清单(分支/merge 命令/提交/计划路径)并 complete;
不通过 → 建「修复 #N」卡(回 dev)或「计划修订 #N」卡(回 plan), 轮次上限 3。
```

## 变量说明
| 占位符 | 含义 | 来源 |
|---|---|---|
| {TASK} | 任务描述 | chain.sh 第一个参数 |
| {BRANCH} | loop/<slug> | slug 由任务描述自动生成 |
| {PLAN_PATH} | docs/plans/NNN.<slug>.md | 脚本按已有计划编号 max+1 |
| {TEST_CMD} | 硬性验证命令 | 环境变量 TEST_CMD |
| 轮次: N | 评审轮次计数 | 修复/修订卡 N+1, 上限 3 |

## 修复/修订卡 (评审不通过时由评审 worker 创建)
```
「修复 #N」卡: assignee=dev, parent=评审卡
任务: <任务描述> — 修复评审/测试发现的问题
分支: loop/<slug>
轮次: N+1

角色: 开发。读父卡失败报告逐条修复, 不重写计划; 修复后 complete。

「计划修订 #N」卡: assignee=plan, parent=评审卡
任务: <任务描述> — 修订计划(验收口径/需求决策)
分支: loop/<slug>
轮次: N+1

角色: 计划。读父卡 findings 修订验收口径/计划内容, 提交后以新 plan_path 交付。
```
