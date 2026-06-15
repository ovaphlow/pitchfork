# 工厂培训与知识库系统 设计方案



## 一、系统整体目标

- **千人千面**：操作工、班组长、工程师、管理者看到的内容与功能各不相同

- **现场赋能**：扫码看操作视频/SOP、故障码查排故步骤，平均查询时间压缩到30秒内

- **合规兜底**：持证上岗、安全培训、复审提醒全自动，避免因资质过期被处罚

- **经验留存**：老师傅的调试窍门、异常处理“土办法”变成可搜索的结构化知识



---



## 二、业务角色与权限



### 2.1 角色定义

| 角色 | 核心需求 | 特殊权限 |

|---|---|---|

| 一线操作工 | 岗位SOP、设备点检步骤、安全警示 | 只能看本车间/本岗位内容 |

| 班组长 | 班组技能矩阵、派工前资质校验、日常培训记录 | 可查看管辖班组人员档案 |

| 技术员/工程师 | 上传维护手册、工艺参数、排故案例 | 知识库编辑、审批权限 |

| 培训管理员 | 课程分发、考试管理、培训报表 | 全厂培训数据查看 |

| 厂级管理者 | 技能分布热力图、人效分析 | 只读大屏看板 |



### 2.2 业务权限矩阵



#### 知识库中心

| 业务操作 | 操作工 | 班组长 | 技术员/工程师 | 培训管理员 |

|---|---|---|---|---|

| 查阅已发布的知识、SOP、案例 | ✅ | ✅ | ✅ | ✅ |

| 按分类/标签/关键词搜索知识 | ✅ | ✅ | ✅ | ✅ |

| 使用自然语言提问并获取答案 | ✅ | ✅ | ✅ | ✅ |

| 对知识条目评论、反馈错误、提问 | ✅ | ✅ | ✅ | ✅ |

| 提交自编的OPL或经验案例 | ✅ | ✅ | ✅ | ✅ |

| 创建/编辑知识条目（含SOP、案例） | | | ✅ | ✅ |

| 上传知识附件（视频、图片、文档） | | | ✅ | ✅ |

| 提交知识发布审批 | | | ✅ | ✅ |

| 审核并批准知识发布 | | | ✅（需授权） | ✅ |

| 设置知识分类与标签体系 | | | | ✅ |

| 查看知识版本历史与对比 | | | ✅ | ✅ |

| 旧版知识归档管理 | | | | ✅ |



#### 岗位与技能矩阵

| 业务操作 | 操作工 | 班组长 | 技术员/工程师 | 培训管理员 | 厂级管理者 |

|---|---|---|---|---|---|

| 查看本人技能画像与证书状态 | ✅ | ✅ | ✅ | ✅ | |

| 查看管辖班组人员技能画像 | | ✅ | | ✅ | |

| 查看全厂/跨部门技能矩阵 | | | | ✅ | ✅ |

| 定义岗位及关联的知识、技能、证书 | | | ✅ | ✅ | |

| 评定员工技能等级（实操/考试后确认） | | ✅ | ✅ | ✅ | |

| 修改员工技能等级档案 | | ✅（本班组） | | ✅ | |

| 设置技能有效期及复训规则 | | | | ✅ | |

| 派工时触发资质校验 | | ✅ | | | |



#### 培训管理与课程

| 业务操作 | 操作工 | 班组长 | 技术员/工程师 | 培训管理员 |

|---|---|---|---|---|

| 查看并完成指派给自己的培训任务 | ✅ | ✅ | ✅ | ✅ |

| 查看本班组培训完成情况 | | ✅ | | ✅ |

| 创建线上课程（章节、挂载知识） | | | ✅ | ✅ |

| 编辑课程结构与内容 | | | ✅ | ✅ |

| 设置课程学习完成条件 | | | ✅ | ✅ |

| 创建线下实操课程并记录安排 | | ✅ | ✅ | ✅ |

| 手动下发培训任务给指定人员/部门 | | ✅（本班组） | | ✅ |

| 配置自动培训触发规则（SOP升版/证书临期等） | | | | ✅ |

| 确认线下实操培训完成 | | ✅ | ✅ | |



#### 考试与考核

| 业务操作 | 操作工 | 班组长 | 技术员/工程师 | 培训管理员 |

|---|---|---|---|---|

| 参加线上考试 | ✅ | ✅ | ✅ | ✅ |

| 查看本人考试成绩与错题 | ✅ | ✅ | ✅ | ✅ |

| 管理题库（增删改试题） | | | ✅ | ✅ |

