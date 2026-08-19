// 理论培训（merit）API 客户端。
//
// 契约先行：按 service-prototype 的路由契约
// （/crate-api/prototype/v1 下 courses/chapters/questions/papers/
// assignments/progress/exam-records 全部 7 组资源）实现，全部方法经由
// merit-request.ts 的共享 request 封装发出（token 注入、401 处理、
// ApiRequestError、{records, meta} 列表解析）。业务枚举与父卡契约保持
// 一致的中文值；时间字段为 ISO 字符串（服务端 OffsetDateTime/RFC3339）。

import { meritRequest } from "./merit-request";
export { ApiRequestError } from "./merit-request";

// ========================================================================
//  通用
// ========================================================================

/** 列表响应：仓库约定 { "records": [...], "meta": { "total": N } } */
export interface MeritPage<T> {
  records: T[];
  meta: { total: number };
}

function meritQuery(params: Record<string, string | number | boolean | undefined>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return query.toString() ? `?${query.toString()}` : "";
}

// ========================================================================
//  课程（courses）
// ========================================================================

/** 课程专题（topic） */
export type CourseTopic =
  | "客流评估与引导"
  | "基础设施保障"
  | "自然灾害防范"
  | "安全应急处置"
  | "舆情应对";

/** 课程授课方式（type） */
export type CourseDeliveryType = "线上授课" | "线下授课";

/** 课程状态（status） */
export type CourseStatus = "启用" | "停用";

/** 课程 */
export interface Course {
  id: string;
  title: string;
  topic: CourseTopic;
  type: CourseDeliveryType;
  status: CourseStatus;
  metadata: Record<string, unknown>;
  created_by: string;
  created_at: string;
  updated_at: string;
}

/** 课程创建/更新负载；status 省略时后端默认「启用」 */
export interface CourseInput {
  title: string;
  topic: CourseTopic;
  type: CourseDeliveryType;
  status?: CourseStatus;
  metadata?: Record<string, unknown>;
  created_by?: string;
}

export type CourseList = MeritPage<Course>;

/** 课程列表筛选：topic/type/status + limit/offset 分页 */
export type CourseListParams = {
  topic?: CourseTopic;
  type?: CourseDeliveryType;
  status?: CourseStatus;
  limit?: number;
  offset?: number;
};

export function listCourses(params: CourseListParams = {}): Promise<CourseList> {
  return meritRequest<CourseList>(`/courses${meritQuery(params)}`);
}

