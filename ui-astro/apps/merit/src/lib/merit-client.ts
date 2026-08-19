/**
 * 理论培训（merit）客户端绑定模块。
 *
 * 对 @pitchfork/shared/merit 做具名 import 并 re-export，覆盖全部 7 组
 * 资源方法（courses/chapters/questions/papers/assignments/progress/
 * exam-records）与全部类型；由 `pnpm --filter @pitchfork/merit build` 的
 * astro check 逐符号解析验证 ./merit 导出可解析。merit 各页面卡只允许
 * 从本模块（或其上级页面模块）访问后端 API。
 */
export {
  // 共享请求封装
  ApiRequestError,
  // 通用
  type MeritPage,
  // 课程（courses）
  type CourseTopic,
  type CourseDeliveryType,
  type CourseStatus,
  type Course,
  type CourseInput,
  type CourseList,
  type CourseListParams,
  // 课程章节（chapters）
  type ContentBlockType,
  type ContentBlock,
  type Chapter,
  type ChapterInput,
  type ChapterList,
  type ChapterListParams,
  // 题库（questions）
  type QuestionType,
  type JudgmentAnswer,
  type Question,
  type QuestionInput,
  type QuestionList,
  type QuestionListParams,
  type QuestionImportResult,
  // 试卷（papers）
  type GenerationStrategy,
  type PaperQuestion,
  type Paper,
  type PaperInput,
  type PaperList,
  type PaperListParams,
  // 培训任务指派（assignments）
  type AssignType,
  type TargetType,
  type Assignment,
  type AssignmentInput,
  type AssignmentList,
  type AssignmentListParams,
  // 学习进度（progress）
  type ProgressStatus,
  type ProgressRow,
  type ProgressReportInput,
  type ChapterProgress,
  type ProgressSummary,
  // 在线考核记录（exam-records）
  type ExamRecordQuestion,
  type ExamSnapshot,
  type ExamRecord,
  type ExamStartInput,
  type ExamAnswers,
  type ExamRecordList,
  type ExamRecordListParams,
  // 方法
  listCourses,
  createCourse,
  getCourse,
  updateCourse,
  deleteCourse,
  listCourseChapters,
  createChapter,
  getChapter,
  updateChapter,
  deleteChapter,
  listQuestions,
  createQuestion,
  getQuestion,
  updateQuestion,
  deleteQuestion,
  importQuestions,
  listPapers,
  createPaper,
  getPaper,
  updatePaper,
  deletePaper,
  generatePaper,
  createAssignment,
  listAssignments,
  deleteAssignment,
  getAssignmentProgress,
  reportChapterProgress,
  completeAssignmentProgress,
  startExam,
  submitExam,
  getExamRecord,
  listExamRecords,
} from "@pitchfork/shared/merit";