| 设置题目属性（难度、关联分类） | | | ✅ | ✅ |

| 创建试卷并配置组卷策略 | | | | ✅ |

| 设定合格分数、重考次数、防作弊设置 | | | | ✅ |

| 导出考试成绩报表 | | | | ✅ |

| 执行实操考核并打分/签名确认 | | ✅ | ✅ | ✅ |

| 实操考核结果录入系统并触发技能更新 | | ✅ | ✅ | ✅ |



#### 现场支持与设备扫码

| 业务操作 | 操作工 | 班组长 | 技术员/工程师 | 培训管理员 |

|---|---|---|---|---|

| 使用终端扫码查看设备知识 | ✅ | ✅ | ✅ | ✅ |

| 输入故障码查询排故步骤 | ✅ | ✅ | ✅ | ✅ |

| 语音输入故障描述搜索 | ✅ | ✅ | ✅ | ✅ |

| 扫码后一键发起报修/呼叫班组长 | ✅ | | | |

| 生成并管理设备/工位二维码 | | | ✅ | ✅ |

| 配置设备关联的知识条目 | | | ✅ | ✅ |

| 设定离线缓存内容范围 | | | ✅ | ✅ |



#### AI智能助手

| 业务操作 | 操作工 | 班组长 | 技术员/工程师 | 培训管理员 |

|---|---|---|---|---|

| 向机器人提问并获得带出处的答案 | ✅ | ✅ | ✅ | ✅ |

| 查看机器人引用的知识原文 | ✅ | ✅ | ✅ | ✅ |

| 配置预防性培训推送的触发阈值 | | | | ✅ |

| 管理机器人可引用的知识库范围 | | | | ✅ |



#### 数据分析与决策看板

| 业务操作 | 班组长 | 培训管理员 | 厂级管理者 |

|---|---|---|---|

| 查看本班组培训完成率、通过率 | ✅ | ✅ | |

| 查看全厂培训合规率、人均学时 | | ✅ | ✅ |

| 查看逾期未培训人员清单 | ✅（本班组） | ✅ | ✅ |

| 查看技能矩阵热力图 | | ✅ | ✅ |

| 导出培训报表 | | ✅ | ✅ |

| 查看培训数据与质量良率关联分析 | | | ✅ |



---



## 三、页面与功能设计



系统需提供两类终端：**移动端（工业平板/手机）** 供一线人员使用，**PC管理后台** 供培训管理员、工程师、管理者使用。



### 3.1 知识库中心



**移动端页面**

- **知识搜索首页**

  - 顶部搜索栏：支持文字输入和语音输入。

  - 快捷入口：扫码图标（调用摄像头扫设备码）、故障码查询、我的常用知识、最近浏览。

  - 分类导航：设备分类、岗位分类的图标入口。

- **扫码结果页**

  - 自动识别二维码关联的设备/工位。

  - 直接列出该设备下的所有生效知识：SOP列表、点检标准、常见故障、注意事项。每项有图标区分。

  - 支持一键报修按钮。

- **知识详情页**

  - 展示标题、版本号、生效日期、作者。

  - 正文区域：图文混排，视频播放器，音频播放条，步骤导航（如“步骤1/5”）。

  - 底部操作栏：点赞、收藏、反馈错误、我有疑问。可查看评论及回复。

- **知识贡献入口**（我的 -> 我要贡献）

  - 表单：标题、文字描述、上传图片/视频/语音口诀、选择关联设备/岗位、标签。提交后进入审核。



**PC管理端页面**

- **知识库列表管理**

  - 表格：标题、知识类型、版本号、状态（草稿/生效/已归档）、创建人、更新时间。

  - 筛选：按分类、岗位、设备、状态。批量操作。

- **知识编辑器（新增/编辑）**

  - 标题、知识类型选择器、关联设备、关联岗位、自定义标签。

  - 富文本正文编辑器，支持上传图片、视频、音频、附件。

  - 版本说明输入框。保存草稿、提交审核按钮。

- **版本与审批管理**

  - 版本列表：各版本号、状态、历史审批记录。

  - 审批流状态图示。新旧版本高亮对比，填写意见后通过或驳回。

- **分类与标签配置**

  - 树状分类管理，可增删改、排序。标签池增删改。



### 3.2 岗位与技能矩阵



**移动端页面**

- **我的技能档案**

  - 头像、工号、姓名、班组。

  - 技能雷达图，显示各项技能及等级（1-4）。

  - 证书列表：名称、到期日、状态。缺失技能提醒。