export function createCourse(input: CourseInput): Promise<Course> {
  return meritRequest<Course>("/courses", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function getCourse(id: string): Promise<Course> {
  return meritRequest<Course>(`/courses/${encodeURIComponent(id)}`);
}

export function updateCourse(id: string, input: CourseInput): Promise<Course> {
  return meritRequest<Course>(`/courses/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export async function deleteCourse(id: string): Promise<void> {
  await meritRequest<void>(`/courses/${encodeURIComponent(id)}`, { method: "DELETE" });
}

// ========================================================================
//  课程章节（chapters）
// ========================================================================

/** 内容块类型 */
export type ContentBlockType = "视频" | "图文" | "互动问答";

/** 章节内容块：type 必填（视频/图文/互动问答），其余字段按块类型自由扩展 */
export type ContentBlock = { type: ContentBlockType } & Record<string, unknown>;

/** 课程章节 */
export interface Chapter {
  id: string;
  course_id: string;
  sort_order: number;
  title: string;
  blocks: ContentBlock[];
  /** JSONB 扩展，原样回显；未设置时为 null */
  quiz_config: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

/** 章节创建/更新负载；sort_order 默认 0、blocks 默认 [] */
export interface ChapterInput {
  sort_order?: number;
  title: string;
  blocks?: ContentBlock[];
  quiz_config?: Record<string, unknown> | null;
}

export type ChapterList = MeritPage<Chapter>;

/** 章节列表筛选：仅分页（所属课程由路由路径决定） */
export type ChapterListParams = {
  limit?: number;
  offset?: number;
};

export function listCourseChapters(courseId: string, params: ChapterListParams = {}): Promise<ChapterList> {
  return meritRequest<ChapterList>(`/courses/${encodeURIComponent(courseId)}/chapters${meritQuery(params)}`);
}

export function createChapter(courseId: string, input: ChapterInput): Promise<Chapter> {
  return meritRequest<Chapter>(`/courses/${encodeURIComponent(courseId)}/chapters`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function getChapter(id: string): Promise<Chapter> {
  return meritRequest<Chapter>(`/chapters/${encodeURIComponent(id)}`);
}

export function updateChapter(id: string, input: ChapterInput): Promise<Chapter> {
  return meritRequest<Chapter>(`/chapters/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export async function deleteChapter(id: string): Promise<void> {
  await meritRequest<void>(`/chapters/${encodeURIComponent(id)}`, { method: "DELETE" });
}

// ========================================================================
//  题库（questions）
// ========================================================================

/** 题型 */
export type QuestionType = "单选" | "多选" | "判断" | "填空";

/** 判断题型答案 */
export type JudgmentAnswer = "正确" | "错误";

/** 题目 */
export interface Question {
  id: string;
  type: QuestionType;
  /** 难度 1-5 */
  difficulty: number;
  tags: string[];
  content: string;
  /** 单选/多选至少 2 项；判断/填空为 [] */
  options: string[];
  /** 单选/判断/填空为字符串，多选为字符串数组 */
  answer: string | string[];
  explanation: string;
  metadata: Record<string, unknown>;
  created_by: string;
  created_at: string;
  updated_at: string;
}

/** 题目创建/更新/导入负载；tags/explanation/metadata 缺省为 []/""/{} */
export interface QuestionInput {
  type: QuestionType;
  difficulty: number;
  tags?: string[];
  content: string;
  options?: string[];
  answer: string | string[];
  explanation?: string;
  metadata?: Record<string, unknown>;
  created_by?: string;
}

export type QuestionList = MeritPage<Question>;

/** 题目列表筛选：type/difficulty/tags（AND 语义）+ 分页 */
export type QuestionListParams = {
  type?: QuestionType;
  difficulty?: number;
  tags?: string[];
  limit?: number;
  offset?: number;
};

export function listQuestions(params: QuestionListParams = {}): Promise<QuestionList> {
  const query = new URLSearchParams();
  if (params.type) query.set("type", params.type);
  if (params.difficulty !== undefined) query.set("difficulty", String(params.difficulty));
  if (params.tags && params.tags.length > 0) query.set("tags", params.tags.join(","));
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return meritRequest<QuestionList>(`/questions${suffix}`);
}

export function createQuestion(input: QuestionInput): Promise<Question> {
  return meritRequest<Question>("/questions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function getQuestion(id: string): Promise<Question> {
  return meritRequest<Question>(`/questions/${encodeURIComponent(id)}`);
}

export function updateQuestion(id: string, input: QuestionInput): Promise<Question> {
  return meritRequest<Question>(`/questions/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export async function deleteQuestion(id: string): Promise<void> {
  await meritRequest<void>(`/questions/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** 批量导入响应：成功导入数量与完整记录 */
export interface QuestionImportResult {
  imported: number;
  records: Question[];
}

/** 批量导入题目：POST /questions/import，任一失败整批 400 并带逐条 details */
export function importQuestions(inputs: QuestionInput[]): Promise<QuestionImportResult> {
  return meritRequest<QuestionImportResult>("/questions/import", {
    method: "POST",
    body: JSON.stringify(inputs),
  });
}

// ========================================================================
//  试卷（papers）
// ========================================================================

/** 组卷策略：题型 -> 题目数量，至少一个题型为正数 */
export type GenerationStrategy = Partial<Record<QuestionType, number>>;

/** 试卷内嵌的题目快照（仅组卷写入，客户端只读） */
export interface PaperQuestion {
  id: string;
  type: QuestionType;
  difficulty: number;
  content: string;
  options: string[];
  answer: string | string[];
}

/** 试卷 */
export interface Paper {
  id: string;
  title: string;
  duration_minutes: number;
  pass_score: number;
  generation_strategy: GenerationStrategy;
  questions: PaperQuestion[];
  created_by: string;
  created_at: string;
  updated_at: string;
}

/** 试卷创建/更新负载；duration_minutes 必须为正数，pass_score 0-100 */
export interface PaperInput {
  title: string;
  duration_minutes: number;
  pass_score: number;
  generation_strategy: GenerationStrategy;
  created_by?: string;
}

export type PaperList = MeritPage<Paper>;

/** 试卷列表筛选：仅分页 */
export type PaperListParams = {
  limit?: number;
  offset?: number;
};

export function listPapers(params: PaperListParams = {}): Promise<PaperList> {
  return meritRequest<PaperList>(`/papers${meritQuery(params)}`);
}

export function createPaper(input: PaperInput): Promise<Paper> {
  return meritRequest<Paper>("/papers", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function getPaper(id: string): Promise<Paper> {
  return meritRequest<Paper>(`/papers/${encodeURIComponent(id)}`);
}

export function updatePaper(id: string, input: PaperInput): Promise<Paper> {
  return meritRequest<Paper>(`/papers/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export async function deletePaper(id: string): Promise<void> {
  await meritRequest<void>(`/papers/${encodeURIComponent(id)}`, { method: "DELETE" });
}

/** 自动组卷：按 generation_strategy 从题库抽取并写入 questions */
export function generatePaper(id: string): Promise<Paper> {
  return meritRequest<Paper>(`/papers/${encodeURIComponent(id)}/generate`, {
    method: "POST",
    body: JSON.stringify({}),
  });
}

// ========================================================================
//  培训任务指派（assignments）
// ========================================================================

/** 指派方式 */
export type AssignType = "手动指派" | "自动触发";

/** 指派对象类型 */
export type TargetType = "用户" | "岗位" | "部门";

/** 培训任务指派 */
export interface Assignment {
  id: string;
  course_id: string;
  assign_type: AssignType;
  /** 自动触发的触发规则（JSONB 扩展，缺省为 {}） */
  trigger_rule: Record<string, unknown>;
  /** RFC3339 时间戳，未设置时为空串 */
  deadline: string;
  target_type: TargetType;
  target_ids: string[];
  created_by: string;
  created_at: string;
  updated_at: string;
}

/** 培训任务指派创建负载 */
export interface AssignmentInput {
  course_id: string;
  assign_type: AssignType;
  trigger_rule?: Record<string, unknown>;
  deadline?: string;
  target_type: TargetType;
  target_ids: string[];
  created_by?: string;
}

export type AssignmentList = MeritPage<Assignment>;

/** 指派列表筛选：course_id/employee_id/target_type + 分页 */
export type AssignmentListParams = {
  course_id?: string;
  employee_id?: string;
  target_type?: TargetType;
  limit?: number;
  offset?: number;
};

export function createAssignment(input: AssignmentInput): Promise<Assignment> {
  return meritRequest<Assignment>("/assignments", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listAssignments(params: AssignmentListParams = {}): Promise<AssignmentList> {
  return meritRequest<AssignmentList>(`/assignments${meritQuery(params)}`);
}

export async function deleteAssignment(id: string): Promise<void> {
  await meritRequest<void>(`/assignments/${encodeURIComponent(id)}`, { method: "DELETE" });
}

// ========================================================================
//  学习进度（progress）
// ========================================================================

/** 进度状态（服务端派生，不接受输入） */
export type ProgressStatus = "学习中" | "已完成";

/** 单章学习进度行（PUT .../progress/chapters/{cid} 的返回） */
export interface ProgressRow {
  id: string;
  assignment_id: string;
  employee_id: string;
  chapter_id: string;
  progress_percent: number;
  status: ProgressStatus;
  detail: Record<string, unknown>;
  started_at: string | null;
  completed_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 进度上报负载：progress_percent 必填（0-100），detail 可选 JSON 对象 */
export interface ProgressReportInput {
  progress_percent: number;
  detail?: Record<string, unknown>;
}

/** 进度汇总中的单章行；未上报章节为零值（0/学习中/null） */
export interface ChapterProgress {
  chapter_id: string;
  chapter_title: string;
  progress_percent: number;
  status: ProgressStatus;
  started_at: string | null;
  completed_at: string | null;
  detail: Record<string, unknown>;
}

/** 一名员工在一个指派下的学习进度汇总（单对象响应，非列表） */
export interface ProgressSummary {
  assignment_id: string;
  employee_id: string;
  course_id: string;
  course_title: string;
  total_chapters: number;
  completed_chapters: number;
  status: ProgressStatus;
  chapters: ChapterProgress[];
}

export function getAssignmentProgress(assignmentId: string, employeeId: string): Promise<ProgressSummary> {
  return meritRequest<ProgressSummary>(
    `/assignments/${encodeURIComponent(assignmentId)}/employees/${encodeURIComponent(employeeId)}/progress`,
  );
}

/** 上报/更新单章学习进度（首次上报建行，之后原地更新） */
export function reportChapterProgress(
  assignmentId: string,
  employeeId: string,
  chapterId: string,
  input: ProgressReportInput,
): Promise<ProgressRow> {
  return meritRequest<ProgressRow>(
    `/assignments/${encodeURIComponent(assignmentId)}/employees/${encodeURIComponent(employeeId)}/progress/chapters/${encodeURIComponent(chapterId)}`,
    {
      method: "PUT",
      body: JSON.stringify(input),
    },
  );
}

/** 完成指派下全部章节学习并返回最新汇总 */
export function completeAssignmentProgress(assignmentId: string, employeeId: string): Promise<ProgressSummary> {
  return meritRequest<ProgressSummary>(
    `/assignments/${encodeURIComponent(assignmentId)}/employees/${encodeURIComponent(employeeId)}/complete`,
    {
      method: "POST",
      body: JSON.stringify({}),
    },
  );
}

// ========================================================================
//  在线考核记录（exam-records）
// ========================================================================

/** 考核快照中的题目（开考时对试卷的只读投影） */
export interface ExamRecordQuestion {
  id: string;
  type: QuestionType;
  difficulty: number;
  content: string;
  options: string[];
  answer: string | string[];
}

/** 开考快照：交卷判分只依赖快照 */
export interface ExamSnapshot {
  paper_id: string;
  pass_score: number;
  questions: ExamRecordQuestion[];
}

/** 在线考核记录 */
export interface ExamRecord {
  id: string;
  employee_id: string;
  paper_id: string;
  start_time: string;
  /** 交卷前为 null */
  end_time: string | null;
  score: number | null;
  passed: boolean | null;
  answers_snapshot: ExamSnapshot;
  metadata: Record<string, unknown>;
  created_by: string;
  created_at: string;
  updated_at: string;
}

/** 开考负载：employee_id/paper_id 必填（26 位 ULID） */
export interface ExamStartInput {
  employee_id: string;
  paper_id: string;
  metadata?: Record<string, unknown>;
  created_by?: string;
}

/** 交卷答案：question_id -> 答案值（单选/判断/填空为字符串，多选为字符串数组） */
export type ExamAnswers = Record<string, string | string[]>;

export type ExamRecordList = MeritPage<ExamRecord>;

/** 考核记录列表筛选：employee_id/paper_id（26 位 ULID）+ 分页 */
export type ExamRecordListParams = {
  employee_id?: string;
  paper_id?: string;
  limit?: number;
  offset?: number;
};

/** 开考：服务端生成 id/start_time 并快照试卷 */
export function startExam(input: ExamStartInput): Promise<ExamRecord> {
  return meritRequest<ExamRecord>("/exam-records", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 交卷：按快照判分并写 end_time/score/passed；重复交卷 400 */
export function submitExam(id: string, answers: ExamAnswers): Promise<ExamRecord> {
  return meritRequest<ExamRecord>(`/exam-records/${encodeURIComponent(id)}/submit`, {
    method: "POST",
    body: JSON.stringify({ answers }),
  });
}

export function getExamRecord(id: string): Promise<ExamRecord> {
  return meritRequest<ExamRecord>(`/exam-records/${encodeURIComponent(id)}`);
}

export function listExamRecords(params: ExamRecordListParams = {}): Promise<ExamRecordList> {
  return meritRequest<ExamRecordList>(`/exam-records${meritQuery(params)}`);
}