- **班组技能总览（班组长视角）**

  - 人员列表，技能项数和达标率。点击查看详情。按岗位、技能筛选。

- **实操技能考核执行（班组长/工程师）**

  - 扫描工牌或输入工号调出员工信息。

  - 选择岗位和技能项，弹出检查表（标准描述，勾选通过/不通过，拍照，备注）。

  - 考核人电子签名提交，系统更新技能等级及日期。



**PC管理端页面**

- **岗位定义管理**

  - 岗位列表树。编辑页：关联知识包、定义技能项及等级要求、证书要求、评估方式。

- **全厂技能矩阵看板**

  - 热力图：行=岗位/技能，列=员工，颜色深浅=等级。支持筛选车间，点击查看评定记录。

- **派工资质校验弹窗**（被集成场景）

  - 展示员工姓名、岗位、缺失技能项、过期证书。强制确认需填写原因。



### 3.3 培训管理与考试



**移动端页面**

- **我的培训任务列表**

  - 分“进行中”“已完成”“已逾期”选项卡。显示课程名、截止日期、进度。

  - 逾期项红字警告。

- **课程学习页**

  - 章节列表及状态。视频播放（防挂机随机弹窗），图文内容，PDF查看器。

  - 章节末随堂测验。完成后标记课程完成。

- **在线考试页**

  - 考前说明。全屏倒计时答题，支持标记题目，侧边栏题号跳转。

  - 交卷确认，立即显示分数及通过/未通过，可查看错题解析。

- **我的考试记录**

  - 列表展示每次考试科目、时间、分数、结果。



**PC管理端页面**

- **课程管理列表**：名称、类型、学时、知识条目数。

- **课程设计器**

  - 基本信息、章节管理（添加视频/图文/文件，设置随堂测验）。

  - 完成规则：全部学完/所有测验通过。

- **题库管理**

  - 题目列表，搜索筛选。新增题目支持单选、多选、判断、填空、看图识错，设置难度、标签。批量导入导出Excel。

- **试卷管理**

  - 组卷方式：固定题/随机抽题（按分类、难度、题数），乱序配置。

  - 安全考试特殊设置：必须满分、人脸拍照。

- **培训任务管理**

  - 手动创建：选择课程、指派对象、截止日期。

  - 自动规则配置：触发条件（SOP升版、证书临期、转岗），关联课程。

  - 监控：完成/未完成/逾期人数，催办，详细进度。



### 3.4 现场支持与离线



**移动端相关功能**

- 离线模式标识，浏览已缓存内容。

- 网络恢复后静默同步学习记录和考核结果，提示进度。

- 缓存管理：查看占用空间，手动清除。



### 3.5 AI智能助手



**移动端页面**

- 智能问答对话页：文字/语音提问，答案附引用来源链接。反馈有用/没用。

- 主动推送消息：触发预防性培训时消息卡片。



**PC管理端页面**

- 语料库与问答管理：查看高频问题，手动添加问答对，屏蔽不当结果。

- 预防性推送规则设置：关联设备故障率指标、阈值、目标岗位与课程。



### 3.6 数据分析看板



**大屏/PC看板页面**

- **培训运营总览**

  - 指标卡片：总培训人次、本月完成率、一次性通过率、人均学时。

  - 各车间完成率柱状图，逾期任务数趋势线。逾期未完成人员清单导出。

- **技能热力图看板**

  - 车间筛选，技能-人员矩阵，颜色标识等级。统计达标率、覆盖率、需复训人数。

- **质量关联分析（高级）**

  - 对比产线人员技能达标率与一次良率趋势图。



---



## 四、关键业务流程细节



### 4.1 SOP升版触发全员再培训

1. 工程师编辑SOP，提交审核。

2. 培训管理员批准后，新版本生效，旧版本归档。

3. 系统根据岗位关联自动创建培训任务，课程含新旧差异，截止日期3天。

4. 相关岗位员工收到通知，学习并通过测验。

5. 跟踪完成率，逾期未完成者标红并通知班组长。

6. 员工完成培训后，“已培训知识版本”记录更新为新版本号。



### 4.2 实操考核与技能更新

1. 班组长在移动端进入“考核执行”，扫描员工工牌。

2. 选择岗位与技能项，展示检查表。

3. 逐项观察勾选，必要时拍照。

4. 电子签名并提交。

5. 系统将考核记录存入档案，更新技能最后评定日期和等级。

6. 若全部必学技能达标，该员工自动通过派工资质校验。



### 4.3 离线学习与记录回传

1. 联网时应用根据岗位自动缓存知识和待学课程到本地。

2. 网络中断进入离线模式，学习或查阅已缓存内容。

3. 进度和答题结果暂存本地。

4. 网络恢复后自动同步本地记录至服务器，冲突时按规则合并。

5. 上传成功后清除待同步标记。



---



## 五、业务数据库设计（灵活扩展，使用JSONB）



### 5.1 知识条目表 `knowledge_entries`

| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| title | 字符串 | 知识标题 |

| type | 枚举/字符串 | SOP / OPL / 故障案例 / 安全须知 / 工艺参数表 / 设备手册 |

| status | 枚举/字符串 | 草稿 / 已发布 / 已归档 |

| current_version_id | 外键 | 指向当前生效版本记录 |

| category_ids | 数组/JSON | 关联多级分类ID |

| device_ids | 数组/JSON | 关联设备ID |

| position_ids | 数组/JSON | 关联岗位ID |

| tags | 数组/JSON | 标签列表 |

| **extra** | **jsonb** | 扩展属性，按知识类型存储不同结构 |

| created_by / updated_by | 外键 | |

| created_at / updated_at | 时间戳 | |



**extra 示例：**

```json

// 故障案例

{

  "fault_code": "E022",

  "symptom": "锁模力异常",

  "reasons": ["液压阀卡滞", "压力传感器漂移"],

  "repair_steps": ["检查阀芯", "校准传感器"]

}

// 工艺参数表

{

  "parameters": [

    {"name": "温度", "value": "220±5℃", "unit": "℃"},

    {"name": "压力", "value": "80bar", "unit": "bar"}

  ]

}
抱歉，是我之前回复的格式问题，5.2 节开始没有正确用代码块包住。



下面是从 **5.2 节到文档结束** 的完整 Markdown 内容，你直接长按复制、保存到备忘录即可，格式是干净的。



---



```markdown

### 5.2 知识版本表 `knowledge_versions`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| entry_id | 外键 | |

| version_number | 字符串 | 如 v1.0 |

| content | 文本 | 正文（富文本） |

| **content_blocks** | **jsonb** | 结构化内容块，支持图文、视频、音频、步骤等混合编排 |

| attachment_files | jsonb | 附件列表 |

| change_note | 文本 | 变更说明 |

| status | 枚举 | 草稿/审批中/已生效/已归档 |

| approved_by / approved_at | | |

| created_by / created_at | | |



**content_blocks 结构示例：**

```json

[

  {"type": "text", "content": "操作前确认防护罩关闭"},

  {"type": "image", "url": "...", "caption": "防护罩图示"},

  {"type": "video", "url": "...", "duration": 120},

  {"type": "step", "steps": ["步骤1", "步骤2"]},

  {"type": "audio", "url": "...", "note": "口诀: 一调二紧三润滑"}

]

```



### 5.3 知识反馈表 `knowledge_feedbacks`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| entry_id | 外键 | |

| type | 枚举 | 提问/纠错/建议 |

| content | 文本 | |

| **reply** | **jsonb** | 多条官方回复及时间、回复人 |

| status | 枚举 | 待处理/已回复/已关闭 |

| created_by / created_at | | |



### 5.4 岗位定义表 `positions`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| name | 字符串 | 岗位名称 |

| parent_id | 外键 | 支持树形（车间->工序->岗位） |

| **skill_requirements** | **jsonb** | 技能要求（技能ID、等级、证书要求） |

| **assessment_config** | **jsonb** | 评估方式：线上/实操/组合及规则 |



**skill_requirements 示例：**

```json

[

  {"skill_id": "注塑机操作", "required_level": 3, "certificate_required": false},

  {"skill_id": "行车操作", "required_level": 2, "certificate_required": true, "cert_validity_days": 365}

]

```



### 5.5 技能项字典表 `skills`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| name | 字符串 | |

| category | 字符串 | 操作/安全/维保等 |

| **evaluation_criteria** | **jsonb** | 1-4级描述，或实操检查表结构 |

| **default_validity** | **jsonb** | 默认复审有效期 |



**evaluation_criteria 示例：**

```json

{

  "levels": [

    {"level": 1, "desc": "了解理论"},

    {"level": 2, "desc": "在指导下操作"},

    {"level": 3, "desc": "独立操作"},

    {"level": 4, "desc": "可培训他人"}

  ],

  "practical_checklist": [

    {"item": "劳保穿戴规范", "required": true},

    {"item": "设备点检流程", "required": true}

  ]

}

```



### 5.6 员工技能档案表 `employee_skills`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| employee_id | 外键 | |

| skill_id | 外键 | |

| current_level | 整数 | 1-4 |

| assessed_date | 日期 | |

| assessor_id | 外键 | |

| expire_date | 日期 | 复审到期日 |

| **assessment_record** | **jsonb** | 最近考核详情（检查表结果、照片、评语） |



**assessment_record 示例：**

```json

{

  "method": "practical",

  "checklist_results": [

    {"item": "劳保穿戴规范", "pass": true, "photo": "url"},

    {"item": "设备点检流程", "pass": false, "note": "漏检压力表"}

  ],

  "assessor_signature": "data:image/png;base64,...",

  "comment": "需加强点检细节"

}

```



### 5.7 证书管理表 `certificates`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| name | 字符串 | 证书名称 |

| **validity_config** | **jsonb** | 有效期类型（固定日期/取证后N天）、提醒规则 |



### 5.8 课程表 `courses`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| title | 字符串 | |

| type | 枚举 | 线上/线下实操 |

| cover_image | 字符串 | |

| target_positions | 数组/json | 面向岗位ID |

| **completion_rules** | **jsonb** | 完成条件（全部章节/随堂测验全过） |

| status | 枚举 | 启用/停用 |

| created_by | 外键 | |



### 5.9 课程章节表 `course_chapters`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| course_id | 外键 | |

| sort_order | 整数 | |

| title | 字符串 | |

| **blocks** | **jsonb** | 与知识版本 content_blocks 类似，多媒体混合编排 |

| **quiz_config** | **jsonb** | 随堂测验配置（题数、题库或固定题） |



### 5.10 试题库表 `questions`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| type | 枚举 | 单选/多选/判断/填空/看图识错 |

| difficulty | 整数 | 1-5 |

| tags | 数组/json | 关联知识标签 |

| **content** | **jsonb** | 题干预结构（文本、图片） |

| **options** | **jsonb** | 选项（可含图片） |

| answer | **jsonb** | 正确答案（多选存数组） |

| explanation | 文本 | 解析 |



**示例：**

```json

// content: {"text": "下列哪项是正确的？", "image": "url"}

// options: [{"key": "A", "text": "选项A", "image": null}, ...]

// answer: {"keys": ["A", "C"]}

```



### 5.11 试卷表 `exam_papers`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| title | 字符串 | |

| duration_minutes | 整数 | |

| pass_score | 数值 | |

| **generation_strategy** | **jsonb** | 组卷策略（固定题+随机抽题配置） |

| **anti_cheat_config** | **jsonb** | 乱序、人脸、挂机检测 |

| **extra_rules** | **jsonb** | 重考次数、冷却时间、必须满分等 |



**generation_strategy 示例：**

```json

{

  "fixed_question_ids": [101,102],

  "random_sections": [

    {

      "tag": "安全",

      "difficulty": {"min": 3, "max": 5},

      "type": "single_choice",

      "count": 10,

      "points_each": 2

    }

  ],

  "shuffle_questions": true,

  "shuffle_options": true

}

```



### 5.12 培训任务表 `training_assignments`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| course_id | 外键 | |

| assign_type | 枚举 | 手动指派/自动触发 |

| **trigger_rule** | **jsonb** | 自动触发条件详情 |

| deadline | 时间戳 | |

| target_type | 枚举 | 按用户/岗位/部门 |

| target_ids | 数组/json | 目标ID列表 |

| created_by / created_at | | |



### 5.13 学习进度表 `learning_progress`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| assignment_id | 外键 | |

| employee_id | 外键 | |

| chapter_id | 外键 | |

| progress_percent | 数值 | |

| status | 枚举 | 学习中/已完成 |

| **detail** | **jsonb** | 视频观看位置、测验尝试记录等 |



### 5.14 考试记录表 `exam_records`



| 字段名 | 类型 | 说明 |

|---|---|---|

| id | 主键 | |

| employee_id | 外键 | |

| paper_id | 外键 | |

| start_time / end_time | 时间戳 | |

| score | 数值 | |

| passed | 布尔 | |

| **answers_snapshot** | **jsonb** | 交卷时题目快照与用户答案 |

| **cheat_flags** | **jsonb** | 防作弊事件记录（切屏、人脸失败） |



**answers_snapshot 示例：**

```json

[

  {

    "question_id": 501,

    "type": "single_choice",

    "content": {...},

    "user_answer": {"key": "B"},

    "correct_answer": {"key": "C"},

    "score
已按照你的要求，将所有 ID 字段修改为 `VARCHAR(32)` 类型，并使用去掉横线的 UUID 作为默认值。以下是完整的 PostgreSQL 建表脚本，可直接执行。



```sql

-- ============================================================

-- 工厂培训与知识库系统 - 数据库创建脚本 (PostgreSQL)

-- 所有ID字段统一使用 VARCHAR(32) ，默认生成32位UUID

-- ============================================================



-- 启用 pgcrypto 扩展以使用 gen_random_uuid()

CREATE EXTENSION IF NOT EXISTS "pgcrypto";



-- 定义生成32位UUID的辅助函数

CREATE OR REPLACE FUNCTION generate_uid() RETURNS VARCHAR(32) AS $$

BEGIN

    RETURN replace(gen_random_uuid()::text, '-', '');

END;

$$ LANGUAGE plpgsql;



-- ======================= 基础表 =======================



-- 部门表

CREATE TABLE departments (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    name            VARCHAR(100) NOT NULL,

    parent_id       VARCHAR(32) REFERENCES departments(id),

    description     TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 用户表（系统登录账号）

CREATE TABLE users (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    username        VARCHAR(50) NOT NULL UNIQUE,

    password_hash   VARCHAR(255) NOT NULL,

    real_name       VARCHAR(50),

    email           VARCHAR(100),

    is_active       BOOLEAN NOT NULL DEFAULT TRUE,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 员工表（对应实体员工）

CREATE TABLE employees (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    user_id         VARCHAR(32) REFERENCES users(id),

    employee_no     VARCHAR(50) NOT NULL UNIQUE,

    name            VARCHAR(50) NOT NULL,

    department_id   VARCHAR(32) REFERENCES departments(id),

    position        VARCHAR(100),

    hire_date       DATE,

    is_on_job       BOOLEAN NOT NULL DEFAULT TRUE,

    extra           JSONB DEFAULT '{}',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- ======================= 知识库相关 =======================



-- 知识条目主表

CREATE TABLE knowledge_entries (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    title               VARCHAR(500) NOT NULL,

    type                VARCHAR(50) NOT NULL CHECK (type IN ('SOP', 'OPL', '故障案例', '安全须知', '工艺参数表', '设备手册')),

    status              VARCHAR(20) NOT NULL DEFAULT '草稿' CHECK (status IN ('草稿', '已发布', '已归档')),

    current_version_id  VARCHAR(32),

    category_ids        VARCHAR(32)[] DEFAULT '{}',

    device_ids          VARCHAR(32)[] DEFAULT '{}',

    position_ids        VARCHAR(32)[] DEFAULT '{}',

    tags                VARCHAR(100)[] DEFAULT '{}',

    extra               JSONB DEFAULT '{}',

    created_by          VARCHAR(32) REFERENCES users(id),

    updated_by          VARCHAR(32) REFERENCES users(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 知识版本表

CREATE TABLE knowledge_versions (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    entry_id        VARCHAR(32) NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE,

    version_number  VARCHAR(20) NOT NULL,

    content         TEXT,

    content_blocks  JSONB DEFAULT '[]',

    attachment_files JSONB DEFAULT '[]',

    change_note     TEXT,

    status          VARCHAR(20) NOT NULL DEFAULT '草稿' CHECK (status IN ('草稿', '审批中', '已生效', '已归档')),

    approved_by     VARCHAR(32) REFERENCES users(id),

    approved_at     TIMESTAMPTZ,

    created_by      VARCHAR(32) REFERENCES users(id),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 补充 knowledge_entries 的外键

ALTER TABLE knowledge_entries 

    ADD CONSTRAINT fk_current_version 

    FOREIGN KEY (current_version_id) REFERENCES knowledge_versions(id) ON DELETE SET NULL;



-- 知识反馈表

CREATE TABLE knowledge_feedbacks (

    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    entry_id    VARCHAR(32) NOT NULL REFERENCES knowledge_entries(id) ON DELETE CASCADE,

    type        VARCHAR(20) NOT NULL CHECK (type IN ('提问', '纠错', '建议')),

    content     TEXT NOT NULL,

    reply       JSONB DEFAULT '[]',

    status      VARCHAR(20) NOT NULL DEFAULT '待处理' CHECK (status IN ('待处理', '已回复', '已关闭')),

    created_by  VARCHAR(32) REFERENCES users(id),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- ======================= 岗位与技能相关 =======================



-- 岗位定义表

CREATE TABLE positions (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    name                VARCHAR(100) NOT NULL,

    parent_id           VARCHAR(32) REFERENCES positions(id),

    skill_requirements  JSONB DEFAULT '[]',

    assessment_config   JSONB DEFAULT '{}',

    extra               JSONB DEFAULT '{}',

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 技能项字典表

CREATE TABLE skills (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    name                VARCHAR(100) NOT NULL,

    category            VARCHAR(50) CHECK (category IN ('操作', '安全', '维保', '其它')),

    evaluation_criteria JSONB DEFAULT '{}',

    default_validity    JSONB DEFAULT '{}',

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 员工技能档案表

CREATE TABLE employee_skills (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    employee_id         VARCHAR(32) NOT NULL REFERENCES employees(id) ON DELETE CASCADE,

    skill_id            VARCHAR(32) NOT NULL REFERENCES skills(id),

    current_level       SMALLINT CHECK (current_level BETWEEN 1 AND 4),

    assessed_date       DATE,

    assessor_id         VARCHAR(32) REFERENCES users(id),

    expire_date         DATE,

    assessment_record   JSONB DEFAULT '{}',

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (employee_id, skill_id)

);



-- 证书管理表

CREATE TABLE certificates (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    name            VARCHAR(200) NOT NULL,

    validity_config JSONB DEFAULT '{}',

    description     TEXT,

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 员工-证书关联表

CREATE TABLE employee_certificates (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    employee_id     VARCHAR(32) NOT NULL REFERENCES employees(id) ON DELETE CASCADE,

    certificate_id  VARCHAR(32) NOT NULL REFERENCES certificates(id),

    issue_date      DATE,

    expire_date     DATE,

    attachment      VARCHAR(500),

    extra           JSONB DEFAULT '{}',

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    UNIQUE (employee_id, certificate_id)

);



-- ======================= 培训与考试相关 =======================



-- 课程表

CREATE TABLE courses (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    title               VARCHAR(200) NOT NULL,

    type                VARCHAR(20) NOT NULL CHECK (type IN ('线上', '线下实操')),

    cover_image         VARCHAR(500),

    target_positions    VARCHAR(32)[] DEFAULT '{}',

    completion_rules    JSONB DEFAULT '{}',

    status              VARCHAR(20) NOT NULL DEFAULT '启用' CHECK (status IN ('启用', '停用')),

    created_by          VARCHAR(32) REFERENCES users(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 课程章节表

CREATE TABLE course_chapters (

    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    course_id   VARCHAR(32) NOT NULL REFERENCES courses(id) ON DELETE CASCADE,

    sort_order  SMALLINT NOT NULL DEFAULT 0,

    title       VARCHAR(200) NOT NULL,

    blocks      JSONB DEFAULT '[]',

    quiz_config JSONB DEFAULT '{}',

    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 试题库表

CREATE TABLE questions (

    id          VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    type        VARCHAR(20) NOT NULL CHECK (type IN ('单选', '多选', '判断', '填空', '看图识错')),

    difficulty  SMALLINT CHECK (difficulty BETWEEN 1 AND 5),

    tags        VARCHAR(100)[] DEFAULT '{}',

    content     JSONB NOT NULL DEFAULT '{}',

    options     JSONB DEFAULT '[]',

    answer      JSONB NOT NULL,

    explanation TEXT,

    created_by  VARCHAR(32) REFERENCES users(id),

    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 试卷表

CREATE TABLE exam_papers (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    title               VARCHAR(200) NOT NULL,

    duration_minutes    SMALLINT NOT NULL,

    pass_score          NUMERIC(5,2) NOT NULL,

    generation_strategy JSONB NOT NULL DEFAULT '{}',

    anti_cheat_config   JSONB DEFAULT '{}',

    extra_rules         JSONB DEFAULT '{}',

    created_by          VARCHAR(32) REFERENCES users(id),

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 培训任务表

CREATE TABLE training_assignments (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    course_id       VARCHAR(32) NOT NULL REFERENCES courses(id),

    assign_type     VARCHAR(20) NOT NULL CHECK (assign_type IN ('手动指派', '自动触发')),

    trigger_rule    JSONB DEFAULT '{}',

    deadline        TIMESTAMPTZ,

    target_type     VARCHAR(20) NOT NULL CHECK (target_type IN ('用户', '岗位', '部门')),

    target_ids      VARCHAR(32)[] NOT NULL DEFAULT '{}',

    created_by      VARCHAR(32) REFERENCES users(id),

    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 学习进度表

CREATE TABLE learning_progress (

    id              VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    assignment_id   VARCHAR(32) NOT NULL REFERENCES training_assignments(id) ON DELETE CASCADE,

    employee_id     VARCHAR(32) NOT NULL REFERENCES employees(id),

    chapter_id      VARCHAR(32) REFERENCES course_chapters(id),

    progress_percent NUMERIC(5,2) DEFAULT 0,

    status          VARCHAR(20) NOT NULL DEFAULT '学习中' CHECK (status IN ('学习中', '已完成')),

    detail          JSONB DEFAULT '{}',

    started_at      TIMESTAMPTZ,

    completed_at    TIMESTAMPTZ,

    UNIQUE (assignment_id, employee_id, chapter_id)

);



-- 考试记录表

CREATE TABLE exam_records (

    id                  VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    employee_id         VARCHAR(32) NOT NULL REFERENCES employees(id),

    paper_id            VARCHAR(32) NOT NULL REFERENCES exam_papers(id),

    start_time          TIMESTAMPTZ NOT NULL,

    end_time            TIMESTAMPTZ,

    score               NUMERIC(5,2),

    passed              BOOLEAN,

    answers_snapshot    JSONB DEFAULT '[]',

    cheat_flags         JSONB DEFAULT '{}',

    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- ======================= 现场支持相关 =======================



-- 设备二维码表（设备主表简化，直接关联设备ID）

CREATE TABLE device_qr_codes (

    id                      VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    device_id               VARCHAR(32) NOT NULL,

    code                    VARCHAR(100) NOT NULL UNIQUE,

    linked_knowledge_ids    VARCHAR(32)[] DEFAULT '{}',

    offline_cache_config    JSONB DEFAULT '{}',

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- 离线缓存策略表

CREATE TABLE offline_cache_policies (

    id                      VARCHAR(32) PRIMARY KEY DEFAULT generate_uid(),

    position_id             VARCHAR(32) REFERENCES positions(id),

    cache_size_limit_mb     SMALLINT DEFAULT 100,

    include_knowledge_types VARCHAR(50)[] DEFAULT '{}',

    include_recent_days     SMALLINT DEFAULT 30,

    extra                   JSONB DEFAULT '{}',

    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()

);



-- ======================= 索引创建 =======================



-- 知识表索引

CREATE INDEX idx_knowledge_entries_type_status ON knowledge_entries(type, status);

CREATE INDEX idx_knowledge_entries_tags ON knowledge_entries USING GIN(tags);

CREATE INDEX idx_knowledge_entries_extra ON knowledge_entries USING GIN(extra);

CREATE INDEX idx_knowledge_versions_entry_id ON knowledge_versions(entry_id);



-- 反馈表索引

CREATE INDEX idx_knowledge_feedbacks_entry_id ON knowledge_feedbacks(entry_id);



-- 岗位技能表索引

CREATE INDEX idx_employee_skills_employee ON employee_skills(employee_id);

CREATE INDEX idx_employee_skills_skill ON employee_skills(skill_id);



-- 课程章节索引

CREATE INDEX idx_course_chapters_course ON course_chapters(course_id);



-- 试题索引

CREATE INDEX idx_questions_tags ON questions USING GIN(tags);

CREATE INDEX idx_questions_content ON questions USING GIN(content);



-- 培训任务索引

CREATE INDEX idx_training_assignments_course ON training_assignments(course_id);



-- 学习进度索引

CREATE INDEX idx_learning_progress_assignment ON learning_progress(assignment_id);

CREATE INDEX idx_learning_progress_employee ON learning_progress(employee_id);



-- 考试记录索引

CREATE INDEX idx_exam_records_employee ON exam_records(employee_id);

CREATE INDEX idx_exam_records_paper ON exam_records(paper_id);



-- ======================= 注释 =======================



COMMENT ON TABLE knowledge_entries IS '知识条目主表';

COMMENT ON COLUMN knowledge_entries.extra IS '扩展属性，如故障代码、参数列表等';

COMMENT ON TABLE knowledge_versions IS '知识版本表';

COMMENT ON COLUMN knowledge_versions.content_blocks IS '结构化内容块，支持图文/视频/音频/步骤';

COMMENT ON TABLE employee_skills IS '员工技能档案';

COMMENT ON COLUMN employee_skills.assessment_record IS '最近考核详情，含检查表、照片、评语';

COMMENT ON TABLE exam_papers IS '试卷表';

COMMENT ON COLUMN exam_papers.generation_strategy IS '组卷策略JSON';

COMMENT ON TABLE exam_records IS '考试记录';

COMMENT ON COLUMN exam_records.answers_snapshot IS '交卷时的题目快照与用户答案';

```



该脚本将确保所有主键、外键及引用字段都使用 32 位 UUID 字符串，同时保留了原有的扩展性和性能索引。
