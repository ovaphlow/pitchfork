import { serviceBase } from "./aceso-service-config";
import { looksLikeJsonBody, problemMessage, type ApiProblem } from "./problem-message";

type ProblemDetails = ApiProblem;

/** 带 HTTP 状态码的 API 错误；调用方可据此区分 404 等业务状态 */
export class ApiRequestError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
  }
}

/**
 * 幂等键生成（采购订单创建/收货、护理站申领创建都用它）。
 *
 * `crypto.randomUUID` 只在安全上下文（HTTPS 或 localhost）可用：内网以
 * `http://<ip>:4324` 直接访问 Aceso 时它不存在，直接调用会让「新建采购订单」
 * 「新建申领」按钮抛 `Uncaught TypeError: crypto.randomUUID is not a function`，
 * 弹窗打不开。这里依次降级：randomUUID → getRandomValues 自行拼 UUID v4 →
 * 时间无关的随机串（幂等键不用于安全用途）。
 */
export function newIdempotencyKey(): string {
  const cryptoApi = globalThis.crypto as Crypto | undefined;
  if (typeof cryptoApi?.randomUUID === "function") return cryptoApi.randomUUID();

  const bytes = new Uint8Array(16);
  if (typeof cryptoApi?.getRandomValues === "function") {
    cryptoApi.getRandomValues(bytes);
  } else {
    for (let index = 0; index < bytes.length; index += 1) {
      bytes[index] = Math.floor(Math.random() * 256);
    }
  }
  bytes[6] = ((bytes[6] ?? 0) & 0x0f) | 0x40; // version 4
  bytes[8] = ((bytes[8] ?? 0) & 0x3f) | 0x80; // RFC 4122 variant
  const hex = Array.from(bytes, (byte) => byte.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

interface RequestOptions {
  csrf?: boolean;
  redirectOnUnauthorized?: boolean;
  service?: "aceso" | "identity" | "nexus";
}

export interface IdentitySession {
  subject_id: string;
  access: "完整" | "仅改密";
  /**
   * 042：是否平台管理员 —— 代操作（把业务操作人指定为他人）的唯一门禁。
   * 字段缺失（旧版 IDP）一律按 `false` 处理：判定不出管理员就不得代操作。
   */
  platform_admin?: boolean;
  /** 042：当前登录主体的姓名，用于「操作人：<姓名>（当前登录用户）」；缺失时回退展示 subject_id。 */
  display_name?: string;
}

export interface IdentitySubject {
  id: string;
  status: "启用" | "禁用";
  security_version: number;
  display_name: string;
  identifier: string;
  platform_admin: boolean;
  created_at: string;
  updated_at: string;
}

export interface IdentitySubjectList {
  records: IdentitySubject[];
  meta: { total: number };
}

/**
 * 组织部门（Nexus `/departments`）。
 *
 * 与床位/入住里的 `department`（照护单元/病区，自由文本）不是同一概念，互不联动。
 */
export interface Department {
  id: string;
  code: string;
  name: string;
  description: string;
  parent_code: string;
  sort_order: number;
  member_count: number;
  created_at: string;
  updated_at: string;
}

export interface DepartmentInput {
  code: string;
  name: string;
  description?: string;
  parent_code?: string;
  sort_order?: number;
}

export interface DepartmentList {
  records: Department[];
  meta: { total: number };
}

export interface WarehousePayload {
  name: string;
  description?: string;
}

export interface Warehouse {
  id: string;
  category: string;
  code: string;
  root_code: string;
  parent_code: string;
  payload: WarehousePayload;
  created_at: string;
  updated_at: string;
}

export interface WarehouseInput {
  code: string;
  name: string;
  description?: string;
}

/** 仓库下拉选项：code 为发药/库存使用的仓库编码，name 为展示名称 */
export interface WarehouseOption {
  code: string;
  name: string;
}

export interface VaccinationRecord {
  vaccine?: string;
  dose?: string;
  date?: string;
  facility?: string;
}

/** 儿保专属档案（person_type=儿童 时 1:1 内嵌）；非儿童为 null */
export interface ChildHealthProfile {
  id?: string;
  guardian_name?: string | null;
  guardian_relationship?: string | null;
  guardian_phone?: string | null;
  birth_weight_g?: number | null;
  birth_height_mm?: number | null;
  delivery_mode?: string | null;
  feeding_method?: string | null;
  vaccination_summary?: string | null;
  vaccination_records?: VaccinationRecord[] | null;
  remark?: string | null;
}

/** 建档/更新儿保档案的入参（与 ChildHealthProfile 同字段，均可选、无 id） */
export interface ChildHealthProfileInput {
  guardian_name?: string | null;
  guardian_relationship?: string | null;
  guardian_phone?: string | null;
  birth_weight_g?: number | null;
  birth_height_mm?: number | null;
  delivery_mode?: string | null;
  feeding_method?: string | null;
  vaccination_summary?: string | null;
  vaccination_records?: VaccinationRecord[] | null;
  remark?: string | null;
}

export interface Patient {
  id: string;
  name: string;
  gender: string;
  birth_date: string | null;
  id_card_no: string | null;
  phone: string | null;
  address: string | null;
  emergency_contact: Record<string, string> | null;
  medical_insurance: string | null;
  allergies: string[] | null;
  past_history: string | null;
  metadata: Record<string, unknown> | null;
  status: string;
  /** 患者域：居民 / 长者 / 儿童（缺省 居民） */
  person_type: string;
  /** 儿保档案；非儿童或无档案为 null */
  child_profile: ChildHealthProfile | null;
  created_at: string;
  updated_at: string;
}

export interface PatientList {
  records: Patient[];
  meta: { total: number };
}

export interface PatientInput {
  name: string;
  gender?: string | null;
  birth_date?: string;
  id_card_no?: string | null;
  phone?: string | null;
  address?: string | null;
  emergency_contact?: Record<string, string> | null;
  medical_insurance?: string | null;
  allergies?: string[] | null;
  past_history?: string | null;
  metadata?: Record<string, unknown>;
  /** 患者域：居民 / 长者 / 儿童（缺省 居民） */
  person_type?: string;
  /** 儿保档案；仅 person_type=儿童 时可持久化，null 表示清空 */
  child_profile?: ChildHealthProfileInput | null;
}

export interface Encounter {
  id: string;
  patient_id: string;
  encounter_type: string;
  encounter_no: string;
  department: string | null;
  ward: string | null;
  admit_date: string | null;
  discharge_date: string | null;
  death_date: string | null;
  death_cause: string | null;
  admitting_diagnosis: string | null;
  discharge_diagnosis: string | null;
  attending_physician: string | null;
  status: string;
  /** 结算关账冻结标记（养老收费）；未关账为 null */
  settled_at: string | null;
  /** 离院操作人（认证主体 id）；历史数据与未认证环境为 null */
  discharged_by: string | null;
  /** 离院确认时刻（服务端写入，区别于可回填的 discharge_date） */
  discharged_at: string | null;
  /** 去世操作人（认证主体 id）；历史数据与未认证环境为 null */
  deceased_by: string | null;
  /** 去世确认时刻（服务端写入，区别于可回填的 death_date） */
  deceased_at: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface EncounterList {
  records: Encounter[];
  meta: { total: number };
}

export interface EncounterInput {
  patient_id: string;
  encounter_type: string;
  encounter_no: string;
  admit_date: string;
  department?: string | null;
  ward?: string | null;
  admitting_diagnosis?: string | null;
  attending_physician?: string | null;
  metadata?: Record<string, unknown>;
}

export interface ElderlyAdmissionInput {
  patient_id: string;
  encounter_no: string;
  admit_date: string;
  department?: string | null;
  ward?: string | null;
  admitting_diagnosis?: string | null;
  attending_physician?: string | null;
  metadata?: Record<string, unknown>;
}

export interface ElderlyAdmission {
  patient: Patient;
  encounter: Encounter;
  /** 同事务创建的养老照护周期（ELDERLY_CARE） */
  nursing_period: NursingServicePeriod;
}

export interface NursingServicePeriod {
  id: string;
  patient_id: string;
  service_type: string;
  start_date: string;
  end_date: string | null;
  coordinator: string | null;
  /** 养老入住周期精确关联的入住记录 ID（ELDERLY_CARE 必有，其它类型为空） */
  encounter_id: string | null;
  status: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface NursingServicePeriodInput {
  patient_id: string;
  service_type: string;
  start_date: string;
  end_date?: string | null;
  coordinator?: string | null;
  metadata?: Record<string, unknown>;
}

export interface NursingAssessment {
  id: string;
  encounter_id: string | null;
  period_id: string | null;
  assess_type: string;
  assess_date: string;
  assessor: string | null;
  total_score: number | null;
  result_level: string | null;
  detail: Record<string, unknown> | null;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface NursingAssessmentInput {
  encounter_id?: string;
  period_id?: string;
  assess_type: string;
  assess_date: string;
  assessor?: string;
  total_score?: number;
  result_level?: string;
  detail?: Record<string, unknown>;
  remark?: string;
  metadata?: Record<string, unknown>;
}

export interface NursingPlanItem {
  id: string;
  plan_id: string;
  action: string;
  frequency_code: string | null;
  frequency_name: string | null;
  duration_days: number | null;
  remark: string | null;
  status: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface NursingPlanItemInput {
  action: string;
  frequency_code?: string;
  frequency_name?: string;
  duration_days?: number;
  remark?: string;
  metadata?: Record<string, unknown>;
}

export interface NursingPlan {
  id: string;
  period_id: string;
  encounter_id: string | null;
  plan_name: string;
  goals: string | null;
  status: string;
  created_by: string | null;
  start_date: string | null;
  end_date: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
  items?: NursingPlanItem[];
}

export interface NursingPlanInput {
  period_id: string;
  encounter_id?: string;
  plan_name: string;
  goals?: string;
  created_by?: string;
  start_date?: string;
  end_date?: string;
  items?: NursingPlanItemInput[];
  metadata?: Record<string, unknown>;
}

export interface NursingTask {
  id: string;
  period_id: string | null;
  encounter_id: string | null;
  plan_item_id: string | null;
  order_item_id: string | null;
  task_type: string;
  description: string;
  frequency_code: string | null;
  frequency_name: string | null;
  start_date: string | null;
  end_date: string | null;
  status: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
  /** 绑定医嘱的类型与结构化明细（未绑定医嘱时为空；前端据此显示药品 / 项目） */
  order_details?: Record<string, unknown> | null;
  order_type?: string | null;
}

export interface NursingTaskInput {
  period_id?: string;
  encounter_id?: string;
  plan_item_id?: string;
  task_type: string;
  description: string;
  frequency_code?: string;
  frequency_name?: string;
  start_date?: string;
  end_date?: string;
  metadata?: Record<string, unknown>;
}

export interface NursingTaskExecution {
  id: string;
  task_id: string;
  planned_time: string | null;
  actual_time: string | null;
  executor: string | null;
  status: string;
  stock_operation_detail_id: string | null;
  quantity: DecimalText | null;
  note: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  consumption_summary?: NursingConsumptionSummary;
  consumptions?: NursingExecutionConsumption[];
}

export interface NursingTaskExecutionInput {
  task_id: string;
  planned_time?: string;
  actual_time?: string;
  executor?: string;
  note?: string;
  quantity?: DecimalText;
  stock_operation_detail_id?: string;
  metadata?: Record<string, unknown>;
}

/** 今日执行记录 — 包含任务摘要和长者姓名 */
export interface NursingTodayExecution {
  id: string;
  task_id: string;
  planned_time: string | null;
  actual_time: string | null;
  executor: string | null;
  status: string;
  stock_operation_detail_id: string | null;
  quantity: DecimalText | null;
  note: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  /** 任务摘要 */
  task_description: string | null;
  task_type: string | null;
  task_frequency_name: string | null;
  task_period_id: string | null;
  /** 绑定医嘱的类型与结构化明细（未绑定医嘱时为空；今日看板显示药品 / 项目） */
  order_details?: Record<string, unknown> | null;
  order_type?: string | null;
  /** 长者摘要 */
  patient_id: string | null;
  patient_name: string | null;
  consumption_summary?: NursingConsumptionSummary;
  /** 逾期派生字段 */
  is_overdue: boolean;
  overdue_minutes: number | null;
  /** 长挂执行派生字段：status=IN_PROGRESS 且 actual_time 非空时的已进行分钟数，否则 null */
  in_progress_minutes: number | null;
  /** 长挂执行提醒：in_progress_minutes >= 1440（24 小时） */
  is_stale: boolean;
}

interface NursingPage<T> {
  records: T[];
  meta: {
    total: number;
    overdue_total?: number;
    /** 跨日全量逾期总数（忽略日期窗口，保留周期/执行人/任务类型与参与条件） */
    overdue_total_all?: number;
    /** 各状态计数（忽略 status 与 overdue 筛选；五个键恒在） */
    status_totals?: Record<string, number>;
  };
}

function cookieValue(name: string): string | undefined {
  if (typeof document === "undefined") return undefined;
  const prefix = `${name}=`;
  const cookie = document.cookie
    .split(";")
    .map((part) => part.trim())
    .find((part) => part.startsWith(prefix));
  return cookie ? decodeURIComponent(cookie.slice(prefix.length)) : undefined;
}

function redirectToLogin(): void {
  if (typeof window !== "undefined" && !window.location.pathname.startsWith("/login")) {
    window.location.assign("/login");
  }
}

async function readResponse<T>(response: Response, redirectOnUnauthorized: boolean): Promise<T> {
  const contentType = response.headers.get("content-type") ?? "";
  const responseText = await response.text();
  const body: ProblemDetails | T | null = looksLikeJsonBody(contentType, responseText)
    ? (() => {
        try {
          return JSON.parse(responseText || "null") as ProblemDetails | T | null;
        } catch {
          return null;
        }
      })()
    : null;

  if (!response.ok) {
    if (response.status === 401 && redirectOnUnauthorized) {
      redirectToLogin();
    }
    const problem = body as ProblemDetails | null;
    throw new ApiRequestError(response.status, problemMessage(response.status, problem, responseText));
  }
  return body as T;
}

/**
 * 把服务端错误翻成用户能照做的提示。
 *
 * 权限闸门（037/038）在无权限时回 403 + `required_permission`，上游故障回 503；
 * 这两种若原样透出英文错误码，用户只会看到 "forbidden"，不知道该找谁要哪个权限。
 */
async function request<T>(
  path: string,
  init: RequestInit = {},
  { csrf = false, redirectOnUnauthorized = true, service = "aceso" }: RequestOptions = {},
): Promise<T> {
  const url = `${serviceBase(service)}${path}`;
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (csrf) {
    const token = cookieValue("identityd_csrf");
    if (!token) throw new Error("会话校验已失效，请重新登录");
    headers.set("X-CSRF-Token", token);
  }

  let response: Response;
  try {
    response = await fetch(url, {
      ...init,
      headers,
      credentials: "include",
    });
  } catch {
    throw new Error("无法连接到服务，请检查网络或服务状态");
  }
  return readResponse<T>(response, redirectOnUnauthorized);
}

export function getCurrentSession(redirectOnUnauthorized = true): Promise<IdentitySession> {
  return request<IdentitySession>("/session", {}, { redirectOnUnauthorized, service: "identity" });
}

export async function login(identifier: string, password: string): Promise<IdentitySession> {
  const body = new URLSearchParams({ identifier, password });
  const url = `${serviceBase("identity")}/sessions`;
  let response: Response;
  try {
    response = await fetch(url, {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/x-www-form-urlencoded",
      },
      body,
      credentials: "include",
    });
  } catch {
    throw new Error("无法连接到认证服务，请稍后重试");
  }

  if (!response.ok) {
    await readResponse(response, false);
  }

  const loginResult = await readResponse<{ access: IdentitySession["access"] }>(response, false);
  if (loginResult.access === "仅改密") {
    throw new Error("该账号需要先完成密码修改");
  }
  return getCurrentSession(false).catch(() => {
    throw new Error("账号或密码不正确");
  });
}

export async function logout(): Promise<void> {
  await request<void>("/sessions/current", { method: "DELETE" }, { csrf: true, redirectOnUnauthorized: false, service: "identity" });
}

export function listIdentitySubjects(page: number, pageSize: number): Promise<IdentitySubjectList> {
  const params = new URLSearchParams({ limit: String(pageSize), offset: String((page - 1) * pageSize) });
  return request<IdentitySubjectList>(`/subjects?${params}`, {}, { service: "identity" });
}

/**
 * 044：业务人员目录条目——**只暴露主体 ID + 姓名**。
 *
 * 不含登录账号、状态、角色/权限等管理字段：本端点对**所有登录用户**开放，
 * 暴露面刻意控制在「组织内通讯录」级别。
 */
export interface IdentityDirectoryEntry {
  id: string;
  display_name: string;
}

export interface IdentityDirectoryList {
  records: IdentityDirectoryEntry[];
  meta: { total: number };
}

/**
 * 业务人员目录（044）：**非管理员可用**。
 *
 * 用途只有两个：把主体 ID 解析成姓名（护士核对列、病程记录、配餐名单、交班人…），
 * 以及填充按执行人/责任人筛选的下拉。管理台的人员管理请继续用 [listIdentitySubjects]
 * （那是管理员接口，业务账号调用会 403）。
 */
export function listIdentityDirectory(
  params: { search?: string; limit?: number; offset?: number } = {},
): Promise<IdentityDirectoryList> {
  const query = new URLSearchParams();
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<IdentityDirectoryList>(`/directory${suffix}`, {}, { service: "identity" });
}

export function createIdentitySubject(input: {
  display_name: string;
  identifier: string;
  password: string;
}): Promise<IdentitySubject> {
  return request<IdentitySubject>(
    "/subjects",
    { method: "POST", body: JSON.stringify(input) },
    { csrf: true, service: "identity" },
  );
}

export function disableIdentitySubject(id: string): Promise<IdentitySubject> {
  return request<IdentitySubject>(
    `/subjects/${encodeURIComponent(id)}`,
    { method: "PATCH", body: JSON.stringify({ status: "禁用" }) },
    { csrf: true, service: "identity" },
  );
}

export function setIdentityTemporaryPassword(id: string, temporaryPassword: string): Promise<IdentitySubject> {
  return request<IdentitySubject>(
    `/subjects/${encodeURIComponent(id)}`,
    { method: "PATCH", body: JSON.stringify({ temporary_password: temporaryPassword }) },
    { csrf: true, service: "identity" },
  );
}

// ========================================================================
//  Nexus API — Departments (组织部门目录)
// ========================================================================

function departmentPayload(input: DepartmentInput): Record<string, unknown> {
  return {
    code: input.code.trim(),
    name: input.name.trim(),
    description: input.description?.trim() ?? "",
    parent_code: input.parent_code?.trim() ?? "",
    sort_order: input.sort_order ?? 0,
  };
}

export function listDepartments(): Promise<DepartmentList> {
  const params = new URLSearchParams({ page: "1", page_size: "100" });
  return request<DepartmentList>(`/departments?${params}`, {}, { service: "nexus" });
}

export function createDepartment(input: DepartmentInput): Promise<Department> {
  return request<Department>("/departments", {
    method: "POST",
    body: JSON.stringify(departmentPayload(input)),
  }, { service: "nexus" });
}

export function updateDepartment(id: string, input: DepartmentInput): Promise<Department> {
  return request<Department>(`/departments/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(departmentPayload(input)),
  }, { service: "nexus" });
}

export async function deleteDepartment(id: string): Promise<void> {
  await request<void>(`/departments/${encodeURIComponent(id)}`, { method: "DELETE" }, { service: "nexus" });
}

const WAREHOUSE_CATEGORY = "warehouse";

export function listWarehouses(): Promise<Warehouse[]> {
  const params = new URLSearchParams({ category: WAREHOUSE_CATEGORY, page: "1", page_size: "100" });
  return request<Warehouse[]>(`/settings?${params}`, {}, { service: "nexus" });
}

function warehouseSettingPayload(input: WarehouseInput): Record<string, unknown> {
  return {
    category: WAREHOUSE_CATEGORY,
    code: input.code.trim(),
    parent_code: "",
    root_code: "",
    payload: {
      name: input.name.trim(),
      ...(input.description?.trim() ? { description: input.description.trim() } : {}),
    },
  };
}

export function createWarehouse(input: WarehouseInput): Promise<Warehouse> {
  return request<Warehouse>("/settings", {
    method: "POST",
    body: JSON.stringify(warehouseSettingPayload(input)),
  }, { service: "nexus" });
}

export function updateWarehouse(id: string, input: WarehouseInput): Promise<Warehouse> {
  return request<Warehouse>(`/settings/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(warehouseSettingPayload(input)),
  }, { service: "nexus" });
}

export async function deleteWarehouse(id: string): Promise<void> {
  await request<void>(`/settings/${encodeURIComponent(id)}`, { method: "DELETE" }, { service: "nexus" });
}

// ========================================================================
//  Nexus API — Role Directory (角色目录)
// ========================================================================

export interface NexusRole {
  id: string;
  role_code: string;
  display_name: string;
  description: string;
  permission_codes: string[];
  created_at: string;
  updated_at: string;
}

export interface NexusRoleInput {
  role_code: string;
  display_name: string;
  description?: string;
  permission_codes?: string[];
}

/** 角色列表：按 role_code 升序，默认取第一页 20 条 */
export function listRoles(params: { page?: number; page_size?: number } = {}): Promise<NexusRole[]> {
  const query = new URLSearchParams();
  if (params.page !== undefined) query.set("page", String(params.page));
  if (params.page_size !== undefined) query.set("page_size", String(params.page_size));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<NexusRole[]>(`/roles${suffix}`, {}, { service: "nexus" });
}

export function createRole(input: NexusRoleInput): Promise<NexusRole> {
  return request<NexusRole>("/roles", {
    method: "POST",
    body: JSON.stringify(input),
  }, { service: "nexus" });
}

export function updateRole(id: string, input: NexusRoleInput): Promise<NexusRole> {
  return request<NexusRole>(`/roles/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  }, { service: "nexus" });
}

export async function deleteRole(id: string): Promise<void> {
  await request<void>(`/roles/${encodeURIComponent(id)}`, { method: "DELETE" }, { service: "nexus" });
}

// ========================================================================
//  Nexus API — Subject Role Assignments (用户 ↔ 角色分配)
// ========================================================================

export interface SubjectRoleAssignment {
  id: string;
  subject_id: string;
  role_id: string;
  role_code: string;
  role_display_name: string;
  granted_by_subject_id: string;
  created_at: string;
  updated_at: string;
}

/** 指定主体的角色分配；subjectIds 为空数组时返回全部分配（不传参数） */
export function listSubjectRoles(subjectIds: string[] = []): Promise<SubjectRoleAssignment[]> {
  const params = new URLSearchParams();
  const ids = subjectIds.map((id) => id.trim()).filter((id) => id.length > 0);
  if (ids.length > 0) params.set("subject_ids", ids.join(","));
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return request<SubjectRoleAssignment[]>(`/subject-roles${suffix}`, {}, { service: "nexus" });
}

/** 全量替换某个主体的角色集合；空数组表示清空全部角色 */
export function replaceSubjectRoles(subjectId: string, roleCodes: string[]): Promise<SubjectRoleAssignment[]> {
  return request<SubjectRoleAssignment[]>(
    `/subject-roles/subjects/${encodeURIComponent(subjectId)}`,
    { method: "PUT", body: JSON.stringify({ role_codes: roleCodes }) },
    { service: "nexus" },
  );
}

// ========================================================================
//  Nexus API — Subject Department (用户 ↔ 部门，一人一主部门)
// ========================================================================

export interface SubjectDepartmentAssignment {
  id: string;
  subject_id: string;
  department_id: string;
  department_code: string;
  department_name: string;
  granted_by_subject_id: string;
  created_at: string;
  updated_at: string;
}

export interface SubjectDepartmentList {
  records: SubjectDepartmentAssignment[];
  meta: { total: number };
}

/** 指定主体的部门归属；subjectIds 为空数组时返回全部归属 */
export function listSubjectDepartments(subjectIds: string[] = []): Promise<SubjectDepartmentList> {
  const params = new URLSearchParams();
  const ids = subjectIds.map((id) => id.trim()).filter((id) => id.length > 0);
  if (ids.length > 0) params.set("subject_ids", ids.join(","));
  const suffix = params.toString() ? `?${params.toString()}` : "";
  return request<SubjectDepartmentList>(`/subject-departments${suffix}`, {}, { service: "nexus" });
}

/** 全量替换某个主体的部门；传 null 表示清空归属 */
export function replaceSubjectDepartment(
  subjectId: string,
  departmentId: string | null,
): Promise<SubjectDepartmentAssignment[]> {
  return request<SubjectDepartmentAssignment[]>(
    `/subject-departments/subjects/${encodeURIComponent(subjectId)}`,
    { method: "PUT", body: JSON.stringify({ department_id: departmentId }) },
    { service: "nexus" },
  );
}

export interface SubjectPermissions {
  subject_id: string;
  role_codes: string[];
  permission_codes: string[];
  source: string;
}

/** 该主体当前生效的权限码（角色权限码的并集），用于配置后自查 */
export function getSubjectPermissions(subjectId: string): Promise<SubjectPermissions> {
  return request<SubjectPermissions>(
    `/subject-permissions?subject_id=${encodeURIComponent(subjectId)}`,
    {},
    { service: "nexus" },
  );
}

export interface DeclaredPermission {
  method: string;
  path: string;
  permission_code: string;
}

/** 040：产品声明的权限码目录条目；`wired=false` 表示还没有接口在判定它。 */
export interface PermissionCatalogEntry {
  code: string;
  description: string;
  wired: boolean;
  routes: { method: string; path: string }[];
}

export interface DeclaredPermissions {
  permission_codes: string[];
  catalog: PermissionCatalogEntry[];
  routes: DeclaredPermission[];
}

/** Aceso 权限自省：已接线的权限码 + 产品权限目录（含未接线的码） */
export function listDeclaredPermissions(): Promise<DeclaredPermissions> {
  return request<DeclaredPermissions>("/access/v1/permissions", {}, { service: "aceso" });
}

/** 发药单等业务页面使用的仓库下拉选项；无配置时返回空数组 */
export async function listWarehouseOptions(): Promise<WarehouseOption[]> {
  const warehouses = await listWarehouses();
  return warehouses.map((warehouse) => ({ code: warehouse.code, name: warehouse.payload.name }));
}

export function listPatients(params: {
  name?: string;
  status?: string;
  person_type?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PatientList> {
  const query = new URLSearchParams();
  if (params.name?.trim()) query.set("name", params.name.trim());
  if (params.status) query.set("status", params.status);
  if (params.person_type) query.set("person_type", params.person_type);
  if (params.limit) query.set("limit", String(params.limit));
  if (params.offset) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<PatientList>(`/healthcare/v1/patients${suffix}`);
}

export function createPatient(input: PatientInput): Promise<Patient> {
  return request<Patient>("/healthcare/v1/patients", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updatePatient(id: string, input: PatientInput): Promise<Patient> {
  return request<Patient>(`/healthcare/v1/patients/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function listEncounters(params: {
  patient_id?: string;
  encounter_type?: string;
  status?: string;
  search?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<EncounterList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.encounter_type?.trim()) query.set("encounter_type", params.encounter_type.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<EncounterList>(`/healthcare/v1/encounters${suffix}`);
}

export function listElderlyAdmissions(params: { patient_id?: string; status?: string; search?: string; limit?: number; offset?: number } = {}): Promise<EncounterList> {
  const query = new URLSearchParams();
  // patient_id 用于按长者取入住历史（健康监测页选择「入住」）；服务端已支持该过滤
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  // status 显式传入空串时取全部入住（含已离院/已去世），用于医生诊疗页只读历史
  if (params.status !== undefined) query.set("status", params.status.trim());
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<EncounterList>(`/healthcare/v1/elderly-admissions${suffix}`);
}

export function listActiveElderlyAdmissions(params: { search?: string; limit?: number; offset?: number } = {}): Promise<EncounterList> {
  return listElderlyAdmissions({ status: "ACTIVE", ...params });
}

// ─── 床位占用只读查询（GET /bed-occupancy） ─────────────────────────────────
//
// 口径（计划 029 §4.1）：只读当前占用中的养老（ELDERLY_CARE）入住，即
// `discharge_date IS NULL OR discharge_date > now()`，按 department/ward/admit_date 升序。
// 占用事实来源仍是 `encounters.department`/`ward`（计划 029 D1）；计划 030 新增的 `beds`
// 只是**候选主数据**（床位管理页维护），不强制引用完整性，调用方按 trim 后精确比较。

export interface BedOccupancyRecord {
  /** 入住（encounter）ID */
  encounter_id: string;
  encounter_no: string | null;
  patient_id: string;
  patient_name: string | null;
  /** 照护单元/病区（自由文本，比较前需 trim） */
  department: string | null;
  /** 房间床位（自由文本，比较前需 trim） */
  ward: string | null;
  admit_date: string | null;
  discharge_date: string | null;
  status: string;
}

export interface BedOccupancyList {
  records: BedOccupancyRecord[];
  meta: { total: number };
}

/** 当前床位占用（department/ward 为 trim 后精确过滤；limit 服务端默认 50、上限 100） */
export function listBedOccupancy(params: { department?: string; ward?: string; limit?: number; offset?: number } = {}): Promise<BedOccupancyList> {
  const query = new URLSearchParams();
  if (params.department?.trim()) query.set("department", params.department.trim());
  if (params.ward?.trim()) query.set("ward", params.ward.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<BedOccupancyList>(`/healthcare/v1/bed-occupancy${suffix}`);
}

// ─── 床位主数据 (Bed Master Data, 计划 030 §4.5) ──────────────────────

export type BedStatus = "启用" | "停用";

/** 床位主数据条目：占用事实仍来自入住记录，本表只提供候选 `(department, ward)` */
export interface Bed {
  id: string;
  /** 照护单元/病区 */
  department: string;
  /** 房间床位 */
  ward: string;
  label: string | null;
  status: BedStatus;
  remark: string | null;
  created_at: string | null;
  updated_at: string | null;
}

export interface BedList {
  records: Bed[];
  meta: { total: number };
}

export interface BedInput {
  department: string;
  ward: string;
  label?: string;
  remark?: string;
}

/** 床位主数据列表：department/ward/status 过滤，created_at 倒序分页 */
export function listBeds(params: {
  department?: string;
  ward?: string;
  status?: BedStatus;
  limit?: number;
  offset?: number;
} = {}): Promise<BedList> {
  const query = new URLSearchParams();
  if (params.department?.trim()) query.set("department", params.department.trim());
  if (params.ward?.trim()) query.set("ward", params.ward.trim());
  if (params.status) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<BedList>(`/healthcare/v1/beds${suffix}`);
}

export function getBed(id: string): Promise<Bed> {
  return request<Bed>(`/healthcare/v1/beds/${encodeURIComponent(id)}`);
}

/** 新增床位：department/ward 必填（trim 后非空、≤50 字），启用态下 (department, ward) 唯一 */
export function createBed(input: BedInput): Promise<Bed> {
  return request<Bed>("/healthcare/v1/beds", { method: "POST", body: JSON.stringify(input) });
}

/** 全量更新床位字段；状态只能走 updateBedStatus */
export function updateBed(id: string, input: BedInput): Promise<Bed> {
  return request<Bed>(`/healthcare/v1/beds/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** 启用/停用流转：非法状态值 400 */
export function updateBedStatus(id: string, status: BedStatus): Promise<Bed> {
  return request<Bed>(`/healthcare/v1/beds/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

/** 删除床位：不存在 404；仍被在住入住占用时 409 */
export function deleteBed(id: string): Promise<{ id: string }> {
  return request<{ id: string }>(`/healthcare/v1/beds/${encodeURIComponent(id)}`, { method: "DELETE" });
}

// ─── 养老离院交接摘要归档 (DISCHARGE_SUMMARY) ──────────────────────────────

export interface ElderlyDischargeHandoverPatient {
  id: string;
  name: string;
  gender: string | null;
  birth_date: string | null;
  emergency_contact: Record<string, string> | null;
  allergies: Record<string, unknown>[] | null;
  past_history: string | null;
}

export interface ElderlyDischargeHandoverEncounter {
  id: string;
  encounter_no: string | null;
  department: string | null;
  ward: string | null;
  admit_date: string | null;
  discharge_date: string | null;
  death_date: string | null;
  death_cause: string | null;
  admitting_diagnosis: string | null;
  discharge_diagnosis: string | null;
  attending_physician: string | null;
  status: string | null;
}

export interface ElderlyDischargeHandoverCarePeriod {
  id: string;
  service_type: string | null;
  start_date: string | null;
  end_date: string | null;
  coordinator: string | null;
  status: string | null;
}

export interface ElderlyDischargeHandoverAssessment {
  id: string;
  assess_type: string | null;
  assess_date: string | null;
  assessor: string | null;
  total_score: number | null;
  result_level: string | null;
  detail: Record<string, unknown> | null;
  remark: string | null;
  created_at: string | null;
}

export interface ElderlyDischargeHandoverPlanItem {
  id: string;
  plan_id: string | null;
  action: string | null;
  frequency_code: string | null;
  frequency_name: string | null;
  duration_days: number | null;
  remark: string | null;
  status: string | null;
  created_at: string | null;
}

export interface ElderlyDischargeHandoverPlan {
  id: string;
  plan_name: string | null;
  goals: string | null;
  status: string | null;
  created_by: string | null;
  start_date: string | null;
  end_date: string | null;
  created_at: string | null;
  items: ElderlyDischargeHandoverPlanItem[];
}

export interface ElderlyDischargeHandoverTaskExecution {
  id: string;
  task_id: string | null;
  planned_time: string | null;
  actual_time: string | null;
  executor: string | null;
  status: string | null;
  note: string | null;
  created_at: string | null;
}

export interface ElderlyDischargeHandoverTask {
  id: string;
  description: string | null;
  task_type: string | null;
  frequency_code: string | null;
  frequency_name: string | null;
  start_date: string | null;
  end_date: string | null;
  status: string | null;
  created_at: string | null;
  executions: ElderlyDischargeHandoverTaskExecution[];
}

export interface ElderlyDischargeHandoverExecutionSummary {
  PENDING: number;
  IN_PROGRESS: number;
  COMPLETED: number;
  SKIPPED: number;
  CANCELLED: number;
}

export interface ElderlyDischargeHandoverNursingRecord {
  id: string;
  record_kind: string | null;
  title: string | null;
  content: string | null;
  record_time: string | null;
  record_date: string | null;
  author: string | null;
  corrects_record_id: string | null;
  created_at: string | null;
}

export interface ElderlyDischargeHandoverSnapshot {
  patient: ElderlyDischargeHandoverPatient;
  encounter: ElderlyDischargeHandoverEncounter;
  care_period: ElderlyDischargeHandoverCarePeriod;
  assessments: ElderlyDischargeHandoverAssessment[];
  plans: ElderlyDischargeHandoverPlan[];
  tasks: ElderlyDischargeHandoverTask[];
  execution_summary: ElderlyDischargeHandoverExecutionSummary;
  nursing_records: ElderlyDischargeHandoverNursingRecord[];
}

export interface ElderlyDischargeHandover {
  id: string;
  record_type: "DISCHARGE_SUMMARY";
  title: string;
  encounter_id: string;
  period_id: string | null;
  record_date: string | null;
  author: string | null;
  handover_note: string | null;
  generated_at: string | null;
  snapshot_version: number;
  snapshot: ElderlyDischargeHandoverSnapshot;
}

export interface ElderlyDischargeHandoverInput {
  author: string;
  handover_note?: string;
}

/**
 * 获取既有交接摘要；符合归档资格但尚未生成时返回 `null`（404），
 * 资格错误（400/409）与网络错误照常抛出。
 */
export async function getElderlyDischargeHandover(encounterId: string): Promise<ElderlyDischargeHandover | null> {
  try {
    return await request<ElderlyDischargeHandover>(`/healthcare/v1/elderly-admissions/${encodeURIComponent(encounterId)}/discharge-handover`);
  } catch (error) {
    if (error instanceof ApiRequestError && error.status === 404) return null;
    throw error;
  }
}

export function createElderlyDischargeHandover(
  encounterId: string,
  input: ElderlyDischargeHandoverInput,
): Promise<ElderlyDischargeHandover> {
  return request<ElderlyDischargeHandover>(`/healthcare/v1/elderly-admissions/${encodeURIComponent(encounterId)}/discharge-handover`, {
    method: "POST",
    body: JSON.stringify({
      author: input.author.trim(),
      ...(input.handover_note?.trim() ? { handover_note: input.handover_note.trim() } : {}),
    }),
  });
}

export function createEncounter(input: EncounterInput): Promise<Encounter> {
  return request<Encounter>("/healthcare/v1/encounters", {
    method: "POST",
    body: JSON.stringify({
      ...input,
      encounter_no: input.encounter_no.trim(),
    }),
  });
}

export function createElderlyAdmission(input: ElderlyAdmissionInput): Promise<ElderlyAdmission> {
  return request<ElderlyAdmission>("/healthcare/v1/elderly-admissions", {
    method: "POST",
    body: JSON.stringify({
      ...input,
      encounter_no: input.encounter_no.trim(),
    }),
  });
}

export function updateEncounter(id: string, input: Omit<EncounterInput, "patient_id" | "encounter_type" | "encounter_no" | "admit_date">): Promise<Encounter> {
  return request<Encounter>(`/healthcare/v1/encounters/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export interface DischargeInput {
  discharge_date?: string;
  discharge_diagnosis?: string;
}

export function dischargeEncounter(id: string, input: DischargeInput = {}): Promise<Encounter> {
  return request<Encounter>(`/healthcare/v1/encounters/${encodeURIComponent(id)}/discharge`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

function nursingQuery(params: Record<string, string | number | boolean | undefined>): string {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return query.toString() ? `?${query.toString()}` : "";
}

export function listNursingServicePeriods(params: {
  patient_id?: string;
  service_type?: string;
  status?: string;
  /** 精确过滤：只返回关联该入住记录的周期 */
  encounter_id?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingServicePeriod>> {
  return request<NursingPage<NursingServicePeriod>>(`/nursing/v1/periods/${nursingQuery(params)}`);
}

export function createNursingServicePeriod(input: NursingServicePeriodInput): Promise<NursingServicePeriod> {
  return request<NursingServicePeriod>("/nursing/v1/periods/", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 为历史活动养老入住幂等补建养老照护周期；首次 201，重复调用 200 */
export function enrollElderlyAdmissionCarePeriod(encounterId: string): Promise<NursingServicePeriod> {
  return request<NursingServicePeriod>("/nursing/v1/periods/elderly-admission", {
    method: "POST",
    body: JSON.stringify({ encounter_id: encounterId }),
  });
}

export function listNursingAssessments(params: {
  encounter_id?: string;
  period_id?: string;
  assess_type?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingAssessment>> {
  return request<NursingPage<NursingAssessment>>(`/nursing/v1/assessments/${nursingQuery(params)}`);
}

export function createNursingAssessment(input: NursingAssessmentInput): Promise<NursingAssessment> {
  return request<NursingAssessment>("/nursing/v1/assessments/", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listNursingPlans(params: {
  period_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingPlan>> {
  return request<NursingPage<NursingPlan>>(`/nursing/v1/plans/${nursingQuery(params)}`);
}

export function getNursingPlan(id: string): Promise<NursingPlan> {
  return request<NursingPlan>(`/nursing/v1/plans/${encodeURIComponent(id)}`);
}

export function createNursingPlan(input: NursingPlanInput): Promise<NursingPlan> {
  return request<NursingPlan>("/nursing/v1/plans/", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateNursingPlanStatus(id: string, status: string): Promise<NursingPlan> {
  return request<NursingPlan>(`/nursing/v1/plans/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

export function listNursingTasks(params: {
  period_id?: string;
  encounter_id?: string;
  task_type?: string;
  status?: string;
  plan_item_id?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingTask>> {
  return request<NursingPage<NursingTask>>(`/nursing/v1/tasks/${nursingQuery(params)}`);
}

export function createNursingTask(input: NursingTaskInput): Promise<NursingTask> {
  return request<NursingTask>("/nursing/v1/tasks/", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateNursingTaskStatus(id: string, status: string): Promise<NursingTask> {
  return request<NursingTask>(`/nursing/v1/tasks/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

export function listNursingTaskExecutions(params: {
  task_id?: string;
  executor?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingTaskExecution>> {
  return request<NursingPage<NursingTaskExecution>>(`/nursing/v1/executions/${nursingQuery(params)}`);
}

export function createNursingTaskExecution(input: NursingTaskExecutionInput): Promise<NursingTaskExecution> {
  return request<NursingTaskExecution>("/nursing/v1/executions/", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateNursingTaskExecutionStatus(
  id: string,
  status: string,
  note?: string,
): Promise<NursingTaskExecution> {
  const body: Record<string, string> = { status };
  if (note !== undefined && note.trim()) body.note = note.trim();
  return request<NursingTaskExecution>(`/nursing/v1/executions/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

/** 查询今日待办执行记录（带任务和长者摘要） */
export function listNursingTodayExecutions(params: {
  date?: string;
  period_id?: string;
  executor?: string;
  status?: string;
  overdue?: boolean;
  /** 按任务类型过滤（如 REHABILITATION 康复活动） */
  task_type?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingTodayExecution>> {
  return request<NursingPage<NursingTodayExecution>>(`/nursing/v1/executions/today${nursingQuery(params)}`);
}

/**
 * 跨日期全量逾期执行队列（§4.3）：
 * 返回全部 PENDING/IN_PROGRESS 且 planned_time < now 的执行，按 planned_time 升序，
 * 不施加任何日期窗口，也不产生任何写副作用（不调用 ensureExecutionsForDate）。
 */
export function listNursingOverdueExecutions(params: {
  period_id?: string;
  executor?: string;
  /** 按任务类型过滤（如 REHABILITATION 康复活动） */
  task_type?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingTodayExecution>> {
  return request<NursingPage<NursingTodayExecution>>(`/nursing/v1/executions/overdue${nursingQuery(params)}`);
}

/**
 * 逾期执行收口结果（计划 030 §4.3）：`converged` 为本次被置为 漏执行（SKIPPED）的条数，
 * `truncated` 表示命中数超过单次上限仍有剩余；重复调用第二次 `converged: 0`（幂等）。
 */
export interface ConvergeOverdueResult {
  converged: number;
  ids: string[];
  truncated: boolean;
  min_overdue_minutes: number;
}

/**
 * 批量收口逾期执行（幂等，人工显式触发）：把「逾期超过 min_overdue_minutes 且仍未终态」的
 * PENDING/IN_PROGRESS 执行置为 `SKIPPED`（漏执行），写 metadata.convergence 留痕。
 * 默认阈值 1440 分钟（24 小时），单次上限 200 条；不做任何自动/定时改写。
 */
export function convergeOverdueExecutions(input: {
  encounter_id?: string;
  task_type?: string;
  min_overdue_minutes?: number;
  limit?: number;
} = {}): Promise<ConvergeOverdueResult> {
  return request<ConvergeOverdueResult>("/nursing/v1/executions/converge-overdue", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 批量生成指定日期范围的执行记录 */
export function generateNursingExecutions(input: {
  date_from: string;
  date_to: string;
  period_id?: string;
}): Promise<{ generated: number; skipped: number; errors: unknown[] }> {
  return request<{ generated: number; skipped: number; errors: unknown[] }>(
    "/nursing/v1/executions/generate",
    { method: "POST", body: JSON.stringify(input) },
  );
}

// ========================================================================
//  Nursing API — Statistics (护理员工作量与计划完成率统计)
// ========================================================================

/** 执行统计记录（按执行人分组） */
export interface NursingExecutionStatistics {
  executor: string | null;
  scheduled_total: number;
  pending_total: number;
  in_progress_total: number;
  completed_total: number;
  skipped_total: number;
  cancelled_total: number;
  due_total: number;
  completed_due_total: number;
  overdue_total: number;
  completion_rate: number | null;
}

/** 执行统计分页响应 */
export interface NursingExecutionStatisticsPage {
  records: NursingExecutionStatistics[];
  meta: {
    total: number;
    date_from: string;
    date_to: string;
    scheduled_total: number;
    pending_total: number;
    in_progress_total: number;
    completed_total: number;
    skipped_total: number;
    cancelled_total: number;
    due_total: number;
    completed_due_total: number;
    overdue_total: number;
    completion_rate: number | null;
  };
}

/** 查询执行统计 */
export function listNursingExecutionStatistics(params: {
  date_from: string;
  date_to: string;
  period_id?: string;
  executor?: string;
  /** 按任务类型过滤（如 REHABILITATION 康复活动） */
  task_type?: string;
  limit?: number;
  offset?: number;
}): Promise<NursingExecutionStatisticsPage> {
  return request<NursingExecutionStatisticsPage>(`/nursing/v1/executions/statistics${nursingQuery(params)}`);
}

// ========================================================================
//  护理记录 (NURSING_RECORD)
// ========================================================================

/** 护理记录 */
export interface NursingRecord {
  id: string;
  encounter_id: string;
  period_id: string | null;
  record_type: string;
  record_kind: string | null;
  title: string;
  content: string | null;
  record_time: string | null;
  record_date: string | null;
  author: string | null;
  task_execution_id: string | null;
  task_id: string | null;
  corrects_record_id: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string | null;
  updated_at: string | null;
  /** 仅 get 详情时返回 */
  is_corrected?: boolean;
  correction_count?: number;
}

export interface NursingRecordInput {
  period_id: string;
  encounter_id: string;
  title: string;
  content: string;
  record_time?: string;
  task_execution_id?: string;
  author?: string;
}

export interface NursingRecordCorrectionInput {
  content: string;
  record_time?: string;
  author?: string;
}

/** 时间线事件 */
export interface NursingTimelineEvent {
  id: string;
  event_type: "ASSESSMENT" | "CARE_PLAN" | "TASK" | "TASK_EXECUTION" | "NURSING_RECORD" | "NURSING_INCIDENT" | "SHIFT_HANDOVER";
  occurred_at: string;
  title: string;
  summary: string | null;
  actor: string | null;
  status?: string;
  source: { resource: string; id: string };
  metadata: Record<string, unknown>;
}

// ========================================================================
//  Healthcare API — Nursing Records
// ========================================================================

export function createNursingRecord(input: NursingRecordInput): Promise<NursingRecord> {
  return request<NursingRecord>("/healthcare/v1/nursing-records", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listNursingRecords(params: {
  period_id?: string;
  encounter_id?: string;
  date_from?: string;
  date_to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<NursingRecord>> {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<NursingPage<NursingRecord>>(`/healthcare/v1/nursing-records${suffix}`);
}

export function getNursingRecord(id: string): Promise<NursingRecord> {
  return request<NursingRecord>(`/healthcare/v1/nursing-records/${encodeURIComponent(id)}`);
}

// ========================================================================
//  Healthcare API — 院内护理异常事件（017）
// ========================================================================

export interface NursingIncident {
  id: string;
  encounter_id: string;
  period_id: string;
  incident_type: string;
  severity: string;
  status: "已上报" | "处理中" | "已关闭";
  occurred_at: string;
  description: string;
  reporter: string | null;
  created_at: string;
  updated_at: string;
}

export interface NursingIncidentAction {
  id: string;
  incident_id: string;
  action_type: string;
  body: string;
  actor: string | null;
  occurred_at: string;
  notified_party: string | null;
  notification_result: string | null;
  created_at: string;
}

export interface NursingIncidentDetail extends NursingIncident {
  actions: NursingIncidentAction[];
}

export interface NursingIncidentCreateInput {
  incident_type: string;
  severity: string;
  occurred_at: string;
  description: string;
  initial_action?: {
    action_type: string;
    body: string;
    notified_party?: string;
    notification_result?: string;
  };
}

export interface NursingIncidentActionInput {
  action_type: string;
  body: string;
  notified_party?: string;
  notification_result?: string;
}

export interface NursingIncidentCloseInput {
  close_note: string;
}

export function createNursingIncident(
  encounterId: string,
  input: NursingIncidentCreateInput,
): Promise<NursingIncident> {
  return request<NursingIncident>(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/nursing-incidents`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

export function listNursingIncidents(params: {
  encounter_id: string;
  status?: string;
  date_from?: string;
  date_to?: string;
  limit?: number;
  offset?: number;
}): Promise<NursingPage<NursingIncident>> {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return request<NursingPage<NursingIncident>>(
    `/healthcare/v1/encounters/${encodeURIComponent(params.encounter_id)}/nursing-incidents?${query.toString()}`,
  );
}

export function getNursingIncident(
  encounterId: string,
  id: string,
): Promise<NursingIncidentDetail> {
  return request<NursingIncidentDetail>(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/nursing-incidents/${encodeURIComponent(id)}`,
  );
}

export function createNursingIncidentAction(
  encounterId: string,
  id: string,
  input: NursingIncidentActionInput,
): Promise<{ incident: NursingIncident; action: NursingIncidentAction }> {
  return request(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/nursing-incidents/${encodeURIComponent(id)}/actions`,
    {
      method: "POST",
      body: JSON.stringify(input),
    },
  );
}

export function closeNursingIncident(
  encounterId: string,
  id: string,
  input: NursingIncidentCloseInput,
): Promise<{ incident: NursingIncident; action: NursingIncidentAction }> {
  return request(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/nursing-incidents/${encodeURIComponent(id)}/close`,
    {
      method: "POST",
      body: JSON.stringify(input),
    },
  );
}

// ========================================================================
//  Healthcare API — 班次交接（017）
// ========================================================================

export interface ShiftHandoverItem {
  id: string;
  handover_id: string;
  item_kind: "执行" | "事件" | "护理记录" | "入住" | "手工";
  encounter_id: string | null;
  period_id: string | null;
  source_id: string | null;
  summary: string;
  created_by: string | null;
  snapshot_at: string | null;
  created_at: string;
}

export interface ShiftHandover {
  id: string;
  care_unit: string;
  business_date: string;
  shift: "早班" | "中班" | "夜班";
  handover_by: string | null;
  handed_over_at: string | null;
  received_by: string | null;
  received_at: string | null;
  status: "待接班" | "已接班";
  item_count?: number;
  created_at: string;
  updated_at: string;
}

export interface ShiftHandoverDetail extends ShiftHandover {
  items: ShiftHandoverItem[];
}

export interface ShiftHandoverCreateInput {
  /** 当前照护单元内任一活动养老入住；服务端据此推导并验证照护单元 */
  encounter_id: string;
  business_date: string;
  shift: string;
  manual_items?: string[];
}

export function createShiftHandover(
  input: ShiftHandoverCreateInput,
  idempotencyKey: string,
): Promise<ShiftHandoverDetail> {
  return request<ShiftHandoverDetail>("/healthcare/v1/nursing-shift-handovers", {
    method: "POST",
    body: JSON.stringify(input),
    headers: { "Idempotency-Key": idempotencyKey },
  });
}

export function listShiftHandovers(params: {
  care_unit: string;
  business_date?: string;
  shift?: string;
  limit?: number;
  offset?: number;
}): Promise<NursingPage<ShiftHandover>> {
  const query = new URLSearchParams();
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== "") query.set(key, String(value));
  });
  return request<NursingPage<ShiftHandover>>(`/healthcare/v1/nursing-shift-handovers?${query.toString()}`);
}

export function getShiftHandover(id: string): Promise<ShiftHandoverDetail> {
  return request<ShiftHandoverDetail>(`/healthcare/v1/nursing-shift-handovers/${encodeURIComponent(id)}`);
}

export function receiveShiftHandover(id: string): Promise<ShiftHandoverDetail> {
  return request<ShiftHandoverDetail>(`/healthcare/v1/nursing-shift-handovers/${encodeURIComponent(id)}/receive`, {
    method: "POST",
    body: JSON.stringify({}),
  });
}

export function appendShiftHandoverItem(id: string, content: string): Promise<ShiftHandoverDetail> {
  return request<ShiftHandoverDetail>(`/healthcare/v1/nursing-shift-handovers/${encodeURIComponent(id)}/items`, {
    method: "POST",
    body: JSON.stringify({ content }),
  });
}

export function createNursingRecordCorrection(
  id: string,
  input: NursingRecordCorrectionInput,
): Promise<NursingRecord> {
  return request<NursingRecord>(
    `/healthcare/v1/nursing-records/${encodeURIComponent(id)}/corrections`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

// ========================================================================
//  Nursing API — Timeline
// ========================================================================

export function listNursingTimeline(params: {
  period_id: string;
  encounter_id: string;
  date_from?: string;
  date_to?: string;
  event_type?: string;
  limit?: number;
  offset?: number;
}): Promise<NursingPage<NursingTimelineEvent>> {
  return request<NursingPage<NursingTimelineEvent>>(`/nursing/v1/timeline${nursingQuery(params)}`);
}

// ========================================================================
//  Nursing API — Consumptions (耗材)
// ========================================================================

/** 与后端 NUMERIC 对应的无损十进制文本。 */
export type DecimalText = string;

/** 耗材摘要 */
export interface NursingConsumptionSummary {
  count: number;
  warehouse: string;
  total_cost: DecimalText;
}

/** 耗材明细（016：单一基础数量与基础单位快照） */
export interface NursingExecutionConsumption {
  id: string;
  stock_operation_detail_id: string;
  stock_id: string;
  material_id: string;
  material_name?: string;
  lot_id: string | null;
  batch_no?: string | null;
  warehouse: string;
  quantity: DecimalText;
  unit: string;
  unit_cost: DecimalText;
  total_cost: DecimalText;
  created_at: string;
}

/** 耗材输入项（016）：客户端只提交 stock_id + 基础数量 */
export interface NursingConsumptionInput {
  stock_id: string;
  quantity: DecimalText;
}

/** 带耗材的状态更新 */
export function updateNursingTaskExecutionStatusWithConsumptions(
  id: string,
  status: string,
  note?: string,
  consumptions?: NursingConsumptionInput[],
): Promise<NursingTaskExecution> {
  const body: Record<string, unknown> = { status };
  if (note !== undefined && note.trim()) body.note = note.trim();
  if (consumptions !== undefined && consumptions.length > 0) body.consumptions = consumptions;
  return request<NursingTaskExecution>(`/nursing/v1/executions/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify(body),
  });
}

/** 查询执行耗材明细 */
export function listNursingExecutionConsumptions(id: string): Promise<NursingPage<NursingExecutionConsumption>> {
  return request<NursingPage<NursingExecutionConsumption>>(`/nursing/v1/executions/${encodeURIComponent(id)}/consumptions`);
}

// ========================================================================
//  Nursing API — Medication Administrations (医嘱给药记录 MAR)
// ========================================================================

/** 给药记录（每执行实例至多一条，与执行 1:1） */
export interface MedicationAdministration {
  id: string;
  task_execution_id: string;
  medical_order_id: string;
  /** 已服 / 部分服 / 拒服 / 漏服 / 暂缓（项目惯例存中文值） */
  result: string;
  /** 实际给药数量（基础单位，十进制文本） */
  administered_quantity: string | null;
  unit: string | null;
  dispense_item_id: string | null;
  dispense_no: string | null;
  material_id: string | null;
  material_name: string | null;
  lot_id: string | null;
  batch_no: string | null;
  warehouse: string | null;
  /** 给药人（认证 userId，服务端写入） */
  administered_by: string | null;
  /** 给药时间（服务端当前时间） */
  administered_at: string | null;
  reason: string | null;
  planned_time: string | null;
  task_description: string | null;
  /** 绑定医嘱的类型与结构化明细（未绑定医嘱时为空；给药与给药记录显示药品 / 项目） */
  order_details?: Record<string, unknown> | null;
  order_type?: string | null;
  patient_name: string | null;
  created_at: string | null;
  updated_at: string | null;
}

/**
 * 给药输入：服务端受控字段（给药人/时间/医嘱归属）不接受提交；
 * 已服/部分服必填 dispense_item_id + administered_quantity（十进制文本）；
 * 拒服/漏服/暂缓必填 reason，且不得带来源与数量。
 */
export interface MedicationAdministrationInput {
  result: string;
  dispense_item_id?: string;
  administered_quantity?: string;
  reason?: string;
}

/** 给药来源选择器行：该医嘱已 DISPENSED 且剩余数量 > 0 的发药明细 */
export interface MedicationAdministrationSource {
  id: string;
  dispense_id: string | null;
  dispense_no: string | null;
  dispensed_at: string | null;
  material_id: string | null;
  material_name: string | null;
  lot_id: string | null;
  batch_no: string | null;
  warehouse: string | null;
  unit: string | null;
  dispensed_quantity: string;
  administered_quantity: string;
  remaining_quantity: string;
  /** 每次数量（基础单位，取自医嘱明细 `order_details.dose_quantity`，032 P4）；用于给药数量默认值，缺失为 null */
  dose_quantity: string | null;
}

/** 记录给药：成功联动执行为 COMPLETED/SKIPPED 并写 actual_time；给药人与时间由服务端写入 */
export function recordMedicationAdministration(
  executionId: string,
  input: MedicationAdministrationInput,
): Promise<MedicationAdministration> {
  return request<MedicationAdministration>(`/nursing/v1/executions/${encodeURIComponent(executionId)}/administration`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 单条给药记录（含来源发药明细摘要）；不存在返回 404 */
export function getMedicationAdministration(executionId: string): Promise<MedicationAdministration> {
  return request<MedicationAdministration>(`/nursing/v1/executions/${encodeURIComponent(executionId)}/administration`);
}

/** 给药来源选择器：已发药（DISPENSED）且未给完的发药明细，只读 */
export function listMedicationAdministrationSources(executionId: string): Promise<NursingPage<MedicationAdministrationSource>> {
  return request<NursingPage<MedicationAdministrationSource>>(`/nursing/v1/executions/${encodeURIComponent(executionId)}/administration/sources`);
}

/** MAR 查询：按长者（encounter_id）/医嘱/给药日期/结果过滤给药明细 */
export function listMedicationAdministrations(params: {
  encounter_id?: string;
  medical_order_id?: string;
  date?: string;
  result?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NursingPage<MedicationAdministration>> {
  return request<NursingPage<MedicationAdministration>>(`/nursing/v1/executions/administrations${nursingQuery(params)}`);
}

// ========================================================================
//  Inventory API — Stocks (库存)
// ========================================================================

/** 可用库存（016：quantity/locked_quantity/available_quantity 均为基础数量） */
export interface InventoryStockAvailability {
  id: string;
  warehouse: string;
  material_id: string;
  material_code: string;
  material_name: string;
  category: string;
  lot_id: string | null;
  batch_no: string | null;
  expiry_date: string | null;
  quantity: DecimalText;
  locked_quantity: DecimalText;
  available_quantity: DecimalText;
  unit_cost: DecimalText;
  unit: string;
}

export type InventoryStockPage = NursingPage<InventoryStockAvailability>;

/** 查询可用库存 */
export function listInventoryStocks(params: {
  warehouse?: string;
  material_id?: string;
  search?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<InventoryStockPage> {
  return request<InventoryStockPage>(`/inventories/v1/stocks${nursingQuery(params)}`);
}

/** 入库明细（016）：基础数量与每基础单位成本 */
export interface InventoryInboundItem {
  material_id: string;
  lot_id?: string;
  quantity: DecimalText;
  unit_cost: DecimalText;
}

/** 确认入库 */
export function confirmInventoryInbound(input: {
  warehouse: string;
  items: InventoryInboundItem[];
  note?: string;
}) {
  return request("/inventories/v1/operations/inbound", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

// ========================================================================
//  Inventory API — Materials 与单位模型 (计划 015)
// ========================================================================

/** 物资（016：一个 base_unit 与一个 quantity_scale）
 * package_unit/package_size 为可选包装规格投影：仅用于整包/拆零展示与换算，库存账本仍以基础单位记账 */
export interface InventoryMaterial {
  id: string;
  code: string;
  name: string;
  category: string;
  spec: string | null;
  base_unit: string;
  quantity_scale: number;
  package_unit: string | null;
  package_size: DecimalText | null;
  enable_batch_control: boolean | null;
  cost_method: string | null;
  metadata: Record<string, unknown> | null;
  status: string;
  created_at: string;
  updated_at: string | null;
}

export type InventoryMaterialPage = NursingPage<InventoryMaterial>;

/** 查询物资 */
export function listInventoryMaterials(params: {
  code?: string;
  name?: string;
  category?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<InventoryMaterialPage> {
  const query = new URLSearchParams();
  if (params.code?.trim()) query.set("code", params.code.trim());
  if (params.name?.trim()) query.set("name", params.name.trim());
  if (params.category?.trim()) query.set("category", params.category.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<InventoryMaterialPage>(`/inventories/v1/materials${suffix}`);
}

/** 创建物资：一次性提交 base_unit 与 quantity_scale（0..6）；包装规格可选 */
export function createInventoryMaterial(input: {
  code: string;
  name: string;
  category: string;
  base_unit: string;
  quantity_scale?: number;
  spec?: string;
  package_unit?: string;
  package_size?: DecimalText;
  enable_batch_control?: boolean;
  cost_method?: string;
  status?: string;
  metadata?: Record<string, unknown> | null;
}): Promise<InventoryMaterial> {
  return request<InventoryMaterial>("/inventories/v1/materials", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 物资详情 */
export function getInventoryMaterial(id: string): Promise<InventoryMaterial> {
  return request<InventoryMaterial>(`/inventories/v1/materials/${encodeURIComponent(id)}`);
}

/** 更新物资：code 不可改；base_unit/quantity_scale 存在库存事实后服务端返回 409；包装规格可随时调整 */
export function updateInventoryMaterial(
  id: string,
  input: Partial<Pick<InventoryMaterial, "name" | "category" | "spec" | "base_unit" | "quantity_scale" | "package_unit" | "package_size" | "metadata" | "status">>,
): Promise<InventoryMaterial> {
  return request<InventoryMaterial>(`/inventories/v1/materials/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** 删除物资（存在库存事实时服务端返回 409） */
export function deleteInventoryMaterial(id: string): Promise<void> {
  return request<void>(`/inventories/v1/materials/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

/** 批次（批控物资的批次字典） */
export interface InventoryLot {
  id: string;
  material_id: string;
  batch_no: string;
  production_date: string | null;
  expiry_date: string | null;
  manufacturer: string | null;
  supplier: string | null;
  metadata: Record<string, unknown> | null;
}

/** 查询批次列表（可按物资过滤） */
export function listInventoryLots(params: {
  material_id?: string;
  batch_no?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<{ records: InventoryLot[]; meta: { total: number } }> {
  const query = new URLSearchParams();
  if (params.material_id?.trim()) query.set("material_id", params.material_id.trim());
  if (params.batch_no?.trim()) query.set("batch_no", params.batch_no.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<{ records: InventoryLot[]; meta: { total: number } }>(`/inventories/v1/lots${suffix}`);
}

// ─── 医生病程记录 (Progress Notes) ─────────────────────────────────────

export interface ProgressNote {
  id: string;
  encounter_id: string;
  note_type: string;
  content: string;
  physician: string;
  record_time: string;
  created_at: string;
}

export interface ProgressNoteInput {
  note_type: string;
  content: string;
  /**
   * 046：医生署名由**服务端按会话派生、只读**，前端不再提交该字段（缺省即可）。
   * 保留可选字段仅为兼容既有调用方与无认证挂载的嵌入式/集成测试。
   */
  physician?: string;
  /** 缺省时由服务端使用当前业务时间 */
  record_time?: string;
}

export interface ProgressNoteList {
  records: ProgressNote[];
  meta: { total: number };
}

/** 创建医生病程记录（仅活动养老入住；追加写入，不提供覆盖式编辑） */
export function createProgressNote(encounterId: string, input: ProgressNoteInput): Promise<ProgressNote> {
  return request<ProgressNote>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/progress-notes`, {
    method: "POST",
    body: JSON.stringify({
      note_type: input.note_type,
      content: input.content.trim(),
      ...(input.physician?.trim() ? { physician: input.physician.trim() } : {}),
      ...(input.record_time?.trim() ? { record_time: input.record_time.trim() } : {}),
    }),
  });
}

export function listProgressNotes(
  encounterId: string,
  params: { note_type?: string; date_from?: string; date_to?: string; limit?: number; offset?: number } = {},
): Promise<ProgressNoteList> {
  const query = new URLSearchParams();
  if (params.note_type?.trim()) query.set("note_type", params.note_type.trim());
  if (params.date_from?.trim()) query.set("date_from", params.date_from.trim());
  if (params.date_to?.trim()) query.set("date_to", params.date_to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ProgressNoteList>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/progress-notes${suffix}`);
}

export function getProgressNote(id: string): Promise<ProgressNote> {
  return request<ProgressNote>(`/healthcare/v1/progress-notes/${encodeURIComponent(id)}`);
}

// ─── 诊断 (Diagnoses) ─────────────────────────────────────────────────

export interface Diagnosis {
  id: string;
  encounter_id: string;
  diagnosis_type: string;
  icd_code: string | null;
  diagnosis_text: string;
  diagnosis_date: string;
  physician: string;
  /** 服务端按 diagnosis_type 派生的只读字段：PRIMARY 时为 true */
  is_major: boolean;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface DiagnosisInput {
  diagnosis_type: "PRIMARY" | "SECONDARY";
  diagnosis_text: string;
  diagnosis_date: string;
  /** 046：医生署名由服务端按会话派生、只读；缺省即可（保留可选字段兼容既有调用方/测试）。 */
  physician?: string;
  icd_code?: string;
  /** 备注写入服务端受控 metadata */
  remark?: string;
}

export interface DiagnosisList {
  records: Diagnosis[];
  meta: { total: number };
}

/** 创建诊断（仅活动养老入住；不提供覆盖式编辑和删除） */
export function createDiagnosis(encounterId: string, input: DiagnosisInput): Promise<Diagnosis> {
  return request<Diagnosis>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/diagnoses`, {
    method: "POST",
    body: JSON.stringify({
      diagnosis_type: input.diagnosis_type,
      diagnosis_text: input.diagnosis_text.trim(),
      diagnosis_date: input.diagnosis_date,
      ...(input.physician?.trim() ? { physician: input.physician.trim() } : {}),
      ...(input.icd_code?.trim() ? { icd_code: input.icd_code.trim() } : {}),
      ...(input.remark?.trim() ? { remark: input.remark.trim() } : {}),
    }),
  });
}

export function listDiagnoses(
  encounterId: string,
  params: { diagnosis_type?: string; limit?: number; offset?: number } = {},
): Promise<DiagnosisList> {
  const query = new URLSearchParams();
  if (params.diagnosis_type?.trim()) query.set("diagnosis_type", params.diagnosis_type.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<DiagnosisList>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/diagnoses${suffix}`);
}

export function getDiagnosis(id: string): Promise<Diagnosis> {
  return request<Diagnosis>(`/healthcare/v1/diagnoses/${encodeURIComponent(id)}`);
}

// ─── 医嘱 (Medical Orders) ─────────────────────────────────────────────
export interface MedicalOrderExecutionSummary {
  PENDING: number;
  IN_PROGRESS: number;
  COMPLETED: number;
  SKIPPED: number;
  CANCELLED: number;
}

/** 医嘱给药汇总（医嘱侧只读）：已给次数/数量、拒服/漏服/暂缓次数、已发/剩余数量 */
export interface MedicalOrderAdministrationSummary {
  /** 已给次数（已服 + 部分服） */
  administered_count: number;
  /** 已给数量（已服 + 部分服实际数量之和，十进制文本） */
  administered_quantity: string;
  partial_count: number;
  refused_count: number;
  missed_count: number;
  deferred_count: number;
  /** 已发数量（DISPENSED 发药明细之和）；未发药为 null */
  dispensed_quantity: string | null;
  /** 剩余数量 = 已发 - 已给；未发药为 null */
  remaining_quantity: string | null;
}

/** 给药明细行（医嘱侧只读，按 task 关联） */
export interface MedicalOrderAdministration {
  id: string;
  task_execution_id: string;
  result: string;
  administered_quantity: string | null;
  unit: string | null;
  dispense_item_id: string | null;
  lot_id: string | null;
  warehouse: string | null;
  administered_by: string | null;
  administered_at: string | null;
  reason: string | null;
  created_at: string | null;
  planned_time: string | null;
  task_description: string | null;
  material_id: string | null;
  material_name: string | null;
  batch_no: string | null;
  dispense_no: string | null;
}

export interface MedicalOrderAdministrationList {
  records: MedicalOrderAdministration[];
  meta: { total: number };
}

export interface MedicalOrder {
  id: string;
  encounter_id: string;
  order_type: string;
  /** 只读展示字段：用药医嘱/治疗医嘱/检查医嘱/检验医嘱 */
  order_type_label: string | null;
  /** LONG_TERM / TEMPORARY；历史 008 医嘱可为 null */
  order_class: string | null;
  /** 只读展示字段：长期医嘱/临时医嘱 */
  order_class_label: string | null;
  order_content: string;
  order_details: Record<string, unknown>;
  start_time: string | null;
  end_time: string | null;
  doctor: string;
  status: string;
  /** 护士核对审计：未核对为 null */
  nurse_checked_by: string | null;
  /** 护士核对时间：与 nurse_checked_by 成对出现，未核对为 null */
  nurse_checked_at: string | null;
  task_id: string | null;
  execution_summary?: MedicalOrderExecutionSummary;
  /** 给药汇总（医嘱侧只读）：无给药记录时为零值，未发药时 dispensed/remaining 为 null */
  administration_summary?: MedicalOrderAdministrationSummary;
  /** 到期派生字段：status=ACTIVE 且 end_time < now；end_time 为空恒为 false */
  is_expired: boolean;
  /** 已到期分钟数：is_expired 为 false 时为 null */
  expired_minutes: number | null;
  created_at: string;
  updated_at: string;
}

export interface MedicalOrderList {
  records: MedicalOrder[];
  meta: { total: number };
}

export interface MedicalOrderInput {
  order_type: string;
  /** 新建医嘱必填：LONG_TERM / TEMPORARY，服务端不接受空值 */
  order_class: string;
  order_content: string;
  /** 046：医生署名由服务端按会话派生、只读；前端不再提交该字段（缺省即可）。 */
  doctor?: string;
  start_time: string;
  /** 临时医嘱可用的明确结束时间 */
  end_time?: string;
  order_details?: Record<string, unknown>;
}

export interface DeathInput {
  death_date: string;
  death_cause?: string;
}

export function createMedicalOrder(encounterId: string, input: MedicalOrderInput): Promise<MedicalOrder> {
  return request<MedicalOrder>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/orders`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listMedicalOrders(encounterId: string, params: { order_type?: string; status?: string; limit?: number; offset?: number } = {}): Promise<MedicalOrderList> {
  const query = new URLSearchParams();
  if (params.order_type?.trim()) query.set("order_type", params.order_type.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<MedicalOrderList>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/orders${suffix}`);
}

export function getMedicalOrder(id: string): Promise<MedicalOrder> {
  return request<MedicalOrder>(`/healthcare/v1/orders/${encodeURIComponent(id)}`);
}

export function updateMedicalOrderStatus(id: string, status: string): Promise<MedicalOrder> {
  return request<MedicalOrder>(`/healthcare/v1/orders/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

/** 护士核对用药医嘱：核对人由服务端认证中间件取得，请求体必须为空 */
export function nurseCheckMedicalOrder(id: string): Promise<MedicalOrder> {
  return request<MedicalOrder>(`/healthcare/v1/orders/${encodeURIComponent(id)}/nurse-check`, {
    method: "PATCH",
    body: JSON.stringify({}),
  });
}

/** 收束结果（§4.5）：closed 为本次收束条数；warnings 为不阻断收束的异常 */
export interface CloseExpiredMedicalOrdersResult {
  closed: number;
  order_ids: string[];
  warnings: { order_id: string; reason: string }[];
  closed_at: string;
}

/**
 * 显式收束已到期医嘱（§4.5，幂等）：
 * 把 ACTIVE 且 end_time < now 的医嘱收束为 COMPLETED（保留 end_time，结束其护理任务，
 * 写 metadata.convergence 审计）。传 encounter_id 时只收束该入住范围内的候选。
 */
export function closeExpiredMedicalOrders(input: { encounter_id?: string }): Promise<CloseExpiredMedicalOrdersResult> {
  return request<CloseExpiredMedicalOrdersResult>("/healthcare/v1/orders/close-expired", {
    method: "POST",
    body: JSON.stringify(input.encounter_id ? { encounter_id: input.encounter_id } : {}),
  });
}

/** 护士核对汇总行：跨入住待核对用药医嘱（未核对），含患者/入住信息 */
export interface NurseCheckPendingOrder extends MedicalOrder {
  patient_id: string;
  patient_name: string;
  encounter_no: string | null;
}

export interface NurseCheckPendingOrderList {
  records: NurseCheckPendingOrder[];
  meta: { total: number };
}

/** 护士核对汇总列表：仅 ELDERLY_CARE + ACTIVE + MEDICATION + ACTIVE + 未核对 */
export function listPendingNurseCheckOrders(params: {
  encounter_id?: string;
  search?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NurseCheckPendingOrderList> {
  const query = new URLSearchParams();
  if (params.encounter_id) query.set("encounter_id", params.encounter_id);
  if (params.search) query.set("search", params.search);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<NurseCheckPendingOrderList>(`/healthcare/v1/orders/pending-nurse-check${qs ? `?${qs}` : ""}`);
}

/** 医嘱给药明细（只读）：按 task 关联，与执行汇总/给药汇总同源一致 */
export function listOrderAdministrations(orderId: string): Promise<MedicalOrderAdministrationList> {
  return request<MedicalOrderAdministrationList>(`/healthcare/v1/orders/${encodeURIComponent(orderId)}/administrations`);
}

// ─── 历史医嘱待补绑目录药品（计划 030 §4.4） ──────────────────────────

/**
 * 待补绑目录药品的用药医嘱：`MEDICATION` + `ACTIVE` + `order_details.material_id` 为空，
 * 且属于养老（`ELDERLY_CARE`）入住。`drug_name` 是历史自由文本药名（可能为空）。
 */
export interface UnboundMaterialOrder {
  id: string;
  encounter_id: string;
  encounter_no: string | null;
  patient_id: string | null;
  patient_name: string | null;
  order_content: string | null;
  /** 历史自由文本药名（`order_details.drug_name`），未填为 null */
  drug_name: string | null;
  start_time: string | null;
  end_time: string | null;
  nurse_checked_at: string | null;
  created_at: string | null;
}

export interface UnboundMaterialOrderList {
  records: UnboundMaterialOrder[];
  meta: { total: number };
}

/** 待补绑清单（只读，可反复调用）：支持 encounter_id / search 过滤，倒序分页 */
export function listUnboundMaterialOrders(params: {
  encounter_id?: string;
  search?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<UnboundMaterialOrderList> {
  const query = new URLSearchParams();
  if (params.encounter_id) query.set("encounter_id", params.encounter_id);
  if (params.search) query.set("search", params.search);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<UnboundMaterialOrderList>(`/healthcare/v1/orders/unbound-material${qs ? `?${qs}` : ""}`);
}

export interface BoundMaterialItem {
  order_id: string;
  material_id: string;
  material_code: string | null;
  material_name: string | null;
}

/**
 * 批量补绑目录药品（单事务全成全败，≤100 条）：任一目录项不存在/停用或任一条已绑定即整体失败
 * （404/400/409），不做部分成功。
 */
export function bindMaterialOrdersBatch(input: {
  items: { order_id: string; material_id: string }[];
}): Promise<{ bound: number; items: BoundMaterialItem[] }> {
  return request<{ bound: number; items: BoundMaterialItem[] }>(
    "/healthcare/v1/orders/bind-material-batch",
    { method: "POST", body: JSON.stringify(input) },
  );
}

export function markEncounterDeath(encounterId: string, input: DeathInput): Promise<Encounter> {
  return request<Encounter>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/death`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

// ─── 复评与照护计划修订 (Care Plan Revisions) ─────────────────────────────

export interface CarePlanRevisionAssessmentInput {
  assess_type: string;
  assess_date: string;
  assessor?: string;
  total_score?: number;
  result_level?: string;
  detail?: Record<string, unknown>;
  remark?: string;
}

export interface CarePlanRevisionPlanItemInput {
  action: string;
  frequency_code?: string;
  frequency_name?: string;
  duration_days?: number;
  remark?: string;
}

export interface CarePlanRevisionPlanInput {
  plan_name: string;
  goals?: string;
  created_by?: string;
  start_date: string;
  end_date?: string;
  items?: CarePlanRevisionPlanItemInput[];
}

export interface CarePlanRevisionInput {
  assessment: CarePlanRevisionAssessmentInput;
  plan: CarePlanRevisionPlanInput;
}

/** 创建响应中的新建任务（task_id 即 id） */
export interface CarePlanRevisionCreatedTask {
  id: string;
  plan_item_id: string | null;
  status: string;
  start_date: string | null;
  end_date: string | null;
}

export interface CarePlanRevisionCreated {
  revision_id: string;
  revision_no: number;
  assessment: NursingAssessment;
  previous_plan: { id: string; status: string };
  /** 新计划（含 items） */
  plan: NursingPlan;
  items: NursingPlanItem[];
  tasks: CarePlanRevisionCreatedTask[];
}

export interface CarePlanRevisionListItem {
  id: string;
  period_id: string;
  encounter_id: string;
  revision_no: number;
  assessment_id: string;
  assessment: {
    assess_type: string | null;
    assess_date: string | null;
    assessor: string | null;
    result_level: string | null;
  };
  previous_plan_id: string | null;
  previous_plan: { id: string; plan_name: string; status: string } | null;
  new_plan_id: string;
  new_plan: { id: string; plan_name: string; status: string };
  created_at: string | null;
}

export interface CarePlanRevisionList {
  records: CarePlanRevisionListItem[];
  meta: { total: number };
}

export interface CarePlanRevisionDetail {
  id: string;
  period_id: string;
  encounter_id: string;
  assessment_id: string;
  previous_plan_id: string | null;
  new_plan_id: string;
  revision_no: number;
  created_at: string | null;
  assessment: NursingAssessment;
  previous_plan: NursingPlan | null;
  plan: NursingPlan;
  tasks: NursingTask[];
}

/** 创建复评并修订照护计划（单事务，前端不直接请求 Nursing API） */
export function createCarePlanRevision(
  encounterId: string,
  input: CarePlanRevisionInput,
): Promise<CarePlanRevisionCreated> {
  return request<CarePlanRevisionCreated>(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/care-plan-revisions`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** 修订历史列表（按修订号倒序） */
export function listCarePlanRevisions(encounterId: string): Promise<CarePlanRevisionList> {
  return request<CarePlanRevisionList>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/care-plan-revisions`);
}

/** 修订历史详情（评估、计划版本、措施与任务关联） */
export function getCarePlanRevision(id: string): Promise<CarePlanRevisionDetail> {
  return request<CarePlanRevisionDetail>(`/healthcare/v1/care-plan-revisions/${encodeURIComponent(id)}`);
}

// ─── 药房 (Pharmacy) ─────────────────────────────────────────────────
/** 待接方用药医嘱（来自 Healthcare 内部端口，仅活动养老入住 + ACTIVE 用药医嘱） */
export interface PharmacyMedicationOrder {
  order_id: string;
  encounter_id: string;
  patient_id: string;
  patient_name: string;
  encounter_no: string | null;
  order_type: string;
  order_type_label: string | null;
  order_class: string | null;
  order_class_label: string | null;
  drug_name: string | null;
  order_content: string;
  dose: string | null;
  unit: string | null;
  route: string | null;
  /** 每次数量（基础单位，医嘱明细 `order_details.dose_quantity`，032 P4）；未填写为 null */
  dose_quantity: string | null;
  /** 每日给药次数：服务端 FrequencyCalculator 由 frequency_code 推导；PRN/STAT/未知为 null */
  daily_dose_count: number | null;
  /** 疗程天数：医嘱明细 `order_details.duration_days`；缺失为 null */
  duration_days: number | null;
  /**
   * 本疗程应发总量（基础单位）= 每次数量 × 每日次数 × 疗程天数；STAT 为每次数量；
   * 无法可信推导时为 null（药房发药量不预填，交由药师手填）
   */
  prescribed_total_quantity: string | null;
  frequency_code: string | null;
  frequency_name: string | null;
  start_time: string | null;
  end_time: string | null;
  doctor: string;
  /** 医嘱绑定的药品目录物资（025）：历史自由文本医嘱为 null */
  material_id: string | null;
  /** 药品目录编码快照：与 material_id 成对出现 */
  material_code: string | null;
  /** 药品目录名称快照：与 material_id 成对出现 */
  material_name: string | null;
  /** 护士核对审计：药房待接方只返回已核对医嘱，两字段均非空 */
  nurse_checked_by: string | null;
  nurse_checked_at: string | null;
  /** 该医嘱已有未取消发药单时返回发药单 ID，页面显示已接方 */
  dispense_id: string | null;
  dispense_status: string | null;
}

export interface PharmacyMedicationOrderList {
  records: PharmacyMedicationOrder[];
  meta: { total: number };
}

export interface PharmacyDispenseItem {
  id: string;
  dispense_id: string;
  order_item_id: string | null;
  order_execution_id: string | null;
  material_id: string | null;
  lot_id: string | null;
  prescribed_quantity: DecimalText | null;
  dispensed_quantity: DecimalText | null;
  /** 累计已给药数量（护理给药记录「已服/部分服」，服务端派生） */
  administered_quantity?: DecimalText | null;
  /** 剩余可退数量 = 实发 − 已给药 − 未取消退药（待确认 + 已确认），服务端派生 */
  returnable_quantity?: DecimalText | null;
  stock_operation_detail_id: string | null;
  unit_cost: DecimalText | null;
  total_cost: DecimalText | null;
  metadata: Record<string, unknown> | null;
}

export interface PharmacyDispense {
  id: string;
  dispense_no: string;
  patient_id: string;
  encounter_id: string | null;
  dispense_type: string;
  status: string;
  pharmacist: string | null;
  reviewer: string | null;
  warehouse: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  dispensed_at: string | null;
  items: PharmacyDispenseItem[];
}

export interface PharmacyDispenseList {
  records: PharmacyDispense[];
  meta: { total: number };
}

export interface PharmacyReturnItem {
  id: string;
  return_id: string;
  dispense_item_id: string;
  quantity: DecimalText | null;
  stock_operation_detail_id: string | null;
  unit_cost: DecimalText | null;
  total_cost: DecimalText | null;
  metadata: Record<string, unknown> | null;
}

export interface PharmacyReturn {
  id: string;
  return_no: string;
  original_dispense_id: string;
  patient_id: string;
  return_reason: string | null;
  status: string;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  confirmed_at: string | null;
  total_quantity?: DecimalText | null;
  items: PharmacyReturnItem[];
}

export interface PharmacyReturnList {
  records: PharmacyReturn[];
  meta: { total: number };
}

export interface PharmacyReturnFromDispenseInput {
  dispense_id: string;
  dispense_item_id: string;
  quantity: DecimalText;
  return_reason: string;
  /**
   * 042：仅**平台管理员代操作**时才提交；业务账号一律不传，由服务端按认证会话落库。
   * 非管理员提交他人会被服务端以 `400 operator cannot be delegated` 拒绝。
   */
  operator?: string;
  restockable: true;
  remark?: string;
}

/** 从医嘱创建发药单入参；患者、入住、药名与医嘱内容由服务端从医嘱锁定读取 */
export interface PharmacyDispenseFromMedicalOrderInput {
  medical_order_id: string;
  warehouse: string;
  material_id: string;
  lot_id?: string;
  dispensed_quantity: DecimalText;
}

/** 审方/调配/确认/取消入参；`operator` 同 [PharmacyReturnFromDispenseInput]（042） */
export interface PharmacyDispenseActionInput {
  operator?: string;
  remark?: string;
}

/** 待接方用药医嘱列表（仅 ELDERLY_CARE + ACTIVE + MEDICATION + ACTIVE） */
export function listPharmacyMedicationOrders(params: {
  encounter_id?: string;
  search?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PharmacyMedicationOrderList> {
  const query = new URLSearchParams();
  if (params.encounter_id) query.set("encounter_id", params.encounter_id);
  if (params.search) query.set("search", params.search);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<PharmacyMedicationOrderList>(`/pharmacy/v1/dispenses/medication-orders${qs ? `?${qs}` : ""}`);
}

/** 从医嘱创建发药单（PENDING），同一医嘱已有未取消发药单时返回 409 */
export function createPharmacyDispenseFromMedicalOrder(
  input: PharmacyDispenseFromMedicalOrderInput,
): Promise<PharmacyDispense> {
  return request<PharmacyDispense>(`/pharmacy/v1/dispenses/from-medical-order`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 审方：PENDING → REVIEWED，写入 reviewer */
export function reviewPharmacyDispense(id: string, input: PharmacyDispenseActionInput): Promise<PharmacyDispense> {
  return request<PharmacyDispense>(`/pharmacy/v1/dispenses/${encodeURIComponent(id)}/review`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 开始调配：REVIEWED → DISPENSING，写入 pharmacist */
export function startPharmacyDispense(id: string, input: PharmacyDispenseActionInput): Promise<PharmacyDispense> {
  return request<PharmacyDispense>(`/pharmacy/v1/dispenses/${encodeURIComponent(id)}/start`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 发药确认：DISPENSING → DISPENSED，同事务扣库存并回写库存操作明细 ID；重试不重复扣库存 */
export function confirmPharmacyDispense(id: string, input: PharmacyDispenseActionInput): Promise<PharmacyDispense> {
  return request<PharmacyDispense>(`/pharmacy/v1/dispenses/${encodeURIComponent(id)}/confirm`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 取消：PENDING/REVIEWED/DISPENSING → CANCELLED；已有库存出库记录的不可取消 */
export function cancelPharmacyDispense(id: string, input: PharmacyDispenseActionInput): Promise<PharmacyDispense> {
  return request<PharmacyDispense>(`/pharmacy/v1/dispenses/${encodeURIComponent(id)}/cancel`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 发药单列表（含 ELDERLY_ROUTINE 与历史类型） */
export function listPharmacyDispenses(params: {
  patient_id?: string;
  encounter_id?: string;
  dispense_type?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PharmacyDispenseList> {
  const query = new URLSearchParams();
  if (params.patient_id) query.set("patient_id", params.patient_id);
  if (params.encounter_id) query.set("encounter_id", params.encounter_id);
  if (params.dispense_type) query.set("dispense_type", params.dispense_type);
  if (params.status) query.set("status", params.status);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<PharmacyDispenseList>(`/pharmacy/v1/dispenses${qs ? `?${qs}` : ""}`);
}

/** 发药单详情（含明细 items） */
export function getPharmacyDispense(id: string): Promise<PharmacyDispense> {
  return request<PharmacyDispense>(`/pharmacy/v1/dispenses/${encodeURIComponent(id)}`);
}

/** 退药单列表：只读，不触发库存操作 */
export function listPharmacyReturns(params: {
  patient_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PharmacyReturnList> {
  const query = new URLSearchParams();
  if (params.patient_id) query.set("patient_id", params.patient_id);
  if (params.status) query.set("status", params.status);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<PharmacyReturnList>(`/pharmacy/v1/returns${qs ? `?${qs}` : ""}`);
}

export function getPharmacyReturn(id: string): Promise<PharmacyReturn> {
  return request<PharmacyReturn>(`/pharmacy/v1/returns/${encodeURIComponent(id)}`);
}

/** 从已发药明细创建待确认退药单，服务端推导患者、物资、批次和成本 */
export function createPharmacyReturnFromDispense(input: PharmacyReturnFromDispenseInput): Promise<PharmacyReturn> {
  return request<PharmacyReturn>(`/pharmacy/v1/returns/from-dispense`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 042：`operator` 仅平台管理员代操作时提交；不传则由服务端按会话落库 */
export function confirmPharmacyReturn(id: string, operator?: string): Promise<PharmacyReturn> {
  return request<PharmacyReturn>(`/pharmacy/v1/returns/${encodeURIComponent(id)}/confirm`, {
    method: "PUT",
    body: JSON.stringify(operator?.trim() ? { operator: operator.trim() } : {}),
  });
}

export function cancelPharmacyReturn(id: string): Promise<PharmacyReturn> {
  return request<PharmacyReturn>(`/pharmacy/v1/returns/${encodeURIComponent(id)}/cancel`, {
    method: "PUT",
  });
}

// ========================================================================
//  013 护理站申领（药房审批 → 预留 → 确认调拨 → 双仓转移）
// ========================================================================

export interface PharmacyRequisitionItem {
  id: string;
  requisition_id: string;
  material_id: string;
  requested_quantity: DecimalText | null;
  approved_quantity: DecimalText | null;
  dispensed_quantity: DecimalText | null;
  lot_id: string | null;
  outbound_stock_operation_detail_id: string | null;
  inbound_stock_operation_detail_id: string | null;
  /** 历史 V300 记录兼容读取；013 新记录不回填 */
  stock_operation_detail_id: string | null;
}

export type PharmacyRequisitionStatus = "DRAFT" | "APPROVED" | "DISPENSED" | "CANCELLED";

export interface PharmacyRequisition {
  id: string;
  requisition_no: string;
  warehouse: string;
  destination_warehouse: string | null;
  department: string | null;
  status: PharmacyRequisitionStatus;
  requester: string | null;
  requester_id: string | null;
  created_at: string | null;
  approved_by: string | null;
  approved_at: string | null;
  dispensed_by: string | null;
  dispensed_at: string | null;
  cancelled_by: string | null;
  cancelled_at: string | null;
  cancel_reason: string | null;
  updated_at: string | null;
  items?: PharmacyRequisitionItem[];
}

export interface PharmacyRequisitionList {
  records: PharmacyRequisition[];
  meta: { total: number };
}

export interface PharmacyRequisitionCreateItemInput {
  material_id: string;
  requested_quantity: DecimalText;
}

export interface PharmacyRequisitionCreateInput {
  warehouse: string;
  destination_warehouse: string;
  department: string;
  items: PharmacyRequisitionCreateItemInput[];
}

export interface PharmacyRequisitionApproveItemInput {
  id: string;
  approved_quantity: DecimalText;
  lot_id: string | null;
}

/**
 * 新建护理站申领（DRAFT）。要求 `Idempotency-Key` 请求头；同一键、同一内容
 * 重试返回原单据（200），同一键、不同内容返回 409。
 */
export function createPharmacyRequisition(
  input: PharmacyRequisitionCreateInput,
  idempotencyKey: string,
): Promise<PharmacyRequisition> {
  return request<PharmacyRequisition>(`/pharmacy/v1/requisitions`, {
    method: "POST",
    body: JSON.stringify(input),
    headers: { "Idempotency-Key": idempotencyKey },
  });
}

export function listPharmacyRequisitions(params: {
  warehouse?: string;
  destination_warehouse?: string;
  department?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PharmacyRequisitionList> {
  const query = new URLSearchParams();
  if (params.warehouse) query.set("warehouse", params.warehouse);
  if (params.destination_warehouse) query.set("destination_warehouse", params.destination_warehouse);
  if (params.department) query.set("department", params.department);
  if (params.status) query.set("status", params.status);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<PharmacyRequisitionList>(`/pharmacy/v1/requisitions${qs ? `?${qs}` : ""}`);
}

export function getPharmacyRequisition(id: string): Promise<PharmacyRequisition> {
  return request<PharmacyRequisition>(`/pharmacy/v1/requisitions/${encodeURIComponent(id)}`);
}

/**
 * 审批：必须覆盖全部明细。批准数量 ≥ 0；批次物资必须给批号；审批在药房
 * 源仓库锁定等额库存（locked_quantity）。已审批单据只允许相同审批集合重放。
 */
export function approvePharmacyRequisition(
  id: string,
  items: PharmacyRequisitionApproveItemInput[],
): Promise<PharmacyRequisition> {
  return request<PharmacyRequisition>(`/pharmacy/v1/requisitions/${encodeURIComponent(id)}/approve`, {
    method: "PUT",
    body: JSON.stringify({ items }),
  });
}

/** 确认调拨：只触发已审批预留的物理转移（药房出库 + 护理站入库）；DISPENSED 重试返回原结果 */
export function dispensePharmacyRequisition(id: string): Promise<PharmacyRequisition> {
  return request<PharmacyRequisition>(`/pharmacy/v1/requisitions/${encodeURIComponent(id)}/dispense`, {
    method: "PUT",
    body: JSON.stringify({}),
  });
}

/** 取消：DRAFT 直接取消；APPROVED 先释放预留再取消；CANCELLED 重试返回原结果 */
export function cancelPharmacyRequisition(id: string, reason: string): Promise<PharmacyRequisition> {
  return request<PharmacyRequisition>(`/pharmacy/v1/requisitions/${encodeURIComponent(id)}/cancel`, {
    method: "PUT",
    body: JSON.stringify({ reason }),
  });
}

// ========================================================================
//  014 药房采购订单与供应商收货
//  状态机：DRAFT → APPROVED → PARTIALLY_RECEIVED → RECEIVED / CLOSED / CANCELLED
//  创建与收货要求 `Idempotency-Key`；同一键、同一内容重试返回原单据（200），
//  同一键、不同内容返回 409。收货结果同时返回收货凭证与订单最新进度。
// ========================================================================

export type PharmacyPurchaseOrderStatus =
  | "DRAFT"
  | "APPROVED"
  | "PARTIALLY_RECEIVED"
  | "RECEIVED"
  | "CLOSED"
  | "CANCELLED";

export interface PharmacyPurchaseOrderItem {
  id: string;
  purchase_order_id: string;
  material_id: string;
  ordered_quantity: DecimalText;
  received_quantity: DecimalText;
  remaining_quantity: DecimalText;
}

export interface PharmacyPurchaseOrderReceiptSummary {
  id: string;
  receipt_no: string;
  received_by: string;
  received_at: string;
  stock_operation_id: string;
  created_at: string;
}

export interface PharmacyPurchaseOrder {
  id: string;
  purchase_order_no: string;
  warehouse: string;
  supplier_name: string;
  status: PharmacyPurchaseOrderStatus;
  requester_id: string;
  approved_by: string | null;
  approved_at: string | null;
  cancelled_by: string | null;
  cancelled_at: string | null;
  cancel_reason: string | null;
  closed_by: string | null;
  closed_at: string | null;
  close_reason: string | null;
  created_at: string;
  updated_at: string;
  items: PharmacyPurchaseOrderItem[];
  receipts?: PharmacyPurchaseOrderReceiptSummary[];
}

export interface PharmacyPurchaseOrderList {
  records: PharmacyPurchaseOrder[];
  meta: { total: number };
}

export interface PharmacyPurchaseOrderCreateItemInput {
  material_id: string;
  ordered_quantity: DecimalText;
}

export interface PharmacyPurchaseOrderCreateInput {
  warehouse: string;
  supplier_name: string;
  items: PharmacyPurchaseOrderCreateItemInput[];
}

export interface PharmacyPurchaseReceiptLineInput {
  purchase_order_item_id: string;
  received_quantity: DecimalText;
  batch_no?: string | null;
  production_date?: string | null;
  expiry_date?: string | null;
  manufacturer?: string | null;
  unit_cost: DecimalText;
}

export interface PharmacyPurchaseReceiptCreateInput {
  items: PharmacyPurchaseReceiptLineInput[];
}

export interface PharmacyPurchaseReceiptItem {
  id: string;
  receipt_id: string;
  purchase_order_item_id: string;
  material_id: string;
  lot_id: string | null;
  received_quantity: DecimalText;
  unit_cost: DecimalText;
  total_cost: DecimalText;
  stock_operation_detail_id: string;
}

export interface PharmacyPurchaseReceipt {
  id: string;
  receipt_no: string;
  purchase_order_id: string;
  warehouse: string;
  supplier_name: string;
  received_by: string;
  received_at: string;
  stock_operation_id: string;
  created_at: string;
  items: PharmacyPurchaseReceiptItem[];
  order?: PharmacyPurchaseOrder;
}

/**
 * 新建采购订单（DRAFT）。要求 `Idempotency-Key` 请求头；同一键、同一内容
 * 重试返回原订单（200），同一键、不同内容返回 409。
 */
export function createPharmacyPurchaseOrder(
  input: PharmacyPurchaseOrderCreateInput,
  idempotencyKey: string,
): Promise<PharmacyPurchaseOrder> {
  return request<PharmacyPurchaseOrder>(`/pharmacy/v1/purchase-orders`, {
    method: "POST",
    body: JSON.stringify(input),
    headers: { "Idempotency-Key": idempotencyKey },
  });
}

/** 编辑草稿：仅 DRAFT 可编辑；完整替换明细 */
export function updatePharmacyPurchaseOrder(
  id: string,
  input: PharmacyPurchaseOrderCreateInput,
): Promise<PharmacyPurchaseOrder> {
  return request<PharmacyPurchaseOrder>(
    `/pharmacy/v1/purchase-orders/${encodeURIComponent(id)}`,
    { method: "PUT", body: JSON.stringify(input) },
  );
}

export function listPharmacyPurchaseOrders(params: {
  warehouse?: string;
  supplier_name?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PharmacyPurchaseOrderList> {
  const query = new URLSearchParams();
  if (params.warehouse) query.set("warehouse", params.warehouse);
  if (params.supplier_name) query.set("supplier_name", params.supplier_name);
  if (params.status) query.set("status", params.status);
  if (params.limit != null) query.set("limit", String(params.limit));
  if (params.offset != null) query.set("offset", String(params.offset));
  const qs = query.toString();
  return request<PharmacyPurchaseOrderList>(`/pharmacy/v1/purchase-orders${qs ? `?${qs}` : ""}`);
}

export function getPharmacyPurchaseOrder(id: string): Promise<PharmacyPurchaseOrder> {
  return request<PharmacyPurchaseOrder>(`/pharmacy/v1/purchase-orders/${encodeURIComponent(id)}`);
}

/** 审核：DRAFT → APPROVED；同一用户重复审核返回原订单 */
export function approvePharmacyPurchaseOrder(id: string): Promise<PharmacyPurchaseOrder> {
  return request<PharmacyPurchaseOrder>(
    `/pharmacy/v1/purchase-orders/${encodeURIComponent(id)}/approve`,
    { method: "PUT", body: JSON.stringify({}) },
  );
}

/** 取消：DRAFT 或零收货 APPROVED 可取消；有收货须改为关闭 */
export function cancelPharmacyPurchaseOrder(id: string, reason: string): Promise<PharmacyPurchaseOrder> {
  return request<PharmacyPurchaseOrder>(
    `/pharmacy/v1/purchase-orders/${encodeURIComponent(id)}/cancel`,
    { method: "PUT", body: JSON.stringify({ reason }) },
  );
}

/** 关闭：有剩余数量的 APPROVED/PARTIALLY_RECEIVED 关闭余量 */
export function closePharmacyPurchaseOrder(id: string, reason: string): Promise<PharmacyPurchaseOrder> {
  return request<PharmacyPurchaseOrder>(
    `/pharmacy/v1/purchase-orders/${encodeURIComponent(id)}/close`,
    { method: "PUT", body: JSON.stringify({ reason }) },
  );
}

/**
 * 供应商收货：一次到货写一张 `PHARMACY_PURCHASE_RECEIPT` 库存 INBOUND 操作，
 * 原子更新库存、成本、收货凭证与订单进度。要求 `Idempotency-Key`；重放返回
 * 原凭证（200）。返回值含 `order` 字段反映收货后的订单状态。
 */
export function receivePharmacyPurchaseOrder(
  id: string,
  input: PharmacyPurchaseReceiptCreateInput,
  idempotencyKey: string,
): Promise<PharmacyPurchaseReceipt> {
  return request<PharmacyPurchaseReceipt>(
    `/pharmacy/v1/purchase-orders/${encodeURIComponent(id)}/receipts`,
    {
      method: "POST",
      body: JSON.stringify(input),
      headers: { "Idempotency-Key": idempotencyKey },
    },
  );
}

export function getPharmacyPurchaseReceipt(id: string): Promise<PharmacyPurchaseReceipt> {
  return request<PharmacyPurchaseReceipt>(`/pharmacy/v1/purchase-receipts/${encodeURIComponent(id)}`);
}

// ─── 随访管理 (Followup · healthcare) ────────────────────────────────────
// 养老/福利院方向：离院长者回访 + 在院慢病长者定期随访。
// 业务枚举（中文值）：随访类型 出院后随访/慢病随访/常规电话随访；
// 方式 电话/上门/门诊；结果 正常/异常/需复访/需转诊；状态 待随访/已完成/已取消
// （「已逾期」由查询计算：待随访且计划日早于今天，不落库）。

/** 生命体征测量值（JSONB vitals），字段由前端表单固定 */
export interface FollowupVitals {
  systolic?: number | null;
  diastolic?: number | null;
  heart_rate?: number | null;
  blood_glucose?: number | null;
  temperature?: number | null;
}

export interface FollowupPlan {
  id: string;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string;
  encounter_no: string | null;
  followup_type: string;
  planned_date: string;
  planned_way: string;
  assignee: string;
  /** 待随访/已完成/已取消；待随访且计划日早于今天时返回 已逾期（查询计算） */
  status: "待随访" | "已完成" | "已取消" | "已逾期";
  completed_at: string | null;
  cancel_reason: string | null;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface FollowupPlanList {
  records: FollowupPlan[];
  meta: { total: number };
}

export interface FollowupRecord {
  id: string;
  plan_id: string | null;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string;
  encounter_no: string | null;
  followup_type: string;
  followup_way: string;
  followup_date: string;
  contact_object: string | null;
  condition_summary: string | null;
  vitals: FollowupVitals | null;
  guidance: string | null;
  result: string;
  next_followup_date: string | null;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface FollowupRecordList {
  records: FollowupRecord[];
  meta: { total: number };
}

export interface FollowupPlanStats {
  today_pending: number;
  overdue: number;
  month_completed: number;
}

export interface FollowupPatientTimeline {
  plans: FollowupPlan[];
  records: FollowupRecord[];
}

export interface FollowupPlanInput {
  patient_id: string;
  encounter_id: string;
  followup_type: string;
  planned_date: string;
  planned_way?: string;
  remark?: string;
  metadata?: Record<string, unknown>;
}

export interface FollowupRecordInput {
  plan_id?: string | null;
  patient_id: string;
  encounter_id: string;
  followup_type: string;
  followup_way?: string;
  followup_date?: string;
  contact_object?: string;
  condition_summary?: string;
  vitals?: FollowupVitals;
  guidance?: string;
  result: string;
  next_followup_date?: string;
  metadata?: Record<string, unknown>;
}

/** 计划状态流转：已完成 须带 record 或 record_id；已取消 必须带 cancel_reason */
export interface FollowupPlanStatusInput {
  status: "已完成" | "已取消";
  record_id?: string;
  record?: Omit<FollowupRecordInput, "plan_id" | "patient_id" | "encounter_id">;
  cancel_reason?: string;
}

/** 顶部概览统计 */
export function getFollowupPlanStats(): Promise<FollowupPlanStats> {
  return request<FollowupPlanStats>("/healthcare/v1/followup-plans/stats");
}

export function listFollowupPlans(params: {
  status?: string;
  followup_type?: string;
  patient_id?: string;
  date_from?: string;
  date_to?: string;
  overdue?: boolean;
  limit?: number;
  offset?: number;
} = {}): Promise<FollowupPlanList> {
  const query = new URLSearchParams();
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.followup_type?.trim()) query.set("followup_type", params.followup_type.trim());
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.date_from?.trim()) query.set("date_from", params.date_from.trim());
  if (params.date_to?.trim()) query.set("date_to", params.date_to.trim());
  if (params.overdue) query.set("overdue", "true");
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<FollowupPlanList>(`/healthcare/v1/followup-plans${suffix}`);
}

export function getFollowupPlan(id: string): Promise<FollowupPlan> {
  return request<FollowupPlan>(`/healthcare/v1/followup-plans/${encodeURIComponent(id)}`);
}

export function createFollowupPlan(input: FollowupPlanInput): Promise<FollowupPlan> {
  return request<FollowupPlan>("/healthcare/v1/followup-plans", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateFollowupPlanStatus(id: string, input: FollowupPlanStatusInput): Promise<FollowupPlan> {
  return request<FollowupPlan>(`/healthcare/v1/followup-plans/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

export function listFollowupRecords(params: {
  patient_id?: string;
  encounter_id?: string;
  followup_type?: string;
  result?: string;
  date_from?: string;
  date_to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<FollowupRecordList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.encounter_id?.trim()) query.set("encounter_id", params.encounter_id.trim());
  if (params.followup_type?.trim()) query.set("followup_type", params.followup_type.trim());
  if (params.result?.trim()) query.set("result", params.result.trim());
  if (params.date_from?.trim()) query.set("date_from", params.date_from.trim());
  if (params.date_to?.trim()) query.set("date_to", params.date_to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<FollowupRecordList>(`/healthcare/v1/followup-records${suffix}`);
}

export function getFollowupRecord(id: string): Promise<FollowupRecord> {
  return request<FollowupRecord>(`/healthcare/v1/followup-records/${encodeURIComponent(id)}`);
}

/** 新增随访记录；带 plan_id 时服务端同一事务内将计划置为已完成 */
export function createFollowupRecord(input: FollowupRecordInput): Promise<FollowupRecord> {
  return request<FollowupRecord>("/healthcare/v1/followup-records", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 长者随访历史时间线（详情页用）：plans 与 records 各按时间倒序 */
export function listPatientFollowups(patientId: string): Promise<FollowupPatientTimeline> {
  return request<FollowupPatientTimeline>(`/healthcare/v1/patients/${encodeURIComponent(patientId)}/followups`);
}

// ─── 生命体征 (Vital Signs · healthcare) ─────────────────────────────────
// 养老方向：入住长者日常健康监测。体征类型为英文枚举，前端展示映射中文；
// 血压按收缩/舒张两条独立记录建模（一次录入提交两条）；abnormal 由服务端
// 按内置参考范围计算（WEIGHT 不判异常）；删除为软删除（数据可追溯）。

export type VitalSignType =
  | "TEMPERATURE"
  | "PULSE"
  | "RESPIRATION"
  | "SYSTOLIC_BP"
  | "DIASTOLIC_BP"
  | "SPO2"
  | "BLOOD_GLUCOSE"
  | "WEIGHT";

export interface VitalSignRecord {
  id: string;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string | null;
  encounter_no: string | null;
  type: VitalSignType;
  value: number;
  unit: string;
  measured_at: string;
  recorded_by: string;
  /** 超参考范围标记，服务端计算（客户端不可提交） */
  abnormal: boolean;
  note: string | null;
  metadata: Record<string, unknown> | null;
  /** 异常处理状态：待复核/已确认/已误报/已转诊（服务端管控） */
  review_status: VitalSignReviewStatus;
  /** 复核结论：确认异常/误报，未复核为 null */
  review_result: VitalSignReviewResult | null;
  /** 复核备注 */
  review_note: string | null;
  /** 复核人（认证主体） */
  reviewed_by: string | null;
  /** 复核时间 */
  reviewed_at: string | null;
  created_at: string;
  updated_at: string;
}

export type VitalSignReviewStatus = "待复核" | "已确认" | "已误报" | "已转诊";
export type VitalSignReviewResult = "确认异常" | "误报";

export interface VitalSignInput {
  patient_id: string;
  encounter_id?: string | null;
  type: VitalSignType;
  value: number;
  unit?: string;
  measured_at?: string;
  note?: string;
  metadata?: Record<string, unknown>;
}

/** 修正：value 必填；unit/measured_at 省略保留原值；note/metadata 传 null 清空 */
export interface VitalSignUpdateInput {
  value: number;
  unit?: string;
  measured_at?: string;
  note?: string | null;
  metadata?: Record<string, unknown> | null;
}

export interface VitalSignList {
  records: VitalSignRecord[];
  meta: { total: number };
}

export interface VitalSignSnapshot {
  records: VitalSignRecord[];
}

export interface VitalSignTrend {
  records: VitalSignRecord[];
}

/** 按长者查询体征记录（时间倒序，可分页，按类型/时间范围过滤） */
export function listVitalSigns(params: {
  patient_id: string;
  type?: string;
  date_from?: string;
  date_to?: string;
  limit?: number;
  offset?: number;
}): Promise<VitalSignList> {
  const query = new URLSearchParams();
  query.set("patient_id", params.patient_id);
  if (params.type?.trim()) query.set("type", params.type.trim());
  if (params.date_from?.trim()) query.set("date_from", params.date_from.trim());
  if (params.date_to?.trim()) query.set("date_to", params.date_to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<VitalSignList>(`/healthcare/v1/vital-signs?${query.toString()}`);
}

/** 批量创建体征记录：请求体为数组（血压一次提交收缩/舒张两条），返回创建结果 */
export function createVitalSigns(inputs: VitalSignInput[]): Promise<VitalSignSnapshot> {
  return request<VitalSignSnapshot>("/healthcare/v1/vital-signs", {
    method: "POST",
    body: JSON.stringify(inputs),
  });
}

export function getVitalSign(id: string): Promise<VitalSignRecord> {
  return request<VitalSignRecord>(`/healthcare/v1/vital-signs/${encodeURIComponent(id)}`);
}

/** 修正体征记录（改值/时间/备注），abnormal 由服务端重算 */
export function updateVitalSign(id: string, input: VitalSignUpdateInput): Promise<VitalSignRecord> {
  return request<VitalSignRecord>(`/healthcare/v1/vital-signs/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** 软删除体征记录（置 deleted_at，可追溯），返回 {id, deleted_at} */
export function deleteVitalSign(id: string): Promise<{ id: string; deleted_at: string }> {
  return request<{ id: string; deleted_at: string }>(`/healthcare/v1/vital-signs/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

/** 最新体征快照：每种类型最近一条（首页/详情卡片用） */
export function getVitalSignSnapshot(patientId: string): Promise<VitalSignSnapshot> {
  return request<VitalSignSnapshot>(
    `/healthcare/v1/patients/${encodeURIComponent(patientId)}/vital-signs/snapshot`,
  );
}

/** 趋势序列：指定长者、指定体征类型在一段时间内的测量点（时间升序，供绘图） */
export function getVitalSignTrend(
  patientId: string,
  type: VitalSignType,
  params: { date_from?: string; date_to?: string } = {},
): Promise<VitalSignTrend> {
  const query = new URLSearchParams();
  query.set("type", type);
  if (params.date_from?.trim()) query.set("date_from", params.date_from.trim());
  if (params.date_to?.trim()) query.set("date_to", params.date_to.trim());
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<VitalSignTrend>(
    `/healthcare/v1/patients/${encodeURIComponent(patientId)}/vital-signs/trend${suffix}`,
  );
}

// ─── 体征异常告警闭环（复核 / 转诊）──────────────────────────────────────
// 状态机：待复核 ─复核→ 已确认 ─转诊→ 已转诊；待复核 ─复核→ 已误报（终态）。
// 复核可重复执行（覆盖式更新）；转诊在事务内创建随访计划（慢病随访/门诊），
// 计划 metadata 关联体征记录；修正记录导致 abnormal 翻转时状态自动重置。

export interface VitalSignAbnormalList {
  records: VitalSignRecord[];
  meta: { total: number };
}

export interface VitalSignAbnormalSummary {
  /** 待复核异常数 */
  pending_total: number;
  /** 今日新增异常数（按测量时间，业务时区当天） */
  today_total: number;
  /** 已转诊异常数 */
  referred_total: number;
  /** 各体征类型异常分布 */
  by_type: { type: VitalSignType; count: number }[];
  /** 各处理状态分布 */
  by_status: { status: VitalSignReviewStatus; count: number }[];
}

export interface VitalSignReviewInput {
  /** 复核结论：确认异常 / 误报 */
  result: VitalSignReviewResult;
  /** 复核备注（≤500 字） */
  note?: string;
}

export interface VitalSignReferInput {
  /** 计划随访日，缺省当天；不得早于入住开始日 */
  planned_date?: string;
  remark?: string;
}

export interface VitalSignReferResult {
  record: VitalSignRecord;
  followup_plan: FollowupPlan;
}

/** 跨长者异常列表：仅 abnormal=true 的记录（patient_id 可选过滤），按测量时间倒序 */
export function listAbnormalVitalSigns(params: {
  patient_id?: string;
  type?: VitalSignType;
  review_status?: VitalSignReviewStatus;
  date_from?: string;
  date_to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<VitalSignAbnormalList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.type) query.set("type", params.type);
  if (params.review_status) query.set("review_status", params.review_status);
  if (params.date_from?.trim()) query.set("date_from", params.date_from.trim());
  if (params.date_to?.trim()) query.set("date_to", params.date_to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<VitalSignAbnormalList>(`/healthcare/v1/vital-signs/abnormal${suffix}`);
}

/** 异常看板统计摘要：待复核/今日新增/已转诊 + 类型与状态分布 */
export function getAbnormalVitalSignSummary(): Promise<VitalSignAbnormalSummary> {
  return request<VitalSignAbnormalSummary>("/healthcare/v1/vital-signs/abnormal/summary");
}

/** 复核异常记录：待复核 → 已确认 | 已误报；复核人与时间由服务端留痕 */
export function reviewVitalSign(id: string, input: VitalSignReviewInput): Promise<VitalSignRecord> {
  return request<VitalSignRecord>(`/healthcare/v1/vital-signs/${encodeURIComponent(id)}/review`, {
    method: "POST",
    body: JSON.stringify({
      result: input.result,
      ...(input.note?.trim() ? { note: input.note.trim() } : {}),
    }),
  });
}

/** 转诊：已确认 → 已转诊，事务内创建随访计划（慢病随访/门诊），计划 metadata 关联体征记录 */
export function referVitalSign(id: string, input: VitalSignReferInput = {}): Promise<VitalSignReferResult> {
  const body: Record<string, string> = {};
  if (input.planned_date?.trim()) body.planned_date = input.planned_date.trim();
  if (input.remark?.trim()) body.remark = input.remark.trim();
  return request<VitalSignReferResult>(`/healthcare/v1/vital-signs/${encodeURIComponent(id)}/refer`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

// ─── 慢病档案管理 (Chronic Disease · healthcare) ───────────────────────────
// 养老方向：患者级、跨入住周期的长期慢病档案。
// 登记/随访完成自动生成「慢病随访」计划（metadata.chronic_disease_id 关联）。
// 业务枚举（中文值）：控制状态 良好/一般/较差/未控制；
// 随访频率 每月/每两月/每季度/每半年/每年；档案状态 管理中/已缓解/已停管。

export type ChronicControlStatus = "良好" | "一般" | "较差" | "未控制";
export type ChronicFollowupFrequency = "每月" | "每两月" | "每季度" | "每半年" | "每年";
export type ChronicRegistrationStatus = "管理中" | "已缓解" | "已停管";

export interface ChronicDiseaseRegistration {
  id: string;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string;
  encounter_no: string | null;
  disease_name: string;
  icd_code: string | null;
  confirmed_date: string;
  control_status: ChronicControlStatus;
  followup_frequency: ChronicFollowupFrequency;
  physician: string | null;
  remark: string | null;
  status: ChronicRegistrationStatus;
  metadata: Record<string, unknown> | null;
  /** 下次随访日：待随访计划最早日期（无则 null） */
  next_followup_date: string | null;
  /** 待随访计划已逾期（查询计算） */
  is_overdue: boolean;
  recent_followup_date: string | null;
  recent_followup_result: string | null;
  created_at: string;
  updated_at: string;
}

export interface ChronicDiseaseRegistrationList {
  records: ChronicDiseaseRegistration[];
  meta: { total: number };
}

/** 档案时间线：慢病病程记录 + 慢病随访计划/记录，各按时间倒序 */
export interface ChronicDiseaseTimeline {
  chronic_disease_id: string;
  progress_notes: ChronicProgressNote[];
  followup_plans: FollowupPlan[];
  followup_records: FollowupRecord[];
}

/** 慢病病程记录（progress_notes，note_type=CHRONIC） */
export interface ChronicProgressNote {
  id: string;
  encounter_id: string;
  note_type: string;
  content: string;
  physician: string | null;
  record_time: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface ChronicDiseaseRegistrationInput {
  patient_id: string;
  encounter_id: string;
  disease_name: string;
  icd_code?: string;
  confirmed_date: string;
  control_status?: ChronicControlStatus;
  followup_frequency?: ChronicFollowupFrequency;
  physician?: string;
  remark?: string;
  /** 可携带 diagnosis_id（一键带入当次入住诊断）与 followup_frequency 覆盖 */
  metadata?: Record<string, unknown>;
}

export interface ChronicDiseaseStatusInput {
  status: ChronicRegistrationStatus;
}

export function listChronicDiseases(params: {
  patient_id?: string;
  disease_name?: string;
  control_status?: ChronicControlStatus;
  status?: ChronicRegistrationStatus;
  limit?: number;
  offset?: number;
} = {}): Promise<ChronicDiseaseRegistrationList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.disease_name?.trim()) query.set("disease_name", params.disease_name.trim());
  if (params.control_status) query.set("control_status", params.control_status);
  if (params.status) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChronicDiseaseRegistrationList>(`/healthcare/v1/chronic-diseases${suffix}`);
}

export function getChronicDisease(id: string): Promise<ChronicDiseaseRegistration> {
  return request<ChronicDiseaseRegistration>(`/healthcare/v1/chronic-diseases/${encodeURIComponent(id)}`);
}

export function getChronicDiseaseTimeline(id: string): Promise<ChronicDiseaseTimeline> {
  return request<ChronicDiseaseTimeline>(`/healthcare/v1/chronic-diseases/${encodeURIComponent(id)}/timeline`);
}

/** 登记慢病档案；成功即自动生成首轮「慢病随访」计划（同事务） */
export function createChronicDisease(input: ChronicDiseaseRegistrationInput): Promise<ChronicDiseaseRegistration> {
  return request<ChronicDiseaseRegistration>("/healthcare/v1/chronic-diseases", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 档案状态流转：管理中 ↔ 已缓解/已停管；停管后停止自动生成新计划 */
export function updateChronicDiseaseStatus(id: string, input: ChronicDiseaseStatusInput): Promise<ChronicDiseaseRegistration> {
  return request<ChronicDiseaseRegistration>(`/healthcare/v1/chronic-diseases/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

// ========================================================================
//  Healthcare API — Health Checkup (体检管理)
//  机构年度体检常规（医疗/养老/儿保共用）：
//  批次（年度唯一）+ 参检名单快照 + 结果录入；异常项一键转体征/转随访。
// ========================================================================

export type HealthCheckupStatus = "草稿" | "进行中" | "已完成";

/** 体检批次 */
export interface HealthCheckup {
  id: string;
  checkup_year: number;
  name: string;
  status: HealthCheckupStatus;
  start_date: string | null;
  end_date: string | null;
  /** 创建人（认证主体，服务端写入） */
  operator: string;
  metadata: Record<string, unknown> | null;
  member_total: number;
  checked_total: number;
  created_at: string;
  updated_at: string;
}

export interface HealthCheckupList {
  records: HealthCheckup[];
  meta: { total: number };
}

/** 批次只读统计：应检/已检/完成率/异常汇总 */
export interface HealthCheckupStats {
  member_total: number;
  checked_total: number;
  /** 已检/应检 百分比（整数） */
  completion_rate: number;
  abnormal_total: number;
  vital_sign_total: number;
  followup_total: number;
}

export interface HealthCheckupInput {
  /** 业务年（2000–2100），同一年度仅允许一个批次（重复 409） */
  checkup_year: number;
  name: string;
  start_date?: string;
  end_date?: string;
  /** 创建时是否快照本机构在册人员，默认 true */
  snapshot?: boolean;
  metadata?: Record<string, unknown>;
}

/** 状态流转：草稿 → 进行中 → 已完成（单向，非法跳转 400） */
export interface HealthCheckupStatusInput {
  status: HealthCheckupStatus;
}

/** 参检名单快照成员 */
export interface HealthCheckupMember {
  id: string;
  checkup_id: string;
  patient_id: string;
  patient_name: string | null;
  /** 活动 ELDERLY_CARE/OUTPATIENT 周期锚点，可空 */
  encounter_id: string | null;
  encounter_no: string | null;
  checked: boolean;
  checked_at: string | null;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
  /** 补录时已存在成员（唯一索引幂等跳过）为 true */
  skipped?: boolean;
}

export interface HealthCheckupMemberList {
  records: HealthCheckupMember[];
  meta: { total: number };
}

export interface HealthCheckupMemberInput {
  patient_ids: string[];
}

export type HealthCheckupItemCategory = "数值" | "文本";

/** 体检结果：数值项自动判异常，文本项人工标记 */
export interface HealthCheckupResult {
  id: string;
  checkup_id: string;
  member_id: string;
  patient_id: string;
  patient_name: string | null;
  /** 项目名（中文），如 收缩压/空腹血糖/心电图 */
  item_name: string;
  item_category: HealthCheckupItemCategory;
  value: number | null;
  unit: string | null;
  text_value: string | null;
  ref_min: number | null;
  ref_max: number | null;
  /** 异常标记：数值项服务端按参考范围计算（含边界），文本项录入人标记 */
  abnormal: boolean;
  exam_date: string;
  operator: string;
  /** 非空表示已转体征（幂等，重复 409） */
  vital_sign_id: string | null;
  /** 非空表示已转随访（幂等，重复 409） */
  followup_plan_id: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface HealthCheckupResultList {
  records: HealthCheckupResult[];
  meta: { total: number };
}

export interface HealthCheckupResultInput {
  /** 必须属于批次参检名单 */
  patient_id: string;
  item_name: string;
  item_category: HealthCheckupItemCategory;
  /** 数值项必填；abnormal 由服务端计算，客户端不可提交 */
  value?: number;
  /** 数值项单位；命中内置映射时省略按类型默认，否则必填 */
  unit?: string;
  /** 文本项必填（心电图/胸透结论等） */
  text_value?: string;
  ref_min?: number;
  ref_max?: number;
  /** 文本项必填（人工标记异常）；数值项由服务端计算 */
  abnormal?: boolean;
  /** 体检日期，默认当天 */
  exam_date?: string;
  /** 可含 thresholds 覆盖参考范围 {"thresholds":{"SYSTOLIC_BP":{"min":..,"max":..}}} */
  metadata?: Record<string, unknown>;
}

/** 修正：数值项服务端重算 abnormal；文本项可改 text_value/abnormal */
export interface HealthCheckupResultPatchInput {
  value?: number;
  unit?: string;
  text_value?: string;
  abnormal?: boolean;
  ref_min?: number;
  ref_max?: number;
  exam_date?: string;
  metadata?: Record<string, unknown> | null;
}

/** 异常转体征生成的 vital_sign_records（快照，不随结果修正级联） */
export interface CheckupVitalSignConversion {
  id: string;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string | null;
  encounter_no: string | null;
  type: VitalSignType;
  value: number;
  unit: string;
  measured_at: string;
  recorded_by: string;
  abnormal: boolean;
  note: string | null;
  metadata: Record<string, unknown> | null;
  review_status: VitalSignReviewStatus;
  review_result: VitalSignReviewResult | null;
  review_note: string | null;
  reviewed_by: string | null;
  reviewed_at: string | null;
  created_at: string;
  updated_at: string;
}

/** 异常转随访生成的 followup_plans（锚定活动 encounter） */
export interface CheckupFollowupConversion {
  id: string;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string;
  encounter_no: string | null;
  followup_type: string;
  planned_date: string;
  planned_way: string;
  /** 责任人（认证主体，服务端写入） */
  assignee: string;
  status: "待随访";
  completed_at: string | null;
  cancel_reason: string | null;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ToFollowupInput {
  /** 慢病随访 / 常规电话随访 */
  followup_type: string;
  /** 默认体检日 + 7 天 */
  planned_date?: string;
  /** 电话 / 上门 / 门诊，默认电话 */
  planned_way?: string;
  remark?: string;
}

/** 批次列表（分页 + 状态筛选） */
export function listHealthCheckups(params: {
  status?: HealthCheckupStatus;
  limit?: number;
  offset?: number;
} = {}): Promise<HealthCheckupList> {
  const query = new URLSearchParams();
  if (params.status) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<HealthCheckupList>(`/healthcare/v1/health-checkups${suffix}`);
}

export function getHealthCheckup(id: string): Promise<HealthCheckup> {
  return request<HealthCheckup>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}`);
}

/** 创建批次：同一年度重复 409；snapshot=true 时同事务快照在册人员 */
export function createHealthCheckup(input: HealthCheckupInput): Promise<HealthCheckup> {
  return request<HealthCheckup>("/healthcare/v1/health-checkups", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 批次状态流转：草稿 → 进行中 → 已完成（单向） */
export function updateHealthCheckupStatus(
  id: string,
  input: HealthCheckupStatusInput,
): Promise<HealthCheckup> {
  return request<HealthCheckup>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** 批次只读统计 */
export function getHealthCheckupStats(id: string): Promise<HealthCheckupStats> {
  return request<HealthCheckupStats>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}/stats`);
}

/** 名单补录（幂等跳过已存在成员；已完成批次 400） */
export function addHealthCheckupMembers(
  id: string,
  input: HealthCheckupMemberInput,
): Promise<HealthCheckupMemberList> {
  return request<HealthCheckupMemberList>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}/members`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listHealthCheckupMembers(
  id: string,
  params: { checked?: boolean; limit?: number; offset?: number } = {},
): Promise<HealthCheckupMemberList> {
  const query = new URLSearchParams();
  if (params.checked !== undefined) query.set("checked", String(params.checked));
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<HealthCheckupMemberList>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}/members${suffix}`);
}

/** 结果录入：单条对象或数组批量；成功联动成员已检标记 */
export function createHealthCheckupResults(
  id: string,
  inputs: HealthCheckupResultInput | HealthCheckupResultInput[],
): Promise<HealthCheckupResultList> {
  return request<HealthCheckupResultList>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}/results`, {
    method: "POST",
    body: JSON.stringify(inputs),
  });
}

/** 结果列表：按异常/人员/类别筛选 */
export function listHealthCheckupResults(
  id: string,
  params: {
    abnormal?: boolean;
    patient_id?: string;
    item_category?: HealthCheckupItemCategory;
    limit?: number;
    offset?: number;
  } = {},
): Promise<HealthCheckupResultList> {
  const query = new URLSearchParams();
  if (params.abnormal !== undefined) query.set("abnormal", String(params.abnormal));
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.item_category) query.set("item_category", params.item_category);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<HealthCheckupResultList>(`/healthcare/v1/health-checkups/${encodeURIComponent(id)}/results${suffix}`);
}

export function getHealthCheckupResult(id: string): Promise<HealthCheckupResult> {
  return request<HealthCheckupResult>(`/healthcare/v1/health-checkup-results/${encodeURIComponent(id)}`);
}

/** 修正结果：数值项重算 abnormal；不级联已生成的体征/随访（快照） */
export function updateHealthCheckupResult(
  id: string,
  input: HealthCheckupResultPatchInput,
): Promise<HealthCheckupResult> {
  return request<HealthCheckupResult>(`/healthcare/v1/health-checkup-results/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** 异常转体征：仅 abnormal 数值项且命中内置映射；同项重复 409 */
export function convertCheckupResultToVitalSign(
  id: string,
): Promise<{ vital_sign: CheckupVitalSignConversion; result: HealthCheckupResult }> {
  return request<{ vital_sign: CheckupVitalSignConversion; result: HealthCheckupResult }>(
    `/healthcare/v1/health-checkup-results/${encodeURIComponent(id)}/to-vital-sign`,
    { method: "POST" },
  );
}

/** 异常转随访：锚定活动 encounter；同项重复 409；无活动锚点 409 */
export function convertCheckupResultToFollowup(
  id: string,
  input: ToFollowupInput,
): Promise<{ followup_plan: CheckupFollowupConversion; result: HealthCheckupResult }> {
  return request<{ followup_plan: CheckupFollowupConversion; result: HealthCheckupResult }>(
    `/healthcare/v1/health-checkup-results/${encodeURIComponent(id)}/to-followup`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

// ─── 膳食营养 (dining) ─────────────────────────────────────────────
// 长者餐食管理：饮食档案 → 菜品库 → 周菜谱 → 配餐名单 → 就餐登记 → 统计。
// 业务枚举一律中文值：餐食类型（普食/软食/碎食/流食/糖尿病餐）、
// 餐次（早餐/午餐/晚餐/加餐）、就餐状态（正常/部分/未就餐/拒食）等。

export interface DietProfile {
  id: string;
  patient_id: string;
  patient_name: string | null;
  encounter_id: string;
  meal_type: string;
  allergies: string[];
  portion_preference: string | null;
  remark: string | null;
  status: string;
  /** 关联入住状态：ACTIVE=在院 */
  encounter_status: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface DietProfileInput {
  patient_id: string;
  encounter_id: string;
  meal_type: string;
  allergies?: string[];
  portion_preference?: string;
  remark?: string;
  metadata?: Record<string, unknown>;
}

export interface DietProfileList {
  records: DietProfile[];
  meta: { total: number };
}

export interface Dish {
  id: string;
  name: string;
  category: string;
  meal_times: string[];
  diet_tags: string[];
  status: string;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface DishInput {
  name: string;
  category: string;
  meal_times?: string[];
  diet_tags?: string[];
  remark?: string;
  metadata?: Record<string, unknown>;
}

export interface DishList {
  records: Dish[];
  meta: { total: number };
}

export interface WeeklyMenuItem {
  id: string;
  menu_id: string;
  day_of_week: number;
  meal_time: string;
  dish_id: string;
  dish_name: string;
  sort_order: number;
}

export interface WeeklyMenu {
  id: string;
  week_start: string;
  name: string | null;
  status: string;
  remark: string | null;
  /** 列表接口返回的菜品项数；详情接口返回 items 时不重复统计 */
  item_count?: number;
  items?: WeeklyMenuItem[];
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface WeeklyMenuInput {
  week_start: string;
  name?: string;
  remark?: string;
  metadata?: Record<string, unknown>;
}

export interface WeeklyMenuList {
  records: WeeklyMenu[];
  meta: { total: number };
}

export interface MealExecution {
  id: string;
  roster_item_id: string;
  status: string;
  remark: string | null;
  recorded_by: string;
  recorded_at: string;
  /** 联查字段（list 接口） */
  menu_date?: string;
  meal_time?: string;
  patient_id?: string;
  patient_name?: string;
  meal_type?: string;
  allergies?: string[];
  adjust_type?: string | null;
}

export interface RosterItem {
  id: string;
  roster_id: string;
  patient_id: string;
  encounter_id: string | null;
  patient_name: string;
  meal_type: string;
  allergies: string[];
  source: "自动" | "手工";
  adjust_type: string | null;
  remark: string | null;
  sort_order: number;
  execution: MealExecution | null;
  created_at: string;
  updated_at: string;
}

export interface Roster {
  id: string;
  menu_date: string;
  meal_time: string;
  generated_by: string | null;
  generated_at: string | null;
  remark: string | null;
  /** 列表接口返回的条目数；详情接口返回 items 时不重复统计 */
  item_count?: number;
  items?: RosterItem[];
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface RosterList {
  records: Roster[];
  meta: { total: number };
}

export interface RosterGenerateResult {
  roster: Roster;
  created: number;
  updated: number;
  skipped: number;
  total: number;
}

export interface MealStatistics {
  date_from: string;
  date_to: string;
  summary: {
    expected_total: number;
    recorded_total: number;
    eaten_total: number;
    normal_total: number;
    partial_total: number;
    not_eaten_total: number;
    refused_total: number;
    not_expected_total: number;
    unrecorded_total: number;
    dining_rate: number | null;
  };
  by_status: Record<string, number>;
  by_meal: Array<{ meal_time: string; expected_total: number; recorded_total: number; eaten_total: number; dining_rate: number | null }>;
  by_date: Array<{ menu_date: string; expected_total: number; recorded_total: number; eaten_total: number; dining_rate: number | null }>;
}

export interface MealExecutionList {
  records: MealExecution[];
  meta: { total: number };
}

// ─── 长者饮食档案 ──────────────────────────────────────────────────

export function listDietProfiles(params: {
  patient_id?: string;
  encounter_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<DietProfileList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.encounter_id?.trim()) query.set("encounter_id", params.encounter_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<DietProfileList>(`/dining/v1/diet-profiles${suffix}`);
}

export function getDietProfile(id: string): Promise<DietProfile> {
  return request<DietProfile>(`/dining/v1/diet-profiles/${encodeURIComponent(id)}`);
}

export function createDietProfile(input: DietProfileInput): Promise<DietProfile> {
  return request<DietProfile>("/dining/v1/diet-profiles", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateDietProfile(id: string, input: Partial<DietProfileInput>): Promise<DietProfile> {
  return request<DietProfile>(`/dining/v1/diet-profiles/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function updateDietProfileStatus(id: string, status: "启用" | "停用"): Promise<DietProfile> {
  return request<DietProfile>(`/dining/v1/diet-profiles/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

export function deleteDietProfile(id: string): Promise<void> {
  return request<void>(`/dining/v1/diet-profiles/${encodeURIComponent(id)}`, { method: "DELETE" });
}

// ─── 菜品库 ────────────────────────────────────────────────────────

export function listDishes(params: {
  category?: string;
  status?: string;
  keyword?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<DishList> {
  const query = new URLSearchParams();
  if (params.category?.trim()) query.set("category", params.category.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.keyword?.trim()) query.set("keyword", params.keyword.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<DishList>(`/dining/v1/dishes${suffix}`);
}

export function createDish(input: DishInput): Promise<Dish> {
  return request<Dish>("/dining/v1/dishes", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateDish(id: string, input: Partial<DishInput>): Promise<Dish> {
  return request<Dish>(`/dining/v1/dishes/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function updateDishStatus(id: string, status: "启用" | "停用"): Promise<Dish> {
  return request<Dish>(`/dining/v1/dishes/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

// ─── 周菜谱 ────────────────────────────────────────────────────────

export function listWeeklyMenus(params: {
  week_start?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<WeeklyMenuList> {
  const query = new URLSearchParams();
  if (params.week_start?.trim()) query.set("week_start", params.week_start.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<WeeklyMenuList>(`/dining/v1/weekly-menus${suffix}`);
}

export function getWeeklyMenu(id: string): Promise<WeeklyMenu> {
  return request<WeeklyMenu>(`/dining/v1/weekly-menus/${encodeURIComponent(id)}`);
}

/** 取某日期所在周当前启用的菜谱（含明细）；无启用菜谱时 404 */
export function getWeeklyMenuByDate(date: string): Promise<WeeklyMenu> {
  return request<WeeklyMenu>(`/dining/v1/weekly-menus/by-date?date=${encodeURIComponent(date)}`);
}

export function createWeeklyMenu(input: WeeklyMenuInput): Promise<WeeklyMenu> {
  return request<WeeklyMenu>("/dining/v1/weekly-menus", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateWeeklyMenu(id: string, input: Partial<WeeklyMenuInput>): Promise<WeeklyMenu> {
  return request<WeeklyMenu>(`/dining/v1/weekly-menus/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

export function updateWeeklyMenuStatus(id: string, status: "启用" | "停用"): Promise<WeeklyMenu> {
  return request<WeeklyMenu>(`/dining/v1/weekly-menus/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

export function replaceWeeklyMenuItems(
  id: string,
  items: Array<{ day_of_week: number; meal_time: string; dish_id: string; sort_order?: number }>,
): Promise<WeeklyMenu> {
  return request<WeeklyMenu>(`/dining/v1/weekly-menus/${encodeURIComponent(id)}/items`, {
    method: "POST",
    body: JSON.stringify({ items }),
  });
}

/** 整周模板复制到目标周；目标周已有启用菜谱时 409 */
export function copyWeeklyMenu(id: string, weekStart: string): Promise<WeeklyMenu> {
  return request<WeeklyMenu>(`/dining/v1/weekly-menus/${encodeURIComponent(id)}/copy`, {
    method: "POST",
    body: JSON.stringify({ week_start: weekStart }),
  });
}

// ─── 配餐名单 ──────────────────────────────────────────────────────

export function generateRoster(input: { date: string; meal_time: string; remark?: string }): Promise<RosterGenerateResult> {
  return request<RosterGenerateResult>("/dining/v1/rosters/generate", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listRosters(params: {
  date?: string;
  meal_time?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<RosterList> {
  const query = new URLSearchParams();
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.meal_time?.trim()) query.set("meal_time", params.meal_time.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<RosterList>(`/dining/v1/rosters${suffix}`);
}

export function getRoster(id: string): Promise<Roster> {
  return request<Roster>(`/dining/v1/rosters/${encodeURIComponent(id)}`);
}

/** 手工调整：外出/请假（标记本餐不就餐）/临时加餐（新增） */
export function addRosterItem(
  rosterId: string,
  input: { patient_id: string; adjust_type: "外出" | "请假" | "临时加餐"; remark?: string },
): Promise<RosterItem> {
  return request<RosterItem>(`/dining/v1/rosters/${encodeURIComponent(rosterId)}/items`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function removeRosterItem(rosterId: string, itemId: string): Promise<void> {
  return request<void>(`/dining/v1/rosters/${encodeURIComponent(rosterId)}/items/${encodeURIComponent(itemId)}`, {
    method: "DELETE",
  });
}

// ─── 就餐执行登记 ──────────────────────────────────────────────────

/** 登记（幂等）：同一名单条目重复登记为更新，登记人与时间随之刷新 */
export function registerMealExecution(input: { roster_item_id: string; status: string; remark?: string }): Promise<MealExecution> {
  return request<MealExecution>("/dining/v1/executions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listMealExecutions(params: {
  date?: string;
  meal_time?: string;
  status?: string;
  patient_id?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<MealExecutionList> {
  const query = new URLSearchParams();
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.meal_time?.trim()) query.set("meal_time", params.meal_time.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<MealExecutionList>(`/dining/v1/executions${suffix}`);
}

// ─── 就餐统计 ──────────────────────────────────────────────────────

export function getMealStatistics(params: {
  date_from: string;
  date_to: string;
  meal_time?: string;
}): Promise<MealStatistics> {
  const query = new URLSearchParams();
  query.set("date_from", params.date_from);
  query.set("date_to", params.date_to);
  if (params.meal_time?.trim()) query.set("meal_time", params.meal_time.trim());
  return request<MealStatistics>(`/dining/v1/statistics/meals?${query.toString()}`);
}

// ========================================================================
//  Healthcare API — Deposit (押金登记与退押)
//  养老费用管理独立子任务：入住押金登记、退押与台账，挂 encounter
//  （不强制关联费用项目字典；核销由结算关账手工触发，见下方养老收费分组）。
//  退押为独立操作：不校验 encounter 关账状态，离院/去世后仍可退押。
// ========================================================================

/**
 * 押金台账记录类型：`登记` 与 `退押` 由押金管理页提交；
 * `核销` 由结算关账时的押金核销写入，不是用户可登记的类型。
 */
export type DepositType = "登记" | "退押" | "核销";

/** 押金台账记录（登记/退押/核销共用同表） */
export interface DepositRecord {
  id: string;
  encounter_id: string;
  type: DepositType;
  /** 发生金额（元），登记/退押/核销均为正数；余额 = Σ登记 − Σ退押 − Σ核销 */
  amount: number;
  /** 操作人（认证主体，服务端写入） */
  operator: string;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface DepositLedger {
  records: DepositRecord[];
  meta: { total: number; balance: number };
}

export interface DepositInput {
  /** 正数且至多两位小数（元），NUMERIC(12,2) 上限 */
  amount: number;
  remark?: string;
  metadata?: Record<string, unknown>;
}

/** 登记押金：encounter 必须存在（404）；缺必填/金额 ≤ 0 → 400 */
export function createDeposit(encounterId: string, input: DepositInput): Promise<DepositRecord> {
  return request<DepositRecord>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/deposits`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 退押：累计退押不得超过当前余额（400）；离院/去世后仍可退押 */
export function createDepositRefund(encounterId: string, input: DepositInput): Promise<DepositRecord> {
  return request<DepositRecord>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/deposits/refunds`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 按 encounter 查询押金台账（登记+退押+核销倒序分页）；meta.balance 为当前余额（已扣除核销） */
export function listDeposits(
  encounterId: string,
  params: { limit?: number; offset?: number } = {},
): Promise<DepositLedger> {
  const query = new URLSearchParams();
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<DepositLedger>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/deposits${suffix}`);
}

// ========================================================================
//  Healthcare API — 养老收费 (Fee Items / Bills / Payments / Settlement)
//  费用字典、账单（按月自动计费 + 手工加项）、缴费与欠费、结算关账入口，
//  路径 /healthcare/v1/*，沿用 request 封装（token 注入、JSON、401、{records,meta}）。
//  结算关账可选携带押金核销金额，核销由服务端同时写入缴费流水（method = 押金）
//  与押金台账（type = 核销）；押金登记/退押仍只在押金管理页。
// ========================================================================

// ─── 费用项目字典 (Fee Items) ────────────────────────────────────────

export type FeeItemCategory = "床位费" | "护理费" | "伙食费" | "个性化服务费" | "押金" | "其他";
export type FeeItemStatus = "启用" | "停用";

/**
 * 护理费绑定的护理等级枚举（计划 030 D1）：与护理评估表单的「结果等级」四值逐字一致。
 * 护理费**必须**绑定其中一个等级，计费按等级匹配（不再按名称匹配）；`name` 从此只是描述文本。
 */
export const NURSING_FEE_LEVELS = ["低风险", "中风险", "高风险", "无需干预"] as const;
export type NursingFeeLevel = (typeof NURSING_FEE_LEVELS)[number];

/** 费用项目字典条目；unit_price 为 NUMERIC(12,2) 数值（元） */
export interface FeeItem {
  id: string;
  category: FeeItemCategory;
  name: string;
  unit_price: number;
  status: FeeItemStatus;
  /** 仅护理费有值：绑定的护理等级（服务端字段，落 metadata.nursing_level） */
  nursing_level: NursingFeeLevel | string | null;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface FeeItemList {
  records: FeeItem[];
  meta: { total: number };
}

export interface FeeItemInput {
  category: FeeItemCategory;
  name: string;
  unit_price: number;
  /** 分类为「护理费」时必填；其它分类不得携带（服务端 400） */
  nursing_level?: NursingFeeLevel | string | null;
  remark?: string;
  metadata?: Record<string, unknown>;
}

/** 费用字典列表：category/status 过滤，created_at 倒序分页 */
export function listFeeItems(params: {
  category?: FeeItemCategory;
  status?: FeeItemStatus;
  limit?: number;
  offset?: number;
} = {}): Promise<FeeItemList> {
  const query = new URLSearchParams();
  if (params.category) query.set("category", params.category);
  if (params.status) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<FeeItemList>(`/healthcare/v1/fee-items${suffix}`);
}

export function getFeeItem(id: string): Promise<FeeItem> {
  return request<FeeItem>(`/healthcare/v1/fee-items/${encodeURIComponent(id)}`);
}

/** 创建费用项目：分类/名称/单价必填；状态默认 启用 */
export function createFeeItem(input: FeeItemInput): Promise<FeeItem> {
  return request<FeeItem>("/healthcare/v1/fee-items", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 全量更新字典字段（分类/名称/单价/备注/扩展）；状态只能走 updateFeeItemStatus */
export function updateFeeItem(id: string, input: FeeItemInput): Promise<FeeItem> {
  return request<FeeItem>(`/healthcare/v1/fee-items/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

/** 删除字典条目 */
export function deleteFeeItem(id: string): Promise<{ id: string }> {
  return request<{ id: string }>(`/healthcare/v1/fee-items/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

/** 启用/停用流转：非法状态值 400 */
export function updateFeeItemStatus(id: string, status: FeeItemStatus): Promise<FeeItem> {
  return request<FeeItem>(`/healthcare/v1/fee-items/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

// ─── 账单 (Bills) ────────────────────────────────────────────────────

export type BillStatus = "待缴费" | "已结清" | "已结算";
export type BillItemSource = "自动" | "手工";

/** 账单明细（字典快照：编码/名称/单价在生成时定格，字典改价不影响已生成账单） */
export interface BillItem {
  id: string;
  bill_id: string;
  source: BillItemSource;
  /** 来源字典项 ID（自动计费与手工加项均为字典项 ID） */
  item_code: string;
  item_name: string;
  unit_price: number;
  quantity: number;
  amount: number;
  remark: string | null;
  created_at: string;
  updated_at: string;
}

export interface Bill {
  id: string;
  encounter_id: string;
  period_start: string;
  period_end: string;
  status: BillStatus;
  /** 已结清/已结算时间；列表接口不返回该字段 */
  settled_at?: string | null;
  total_amount: number;
  /**
   * 关账（结算关账）时刻该账单的未结余额快照：`0` 表示关账时已收妥或已被押金核销；
   * 历史（V519 之前）关账的账单不回填，恒为 `0`。关账前该值为 `0`。
   */
  outstanding_amount: number;
  /**
   * 关账时未结余额被放弃（减免）的原因，仅当 `outstanding_amount > 0` 时非空；
   * 由关账请求的 `write_off_reason` 原样写入，冻结后不可再改。
   */
  write_off_reason: string | null;
  /** 仅详情接口返回（列表接口为空数组） */
  items?: BillItem[];
  /**
   * 红冲（034 A2 反向单）字段，列表与详情接口一并返回：
   *
   * - 本单是**红字单**时：`reversal_of` 指向被冲销的原单，原因/操作人/时刻三列恒为 `null`；
   * - 本单是**已红冲的原单**时：`reversal_reason` / `reversed_by` / `reversed_at` 为红冲留痕，
   *   `reversal_of` 恒为 `null`（原单金额与状态保留不变，不删除、不重算）；
   * - 普通账单：五项全为 `null`。
   */
  reversal_of: string | null;
  reversal_reason: string | null;
  reversed_by: string | null;
  reversed_at: string | null;
  /** 本单是「已红冲的原单」时指向冲销它的红字单（服务端 `LEFT JOIN` 计算，只读）；其余为 `null` */
  reversal_bill_id: string | null;
  created_at: string;
  updated_at: string;
}

export interface BillList {
  records: Bill[];
  meta: { total: number };
}

export interface BillItemInput {
  /** 费用字典项 ID（须启用，停用项 400） */
  item_id: string;
  /** 覆盖字典单价（可选，正数且至多两位小数） */
  unit_price?: number;
  /** 数量（正数，默认 1） */
  quantity?: number;
  remark?: string;
}

/** 生成账单：按月自动计费（床位/护理/伙食），账期裁剪到在院区间；同 encounter 同账期唯一（409） */
export function generateBill(encounterId: string, month: string): Promise<Bill> {
  return request<Bill>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/bills`, {
    method: "POST",
    body: JSON.stringify({ month }),
  });
}

/** 手工加项：unit_price 缺省取字典单价；加项后重算账单合计 */
export function addBillItem(billId: string, input: BillItemInput): Promise<Bill> {
  return request<Bill>(`/healthcare/v1/bills/${encodeURIComponent(billId)}/items`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/**
 * 红冲（034 A2）：为原单新建一张 `total_amount` 取反的红字单并建立双向关联，
 * 成功返回**新建的红字单**（201，与既有单条响应口径一致）；原单本身不改、不删，只写红冲留痕。
 *
 * 请求体白名单只有 `reason`：此处再 trim 一次（与 [settleEncounterBilling] 对 `write_off_reason`
 * 的处理一致），调用方须保证 trim 后非空且不超过 500 字符，否则服务端 400。
 *
 * 失败消息由服务端给出、前端经 `billingMessages` 中文化：404 原单不存在；
 * 400 目标本身是红字单或原因非法；409 原单已被红冲、原单不是「待缴费」、
 * 该 encounter 已关账、原单已有缴费记录。
 */
export function reverseBill(billId: string, reason: string): Promise<Bill> {
  return request<Bill>(`/healthcare/v1/bills/${encodeURIComponent(billId)}/reversal`, {
    method: "POST",
    body: JSON.stringify({ reason: reason.trim() }),
  });
}

/** 账单详情（含明细快照） */
export function getBill(billId: string): Promise<Bill> {
  return request<Bill>(`/healthcare/v1/bills/${encodeURIComponent(billId)}`);
}

/** 按 encounter 查询账单列表（账期倒序分页，不含明细） */
export function listBills(
  encounterId: string,
  params: { limit?: number; offset?: number } = {},
): Promise<BillList> {
  const query = new URLSearchParams();
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<BillList>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/bills${suffix}`);
}

// ─── 生成账单前置校验 (Bill Precheck) ─────────────────────────────────

/**
 * 前置校验的单个费用项目槽位：`category` + `level`（仅护理费有意义）唯一确定一个槽位。
 * `satisfied` 由服务端按「启用条数恰好为 1」判定，前端不得重算。
 */
export interface BillPrecheckRequirement {
  category: FeeItemCategory;
  /** 仅护理费有意义：本账期生效的护理评估结果等级 */
  level: string | null;
  /** 该槽位在本账期是否真会被用到（如账期内无就餐记录时伙食费为 false） */
  required: boolean;
  enabled_count: number;
  satisfied: boolean;
}

/** 前置校验的机器可读阻塞原因；`null` 表示可生成 */
export type BillPrecheckBlockedBy =
  | "missing_fee_items"
  | "already_exists"
  | "not_overlapping"
  | "no_admit_date"
  /** 账期晚于机构时区当前月（031 W4 保守缺省：先住后收，不允许预生成未来账期） */
  | "future_month"
  | "settled"
  | "not_elderly_admission";

/**
 * 计费口径提示（计划 030 D8，机器可读原因码，中文文案由前端映射）。
 * 与 `blocked_by` 的区别：notices **不阻塞**生成，只是告知「本账期有一类费用不会计入」。
 */
export interface BillPrecheckNotice {
  /** 例如 `nursing_fee_not_billed_no_assessment` / `meal_fee_not_billed_no_dining_record` */
  code: string;
  /** 涉及的费用分类（如 护理费/伙食费）；无归属时为 null */
  category: string | null;
  /** 护理费口径下涉及的护理等级；其它口径为 null */
  level: string | null;
}

/** 生成账单前置校验结果（只读，可反复调用） */
export interface BillPrecheck {
  month: string;
  /** 账期裁剪到在院区间后的起止日（缺失时为 null） */
  period_start: string | null;
  period_end: string | null;
  can_generate: boolean;
  blocked_by: BillPrecheckBlockedBy | null;
  requirements: BillPrecheckRequirement[];
  /** 计费口径提示：恒为数组，无提示时 `[]` */
  notices: BillPrecheckNotice[];
}

/**
 * 生成账单前置校验（只读）：服务端判定能否生成与缺哪些费用项目槽位。
 * 缺字典属被查询的业务状态，仍返回 200（阻塞原因见 `blocked_by`）。
 */
export function precheckBillGeneration(encounterId: string, month: string): Promise<BillPrecheck> {
  const query = new URLSearchParams({ month });
  return request<BillPrecheck>(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/bills/precheck?${query.toString()}`,
  );
}

/**
 * 结算关账（已离院/去世未结算的养老入住）：生成区间最终账单并冻结全部账单，返回关账后的 encounter。
 *
 * 押金核销（`depositOffset`）与未结判定、减免留痕都在**同一事务内**完成：服务端先生成区间最终账单
 * （以 `待缴费` 建立、同样可被核销），再用押金按 `min(押金余额, 未结合计)` 的上限核销。
 *
 * 存在未结余额而未提供 `writeOffReason` 时整个关账被拒（409
 * `unsettled bills require explicit write-off: outstanding <金额>`），且不产生任何写入；
 * 提供原因后，核销后仍未结的部分随关账冻结、**不可再收**，并写入账单的 `outstanding_amount`
 * 与 `write_off_reason`。
 *
 * - `depositOffset`：缺省或 `0` 表示不核销（该键不发送）；
 * - `writeOffReason`：缺省或 trim 后为空表示不提供（该键不发送），非空时发送 trim 后的原文。
 */
export function settleEncounterBilling(
  encounterId: string,
  options: { depositOffset?: number; writeOffReason?: string } = {},
): Promise<Encounter> {
  const { depositOffset } = options;
  const writeOffReason = options.writeOffReason?.trim();
  const body: { deposit_offset?: number; write_off_reason?: string } = {};
  if (depositOffset !== undefined && depositOffset !== 0) body.deposit_offset = depositOffset;
  if (writeOffReason) body.write_off_reason = writeOffReason;
  return request<Encounter>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/billing-settlement`, {
    method: "POST",
    body: JSON.stringify(body),
  });
}

// ─── 结算关账预览 (Billing Settlement Preview) ────────────────────────

/** 预览里的区间最终账单账期（与关账实际生成的账期同源） */
export interface SettlementPreviewPeriod {
  start: string;
  end: string;
}

/**
 * 结算关账预览：提交前「会发生什么」的权威口径（只读、可反复调用，不产生任何写入）。
 *
 * 口径关系：
 * - `pending_balance`：既有 `待缴费` 账单的未结合计（**核销前**）；
 * - `outstanding_total`：`pending_balance + final_bill_total`；
 * - `max_offset`：`min(deposit_balance, outstanding_total)`，即本次核销上限；
 * - `requires_write_off`：`outstanding_total > max_offset`，即「即使全额核销仍有未结」。
 */
export interface SettlementPreview {
  /** `null` 表示本次关账不生成区间最终账单 */
  settlement_period: SettlementPreviewPeriod | null;
  final_bill_total: number;
  pending_balance: number;
  outstanding_total: number;
  deposit_balance: number;
  max_offset: number;
  requires_write_off: boolean;
}

/**
 * 结算关账预览（只读）：关账前展示最终账单金额、押金余额、可核销上限与核销后仍需减免的金额。
 * 资格不满足时与关账一致返回 400/404/409；需认证，未认证 401。
 */
export function previewEncounterBilling(encounterId: string): Promise<SettlementPreview> {
  return request<SettlementPreview>(
    `/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/billing-settlement/preview`,
  );
}

// ─── 缴费与欠费 (Payments / Arrears / Summary) ───────────────────────

/** 客户端可提交的缴费方式（5 种）；与 POST /bills/:id/payments 的服务端白名单一致，提交「押金」会 400 */
export type PaymentMethod = "现金" | "转账" | "银行卡" | "微信" | "支付宝";

/**
 * 缴费流水记录的读取类型：含服务端在结算核销时写入的 `押金`。
 * 客户端不可提交「押金」，提交会 400；展示缴费流水用它，提交缴费用 PaymentMethod。
 */
export type PaymentRecordMethod = PaymentMethod | "押金";

/** 缴费流水记录；operator 由服务端写入认证主体 */
export interface Payment {
  id: string;
  bill_id: string;
  amount: number;
  method: PaymentRecordMethod;
  operator: string;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface PaymentList {
  records: Payment[];
  meta: { total: number };
}

export interface PaymentInput {
  amount: number;
  method: PaymentMethod;
  remark?: string;
  metadata?: Record<string, unknown>;
}

/** 缴费：多次部分缴费累加，余额递减；单笔不得使累计缴费超过账单合计（超缴 400）；余额归零账单转 已结清 */
export function createPayment(billId: string, input: PaymentInput): Promise<Payment> {
  return request<Payment>(`/healthcare/v1/bills/${encodeURIComponent(billId)}/payments`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 按账单查询缴费流水（倒序分页） */
export function listPayments(
  billId: string,
  params: { limit?: number; offset?: number } = {},
): Promise<PaymentList> {
  const query = new URLSearchParams();
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<PaymentList>(`/healthcare/v1/bills/${encodeURIComponent(billId)}/payments${suffix}`);
}

/** 欠费账单（状态 待缴费 且 余额 > 0），账期倒序分页；id 为账单 ID */
export interface Arrear {
  id: string;
  encounter_id: string;
  period_start: string;
  period_end: string;
  status: BillStatus;
  total_amount: number;
  paid_amount: number;
  balance: number;
  created_at: string;
  updated_at: string;
}

export interface ArrearsList {
  records: Arrear[];
  meta: { total: number };
}

/**
 * 欠费列表：`encounter_id` 可选，传入时只返回该入住的欠费行，
 * 且 `records` 与 `meta.total` 同源过滤（分页总数不再是全局数）；省略时行为与全局列表一致。
 */
export function listArrears(
  params: { encounter_id?: string; limit?: number; offset?: number } = {},
): Promise<ArrearsList> {
  const query = new URLSearchParams();
  if (params.encounter_id) query.set("encounter_id", params.encounter_id);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ArrearsList>(`/healthcare/v1/payments/arrears${suffix}`);
}

/**
 * 收费汇总：三项既有口径互不重叠，恒等式 `应缴 − 已缴 = 欠费` 在关账前后均成立。
 *
 * - `due_amount` = Σ账单合计（应缴）；
 * - `paid_amount` = Σ缴费金额（已缴，含关账核销写入的 `method = 押金` 行）；
 * - `arrears_amount` = Σ(`待缴费` 且余额 > 0 的账单余额)（欠费，关账后恒为 0）；
 * - `write_off_amount` = Σ(已结算账单的 `outstanding_amount`)（关账时被放弃的减免合计，
 *   是关账动作产生的第三项，**不并入欠费**）。
 */
export interface PaymentSummary {
  due_amount: number;
  paid_amount: number;
  arrears_amount: number;
  write_off_amount: number;
}

export function getPaymentSummary(): Promise<PaymentSummary> {
  return request<PaymentSummary>("/healthcare/v1/payments/summary");
}

// ============================================================================
//  052 儿保（儿童健康）日常功能链：体检 / 成长 / 接种 / 异常通报 / 指导
//  契约冻结见 docs/plans/052 §5；只增函数与类型，不改既有签名。
// ============================================================================

/** 儿保体检检查项（`items` 数组元素，结构随 UI 演化）。 */
export interface ChildHealthCheckupItem {
  item?: string;
  result?: string;
  conclusion?: string;
}

export interface ChildHealthCheckup {
  id: string;
  patient_id: string;
  patient_name: string | null;
  checkup_date: string;
  checkup_type: string;
  /** 体检时月龄，服务端按 birth_date 派生 */
  age_months: number | null;
  items: ChildHealthCheckupItem[] | null;
  conclusion: string | null;
  guidance: string | null;
  next_checkup_date: string | null;
  operator: string;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
  /** 053 B1：医生诊断（可空） */
  diagnosis?: string | null;
  /** 053 B1：诊断编码（可空） */
  diagnosis_code?: string | null;
  /** 053 B1：诊断状态 待诊断/已完成 */
  status?: string;
  /** 053 B1：诊断/审阅医生（服务端派生，客户端不得提交） */
  reviewed_by?: string | null;
  /** 053 B1：诊断时间（服务端派生） */
  reviewed_at?: string | null;
}

export interface ChildHealthCheckupList {
  records: ChildHealthCheckup[];
  meta: { total: number };
}

export interface ChildHealthCheckupInput {
  patient_id: string;
  checkup_date: string;
  checkup_type: string;
  items?: ChildHealthCheckupItem[];
  conclusion?: string | null;
  guidance?: string | null;
  next_checkup_date?: string | null;
  remark?: string | null;
}

export interface ChildHealthCheckupPatch {
  checkup_date?: string;
  checkup_type?: string;
  items?: ChildHealthCheckupItem[];
  conclusion?: string | null;
  guidance?: string | null;
  next_checkup_date?: string | null;
  remark?: string | null;
}

/** 体检类型白名单（与后端 `ChildHealthCheckupService.checkupTypes` 同源）。 */
export const CHILD_CHECKUP_TYPES = ["满月", "3月", "6月", "9月", "12月", "18月", "24月", "3岁", "常规"] as const;

/** 体检结论白名单（与后端 `conclusions` 同源）。 */
export const CHILD_CHECKUP_CONCLUSIONS = ["正常", "异常", "需转诊"] as const;

export function listChildHealthCheckups(
  params: { patient_id: string; from?: string; to?: string; limit?: number; offset?: number },
): Promise<ChildHealthCheckupList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.from) query.set("from", params.from);
  if (params.to) query.set("to", params.to);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildHealthCheckupList>(`/healthcare/v1/child-health-checkups?${query.toString()}`);
}

export function getChildHealthCheckup(id: string): Promise<ChildHealthCheckup> {
  return request<ChildHealthCheckup>(`/healthcare/v1/child-health-checkups/${encodeURIComponent(id)}`);
}

export function createChildHealthCheckup(input: ChildHealthCheckupInput): Promise<ChildHealthCheckup> {
  return request<ChildHealthCheckup>("/healthcare/v1/child-health-checkups", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateChildHealthCheckup(id: string, input: ChildHealthCheckupPatch): Promise<ChildHealthCheckup> {
  return request<ChildHealthCheckup>(`/healthcare/v1/child-health-checkups/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** 053 B1 体检诊断入参：署名 `reviewed_by` / 状态 `status` / 时间 `reviewed_at` 由服务端派生，客户端不得提交。 */
export interface ChildCheckupDiagnosisInput {
  diagnosis: string;
  diagnosis_code?: string;
}

export function diagnoseChildHealthCheckup(
  id: string,
  input: ChildCheckupDiagnosisInput,
): Promise<ChildHealthCheckup> {
  return request<ChildHealthCheckup>(`/healthcare/v1/child-health-checkups/${encodeURIComponent(id)}/diagnosis`, {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 落库时的评价快照（前端按 WHO LMS 计算后提交；服务端只校验形状并透传）。 */
export interface ChildGrowthPercentile {
  p?: number;
  z?: number;
}

export interface ChildGrowthMeasurement {
  id: string;
  patient_id: string;
  patient_name: string | null;
  measured_at: string;
  /** 测量时天龄，服务端按 birth_date 派生 */
  age_days: number | null;
  height_cm: number | null;
  weight_kg: number | null;
  head_circumference_cm: number | null;
  percentile_snapshot: Record<string, ChildGrowthPercentile> | null;
  operator: string;
  note: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildGrowthMeasurementList {
  records: ChildGrowthMeasurement[];
  meta: { total: number };
}

export interface ChildGrowthMeasurementInput {
  patient_id: string;
  measured_at?: string;
  height_cm?: number;
  weight_kg?: number;
  head_circumference_cm?: number;
  /** 体温：与成长测量同一次提交写入 vital_sign_records（052 裁定 7） */
  temperature?: number;
  percentile_snapshot?: Record<string, ChildGrowthPercentile>;
  note?: string;
}

export interface ChildGrowthMeasurementPatch {
  measured_at?: string;
  height_cm?: number;
  weight_kg?: number;
  head_circumference_cm?: number;
  percentile_snapshot?: Record<string, ChildGrowthPercentile>;
  note?: string;
}

export interface ChildGrowthVitalSignEcho {
  id: string;
  type: string;
  value: number;
  unit: string;
  abnormal: boolean;
}

export interface ChildGrowthCreateResult {
  measurement: ChildGrowthMeasurement;
  vital_sign: ChildGrowthVitalSignEcho | null;
}

/** 成长序列数据点（年龄轴，`age_days` 升序）。 */
export interface ChildGrowthSeriesPoint {
  id: string;
  age_days: number;
  value: number;
  measured_at: string;
  percentile?: ChildGrowthPercentile;
}

export type ChildGrowthMetric = "height" | "weight" | "head";

export interface ChildGrowthSeries {
  patient_id: string;
  patient_name: string | null;
  birth_date: string;
  age_days: number;
  series: Record<ChildGrowthMetric, ChildGrowthSeriesPoint[]>;
}

export function listChildGrowthMeasurements(
  params: { patient_id: string; metric?: ChildGrowthMetric; from?: string; to?: string; limit?: number; offset?: number },
): Promise<ChildGrowthMeasurementList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.metric) query.set("metric", params.metric);
  if (params.from) query.set("from", params.from);
  if (params.to) query.set("to", params.to);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildGrowthMeasurementList>(`/healthcare/v1/child-growth-measurements?${query.toString()}`);
}

export function getChildGrowthMeasurement(id: string): Promise<ChildGrowthMeasurement> {
  return request<ChildGrowthMeasurement>(`/healthcare/v1/child-growth-measurements/${encodeURIComponent(id)}`);
}

export function createChildGrowthMeasurement(input: ChildGrowthMeasurementInput): Promise<ChildGrowthCreateResult> {
  return request<ChildGrowthCreateResult>("/healthcare/v1/child-growth-measurements", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updateChildGrowthMeasurement(
  id: string,
  input: ChildGrowthMeasurementPatch,
): Promise<ChildGrowthMeasurement> {
  return request<ChildGrowthMeasurement>(`/healthcare/v1/child-growth-measurements/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

export function deleteChildGrowthMeasurement(id: string): Promise<{ id: string; deleted_at: string }> {
  return request<{ id: string; deleted_at: string }>(
    `/healthcare/v1/child-growth-measurements/${encodeURIComponent(id)}`,
    { method: "DELETE" },
  );
}

export function getChildGrowthSeries(patientId: string): Promise<ChildGrowthSeries> {
  return request<ChildGrowthSeries>(`/healthcare/v1/child-growth-series/${encodeURIComponent(patientId)}`);
}

/** 读时接种状态（后端 `ChildVaccinationService.statusOf` 的结论）。 */
export type ChildVaccinationStatus = "已接种" | "待接种" | "临期" | "逾期" | "暂缓" | "拒种";

export interface ChildVaccinationRecord {
  id: string;
  patient_id: string;
  patient_name: string | null;
  vaccine_code: string;
  vaccine_name: string;
  dose_no: number;
  administered_at: string | null;
  facility: string | null;
  batch_no: string | null;
  status: string;
  operator: string;
  note: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildVaccinationRecordList {
  records: ChildVaccinationRecord[];
  meta: { total: number };
}

export interface ChildVaccinationRecordInput {
  patient_id: string;
  vaccine_code: string;
  dose_no?: number;
  administered_at?: string | null;
  facility?: string | null;
  batch_no?: string | null;
  status?: string;
  note?: string | null;
}

/** 排程条目（后端 `VACCINATION_SCHEDULE` 的只读投影）。 */
export interface ChildVaccinationScheduleRow {
  vaccine_code: string;
  vaccine_name: string;
  dose_no: number;
  recommended_month: number;
  due_date: string;
  status: ChildVaccinationStatus;
  record_id: string | null;
  administered_at: string | null;
  facility: string | null;
  batch_no: string | null;
  note: string | null;
}

export interface ChildVaccinationStatusReport {
  patient_id: string;
  patient_name: string | null;
  birth_date: string;
  age_months: number;
  records: ChildVaccinationScheduleRow[];
  meta: {
    total: number;
    administered: number;
    pending: number;
    due_soon: number;
    overdue: number;
    deferred: number;
    refused: number;
  };
}

export interface ChildVaccinationRemindResult {
  patient_id: string;
  /** 逾期项生成的通报台账（含 message_id 与状态） */
  notified: ChildHealthNotification[];
  /** 临期项：只提示护士，不生成异常台账 */
  due_soon: ChildVaccinationScheduleRow[];
  meta: { notified: number; due_soon: number };
}

export function listChildVaccinationRecords(
  params: { patient_id: string; limit?: number; offset?: number },
): Promise<ChildVaccinationRecordList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildVaccinationRecordList>(`/healthcare/v1/child-vaccination-records?${query.toString()}`);
}

export function createChildVaccination(input: ChildVaccinationRecordInput): Promise<ChildVaccinationRecord> {
  return request<ChildVaccinationRecord>("/healthcare/v1/child-vaccinations", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function getChildVaccinationStatus(patientId: string): Promise<ChildVaccinationStatusReport> {
  return request<ChildVaccinationStatusReport>(
    `/healthcare/v1/child-vaccination-status/${encodeURIComponent(patientId)}`,
  );
}

export function remindChildVaccinations(
  patientId: string,
  input: { channel?: string } = {},
): Promise<ChildVaccinationRemindResult> {
  return request<ChildVaccinationRemindResult>(
    `/healthcare/v1/child-vaccination-status/${encodeURIComponent(patientId)}/remind`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

/** 异常类型白名单（与后端 `ChildAlertService.alertTypes` 同源）。 */
export const CHILD_ALERT_TYPES = [
  "体温异常",
  "心率异常",
  "呼吸异常",
  "生长异常",
  "接种逾期",
  "体检异常",
  "健康指导",
] as const;

/** 通报渠道白名单；MVP 只接入「站内」（Nexus message），其余记「待接入」。 */
export const CHILD_NOTIFICATION_CHANNELS = ["站内", "短信", "微信"] as const;

export interface ChildHealthAlert {
  alert_type: string;
  alert_ref: string | null;
  severity: string;
  title: string;
  detail: string | null;
  occurred_at: string;
  metric: string;
  value?: number;
  /** 待通报 / 已通报（依据本域台账是否已有同 alert_type + alert_ref 的通报） */
  status: string;
  patient_id: string;
  patient_name: string | null;
  guardian_name: string | null;
  guardian_phone: string | null;
}

export interface ChildHealthAlertList {
  records: ChildHealthAlert[];
  meta: { total: number };
}

export interface ChildHealthNotification {
  id: string;
  patient_id: string;
  patient_name: string | null;
  alert_type: string;
  alert_ref: string | null;
  guardian_name: string | null;
  guardian_phone: string | null;
  channel: string;
  content: string;
  message_id: string | null;
  /** 已发送 / 待接入 / 发送失败 */
  status: string;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface ChildHealthNotificationList {
  records: ChildHealthNotification[];
  meta: { total: number };
}

export interface ChildHealthNotificationInput {
  patient_id: string;
  alert_type: string;
  alert_ref?: string | null;
  guardian_name?: string | null;
  guardian_phone?: string | null;
  channel?: string;
  content: string;
}

export function listChildHealthAlerts(
  params: { patient_id: string; status?: string; limit?: number; offset?: number },
): Promise<ChildHealthAlertList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.status) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildHealthAlertList>(`/healthcare/v1/child-health-alerts?${query.toString()}`);
}

export function listChildHealthNotifications(
  params: { patient_id: string; limit?: number; offset?: number },
): Promise<ChildHealthNotificationList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildHealthNotificationList>(`/healthcare/v1/child-health-notifications?${query.toString()}`);
}

export function createChildHealthNotification(input: ChildHealthNotificationInput): Promise<ChildHealthNotification> {
  return request<ChildHealthNotification>("/healthcare/v1/child-health-notifications", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 指导模板（后端 `GUIDANCE_TEMPLATES` 常量）。 */
export interface ChildGuidanceTemplate {
  template_code: string;
  age_band: string;
  topic: string;
  /** 053 B3：指导口径（日常 / 疾病防控）。 */
  kind?: string;
  title: string;
  content: string;
}

export interface ChildGuidanceTemplateList {
  records: ChildGuidanceTemplate[];
  meta: { total: number; age_band: string | null };
}

export interface ChildGuidanceRecord {
  id: string;
  patient_id: string;
  patient_name: string | null;
  template_code: string;
  age_band: string | null;
  topic: string | null;
  /** 053 B3：指导口径（日常 / 疾病防控）。 */
  kind?: string;
  content: string;
  delivered_at: string;
  channel: string;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface ChildGuidanceRecordList {
  records: ChildGuidanceRecord[];
  meta: { total: number };
}

export interface ChildGuidanceRecordInput {
  patient_id: string;
  template_code: string;
  age_band?: string;
  topic?: string;
  /** 053 B3：指导口径（日常 / 疾病防控）；缺省由服务端按模板或「日常」推导。 */
  kind?: string;
  content?: string;
  delivered_at?: string;
  channel?: string;
}

export function listChildGuidanceTemplates(
  ageMonths?: number,
  kind?: string,
): Promise<ChildGuidanceTemplateList> {
  const query = new URLSearchParams();
  if (ageMonths !== undefined) query.set("age_months", String(ageMonths));
  if (kind !== undefined) query.set("kind", kind);
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildGuidanceTemplateList>(`/healthcare/v1/child-guidance-templates${suffix}`);
}

export function createChildGuidanceRecord(input: ChildGuidanceRecordInput): Promise<ChildGuidanceRecord> {
  return request<ChildGuidanceRecord>("/healthcare/v1/child-guidance-records", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildGuidanceRecords(
  params: { patient_id: string; limit?: number; offset?: number; kind?: string },
): Promise<ChildGuidanceRecordList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  if (params.kind !== undefined) query.set("kind", params.kind);
  return request<ChildGuidanceRecordList>(`/healthcare/v1/child-guidance-records?${query.toString()}`);
}

// ============================================================================
//  053 A2 发育筛查结果：POST/GET /child-developmental-screenings
//  契约冻结见 docs/plans/053 §5；只增函数与类型，不改既有签名。
//  判读/领域分等派生字段由服务端调 A1 评分引擎生成，客户端不得提交。
// ============================================================================

export interface ChildDevelopmentalScreening {
  id: string;
  patient_id: string;
  patient_name: string | null;
  scale_code: string;
  scale_version: string;
  /** 筛查时月龄，服务端按 birth_date 与 administered_at 派生 */
  age_months: number;
  administered_at: string;
  /** 条目编码 → 分值（按适龄条目顺序） */
  item_scores: Record<string, number>;
  /** 领域编码 → 得分，服务端派生 */
  domain_scores: Record<string, number>;
  /** 领域编码 → 该月龄适龄满分，服务端回显 */
  domain_max_scores: Record<string, number>;
  total_score: number;
  max_total_score: number;
  /** 判读：正常 / 可疑 / 异常 */
  classification: string;
  triggered_items: string[];
  conclusion: string | null;
  /** 关联体检记录 id（053 A3，可空） */
  checkup_id?: string | null;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildDevelopmentalScreeningList {
  records: ChildDevelopmentalScreening[];
  meta: { total: number };
}

export interface ChildDevelopmentalScreeningInput {
  patient_id: string;
  scale_code: string;
  administered_at?: string;
  item_scores: Record<string, number>;
  metadata?: Record<string, unknown>;
}

export function createChildDevelopmentalScreening(
  input: ChildDevelopmentalScreeningInput,
): Promise<ChildDevelopmentalScreening> {
  return request<ChildDevelopmentalScreening>("/healthcare/v1/child-developmental-screenings", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildDevelopmentalScreenings(
  params: { patient_id: string; limit?: number; offset?: number },
): Promise<ChildDevelopmentalScreeningList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildDevelopmentalScreeningList>(
    `/healthcare/v1/child-developmental-screenings?${query.toString()}`,
  );
}

export function getChildDevelopmentalScreening(id: string): Promise<ChildDevelopmentalScreening> {
  return request<ChildDevelopmentalScreening>(
    `/healthcare/v1/child-developmental-screenings/${encodeURIComponent(id)}`,
  );
}

/**
 * 053 A3 筛查结论判读入参：`conclusion` 必填，`checkup_id` 可选（关联体检记录）。
 * 判读后若 `classification` 为「可疑/异常」，可携带筛查 `id` 调
 * `createChildReferral({ source_type: "筛查", source_id: id, ... })` 完成一键转诊。
 */
export interface ChildScreeningConclusionInput {
  conclusion: string;
  checkup_id?: string;
}

/** 053 A3：医生写筛查结论判读（`clinical:write`，覆盖式，返回更新后的筛查记录）。 */
export function concludeChildDevelopmentalScreening(
  id: string,
  input: ChildScreeningConclusionInput,
): Promise<ChildDevelopmentalScreening> {
  return request<ChildDevelopmentalScreening>(
    `/healthcare/v1/child-developmental-screenings/${encodeURIComponent(id)}/conclusion`,
    {
      method: "POST",
      body: JSON.stringify(input),
    },
  );
}

// ============================================================================
//  053 B2 健康问题转诊：POST/GET /child-referrals、PATCH /child-referrals/:id/status
//  契约冻结见 docs/plans/053 §5；只增函数与类型，不改既有签名。
//  状态机由服务端白名单管控（待转诊 → 已转诊 → 已完成，+已取消）；operator 服务端派生。
// ============================================================================

export interface ChildReferral {
  id: string;
  patient_id: string;
  patient_name: string | null;
  /** 来源：体检 / 筛查 / 异常 / 手动 */
  source_type: string;
  source_id: string | null;
  problem_summary: string;
  /** 转诊类型：院内转诊 / 转上级医院 / 专科转诊 */
  referral_type: string;
  target_org: string | null;
  target_dept: string | null;
  /** 紧急度：常规 / 急 */
  urgency: string;
  /** 状态：待转诊 / 已转诊 / 已完成 / 已取消 */
  status: string;
  referred_at: string | null;
  outcome: string | null;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildReferralList {
  records: ChildReferral[];
  meta: { total: number };
}

export interface ChildReferralInput {
  patient_id: string;
  source_type: string;
  source_id?: string;
  problem_summary: string;
  referral_type: string;
  target_org?: string;
  target_dept?: string;
  urgency?: string;
  metadata?: Record<string, unknown>;
}

export interface ChildReferralStatusInput {
  status: string;
  outcome?: string;
  referred_at?: string;
  metadata?: Record<string, unknown>;
}

export function createChildReferral(input: ChildReferralInput): Promise<ChildReferral> {
  return request<ChildReferral>("/healthcare/v1/child-referrals", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildReferrals(
  params: { patient_id: string; status?: string; limit?: number; offset?: number },
): Promise<ChildReferralList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.status !== undefined) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<ChildReferralList>(`/healthcare/v1/child-referrals?${query.toString()}`);
}

export function updateChildReferralStatus(
  id: string,
  input: ChildReferralStatusInput,
): Promise<ChildReferral> {
  return request<ChildReferral>(
    `/healthcare/v1/child-referrals/${encodeURIComponent(id)}/status`,
    { method: "PATCH", body: JSON.stringify(input) },
  );
}

// ============================================================================
//  053 C1 儿童健康报告（只读聚合）：GET /child-health-reports/:patient_id
//  契约冻结见 docs/plans/053 §5；只增函数与类型，不改既有签名。
//  patient 段不含身份证号（053 §3 Dh）；过敏段读遗留 patients.allergies JSONB（D1 落地前）。
// ============================================================================

/** 报告患者段：最小 PHI（**不含身份证号**）。 */
export interface ChildHealthReportPatient {
  id: string;
  name: string | null;
  gender: string | null;
  birth_date: string | null;
  /** 由 birth_date 与业务今天派生；birth_date 为空或异常时为 null */
  age_months: number | null;
  /** 患者域：居民 / 长者 / 儿童 */
  person_type: string;
}

/** 报告档案段（child_health_profiles 全列）。 */
export interface ChildHealthReportProfile {
  id: string;
  patient_id: string;
  guardian_name: string | null;
  guardian_relationship: string | null;
  guardian_phone: string | null;
  birth_weight_g: number | null;
  birth_height_mm: number | null;
  delivery_mode: string | null;
  feeding_method: string | null;
  vaccination_summary: string | null;
  vaccination_records: VaccinationRecord[] | null;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string | null;
  updated_at: string | null;
}

/** 报告体检段（含 053 B1 诊断字段；患者段单独给出，故无 patient_name）。 */
export type ChildHealthReportCheckup = Omit<ChildHealthCheckup, "patient_name">;

/** 报告成长段（含 percentile_snapshot 与 deleted_at）。 */
export type ChildHealthReportGrowth = Omit<ChildGrowthMeasurement, "patient_name"> & {
  deleted_at: string | null;
};

/** 报告接种记录段。 */
export type ChildHealthReportVaccinationRecord = Omit<ChildVaccinationRecord, "patient_name">;

/** 报告接种排程条目（读时状态；不含 record_id 等记录回显）。 */
export type ChildHealthReportScheduleRow = Omit<
  ChildVaccinationScheduleRow,
  "record_id" | "administered_at" | "facility" | "batch_no" | "note"
>;

/** 接种汇总（按读时状态计数，口径与 052 一致）。 */
export interface ChildHealthReportVaccinationSummary {
  total: number;
  已接种: number;
  待接种: number;
  临期: number;
  逾期: number;
  暂缓: number;
  拒种: number;
}

export interface ChildHealthReportVaccinations {
  records: ChildHealthReportVaccinationRecord[];
  schedule: ChildHealthReportScheduleRow[];
  summary: ChildHealthReportVaccinationSummary;
}

/** 报告筛查段（含 053 A3 conclusion / checkup_id）。 */
export type ChildHealthReportScreening = Omit<ChildDevelopmentalScreening, "patient_name">;

/** 报告转诊段。 */
export type ChildHealthReportReferral = Omit<ChildReferral, "patient_name">;

/** 报告指导段（含 053 B3 kind）。 */
export type ChildHealthReportGuidance = Omit<ChildGuidanceRecord, "patient_name">;

/** 报告过敏段：遗留 patients.allergies JSONB（035 形状；D1 落地前不改读规范化表）。 */
export interface ChildHealthReportAllergy {
  allergen?: string;
  reaction?: string;
}

export interface ChildHealthReportMeta {
  generated_at: string;
  counts: {
    checkups: number;
    growth: number;
    screenings: number;
    referrals: number;
    guidance: number;
    allergies: number;
  };
}

export interface ChildHealthReport {
  patient: ChildHealthReportPatient;
  profile: ChildHealthReportProfile | null;
  checkups: ChildHealthReportCheckup[];
  growth: ChildHealthReportGrowth[];
  vaccinations: ChildHealthReportVaccinations;
  screenings: ChildHealthReportScreening[];
  referrals: ChildHealthReportReferral[];
  guidance: ChildHealthReportGuidance[];
  allergies: ChildHealthReportAllergy[];
  meta: ChildHealthReportMeta;
}

/** 053 C1：儿童健康报告（只读聚合；读开放）。 */
export function getChildHealthReport(patientId: string): Promise<ChildHealthReport> {
  return request<ChildHealthReport>(
    `/healthcare/v1/child-health-reports/${encodeURIComponent(patientId)}`,
  );
}

// ============================================================================
//  053 D1 药物过敏史规范化：POST/GET /patient-allergies、PATCH/DELETE /patient-allergies/:id
//  契约冻结见 docs/plans/053 §5；只增函数与类型，不改既有签名。
//  枚举（药物/食物/其他、一般/严重、有效/已停用）由服务端白名单校验；operator 服务端派生；
//  删除为软删（status → 已停用）。GET 读开放；POST/PATCH/DELETE 受 clinical:write。
// ============================================================================

export interface PatientAllergy {
  id: string;
  patient_id: string;
  patient_name: string | null;
  /** 过敏原类型：药物 / 食物 / 其他 */
  allergen_type: string;
  allergen_name: string;
  allergen_code: string | null;
  reaction: string | null;
  /** 严重度：一般 / 严重 */
  severity: string;
  /** 状态：有效 / 已停用 */
  status: string;
  operator: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface PatientAllergyList {
  records: PatientAllergy[];
  meta: { total: number };
}

export interface PatientAllergyInput {
  patient_id: string;
  /** 药物 / 食物 / 其他 */
  allergen_type: string;
  allergen_name: string;
  allergen_code?: string;
  reaction?: string;
  /** 一般 / 严重，缺省「一般」 */
  severity?: string;
  metadata?: Record<string, unknown>;
}

export interface PatientAllergyPatch {
  allergen_type?: string;
  allergen_name?: string;
  allergen_code?: string;
  reaction?: string;
  severity?: string;
  /** 有效 / 已停用 */
  status?: string;
  metadata?: Record<string, unknown>;
}

export function listPatientAllergies(
  params: { patient_id: string; status?: string; limit?: number; offset?: number },
): Promise<PatientAllergyList> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.status !== undefined) query.set("status", params.status);
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  return request<PatientAllergyList>(`/healthcare/v1/patient-allergies?${query.toString()}`);
}

export function createPatientAllergy(input: PatientAllergyInput): Promise<PatientAllergy> {
  return request<PatientAllergy>("/healthcare/v1/patient-allergies", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function updatePatientAllergy(id: string, input: PatientAllergyPatch): Promise<PatientAllergy> {
  return request<PatientAllergy>(`/healthcare/v1/patient-allergies/${encodeURIComponent(id)}`, {
    method: "PATCH",
    body: JSON.stringify(input),
  });
}

/** 软删除：置 status='已停用'，返回更新后的单条（含 {id, status}）。 */
export function deletePatientAllergy(id: string): Promise<PatientAllergy> {
  return request<PatientAllergy>(`/healthcare/v1/patient-allergies/${encodeURIComponent(id)}`, {
    method: "DELETE",
  });
}

// ============================================================================
//  053 D3 开嘱前药物过敏检查：GET /drug-allergy-check?patient_id=&material_id=
//  只读（读开放，046 D7）。目录校验与开嘱同口径（端口未接线 503 / 物资不存在 404 /
//  非药品或非启用 409）；缺参 400。warnings 分级（一般/严重），severe 表示存在严重提醒。
//  开嘱时严重项需在请求体顶层带 acknowledged_warnings（matched 字符串数组）二次确认。
// ============================================================================

export interface DrugAllergyWarning {
  /** 严重度：一般 / 严重 */
  severity: string;
  reason: string;
  /** 来源：过敏史 / 交叉反应 / 年龄禁忌 */
  source: string;
  /** 命中的过敏原或规则关键字（脱敏） */
  matched: string;
}

export interface DrugAllergyCheck {
  patient_id: string;
  material_id: string;
  drug_name: string | null;
  drug_code: string | null;
  warnings: DrugAllergyWarning[];
  /** 是否存在严重提醒（true 时开嘱需 acknowledged_warnings 二次确认） */
  severe: boolean;
}

export function checkDrugAllergy(patientId: string, materialId: string): Promise<DrugAllergyCheck> {
  const query = new URLSearchParams({ patient_id: patientId, material_id: materialId });
  return request<DrugAllergyCheck>(`/healthcare/v1/drug-allergy-check?${query.toString()}`);
}

// ============================================================================
//  054 A 链（dining）— 营养师工作台
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.1；
//  形状以 A1–A5 已交付实现为准（比 §5.7 的节选多出 `patient_name` / `band` /
//  `unmatched` / `reference_version` 等服务端派生字段，UI 必须按实际响应取用）。
//  本段只追加，不改既有签名；写路由受 `dining:manage`（A6 页面按此显隐），
//  读路由（列表/分析/周报查看）不上门禁（046 D7）。
// ============================================================================

// ─── 054 A1：每餐膳食摄入记录（POST 同儿童同餐次幂等覆盖 / GET 列表）─────────

export interface MealIntakeItem {
  food_name: string;
  amount_g: number;
  note?: string;
}

export interface MealIntakeRecord {
  id: string;
  patient_id: string;
  patient_name: string;
  roster_item_id: string | null;
  intake_date: string;
  meal_time: string;
  items: MealIntakeItem[];
  /** 0–1 的摄入比例（缺省 1） */
  intake_ratio: number;
  /** 以下五项一律服务端按「食物项 × 菜品营养 × 摄入比例」汇总，客户端提交即忽略 */
  energy_kcal: number;
  protein_g: number;
  fat_g: number;
  carb_g: number;
  sodium_mg: number;
  operator: string;
  remark: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface MealIntakeRecordList {
  records: MealIntakeRecord[];
  meta: { total: number };
}

export interface MealIntakeInput {
  patient_id: string;
  intake_date: string;
  /** 早餐 / 午餐 / 晚餐 / 加餐（服务端白名单） */
  meal_time: string;
  items: MealIntakeItem[];
  intake_ratio?: number;
  roster_item_id?: string | null;
  remark?: string | null;
  metadata?: Record<string, unknown>;
}

/** 登记摄入；同一儿童 + 同一日期 + 同一餐次重复提交为覆盖更新（幂等）。 */
export function createMealIntakeRecord(input: MealIntakeInput): Promise<MealIntakeRecord> {
  return request<MealIntakeRecord>("/dining/v1/meal-intake-records", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listMealIntakeRecords(params: {
  patient_id?: string;
  date?: string;
  meal_time?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<MealIntakeRecordList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.meal_time?.trim()) query.set("meal_time", params.meal_time.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<MealIntakeRecordList>(`/dining/v1/meal-intake-records${suffix}`);
}

// ─── 054 A2/A3：营养分析（读时聚合；结论与缺口项由服务端派生）─────────────────

export interface NutritionAnalysisBand {
  code: string;
  label: string;
}

/** 该月龄段的每日参考摄入量（内置常量口径，见 `reference_version`） */
export interface NutritionAnalysisReference {
  energy_kcal: number;
  protein_g: number;
  fat_g: number;
  carb_g: number;
  sodium_mg: number;
}

export interface NutritionAnalysisItem {
  food_name: string;
  amount_g: number;
  energy_kcal: number;
  /** 是否命中成分来源（菜品营养优先、内置常量回落） */
  matched: boolean;
}

export interface NutritionAnalysis {
  patient_id: string;
  patient_name: string;
  intake_date: string;
  /** 缺省餐次时服务端返回「全部」 */
  meal_time: string;
  age_months: number;
  band: NutritionAnalysisBand;
  energy_kcal: number;
  protein_g: number;
  fat_g: number;
  carb_g: number;
  sodium_mg: number;
  reference: NutritionAnalysisReference;
  /** 服务端派生结论（客户端不得提交/覆盖） */
  conclusion: string;
  gaps: string[];
  items: NutritionAnalysisItem[];
  /** 未命中成分的食物名（计 0，不报错） */
  unmatched: string[];
  /** 参考值常量版本（口径标注，必须随结论一起展示） */
  reference_version: string;
}

/** 读时聚合分析；当天无摄入记录返回零值结论，不 500。 */
export function getNutritionAnalysis(params: {
  patient_id: string;
  date?: string;
  meal_time?: string;
}): Promise<NutritionAnalysis> {
  const query = new URLSearchParams({ patient_id: params.patient_id });
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.meal_time?.trim()) query.set("meal_time", params.meal_time.trim());
  return request<NutritionAnalysis>(`/dining/v1/nutrition-analysis?${query.toString()}`);
}

// ─── 054 A4：过敏原标记（覆盖式写 dining_diet_profiles.allergies）+ 特殊膳食申请 ─

/**
 * 覆盖式写饮食档案的过敏原（复用既有 `allergies` 列，不新建过敏主数据表）。
 * 请求体字段名是 `allergies`（与档案列同口径），不是 `allergens`。
 */
export function updateDietProfileAllergens(id: string, allergies: string[]): Promise<DietProfile> {
  return request<DietProfile>(`/dining/v1/diet-profiles/${encodeURIComponent(id)}/allergens`, {
    method: "PUT",
    body: JSON.stringify({ allergies }),
  });
}

/**
 * 054 A4b：**按 patient 维度**覆盖式写过敏原
 * （`PUT /dining/v1/diet-profiles/by-patient/:patient_id/allergens`）。
 *
 * - 儿童（`patients.person_type='儿童'`）：允许无入住 → 服务端 upsert 最小启用档案
 *   （`encounter_id = NULL`、`meal_type` 缺省「普食」）并写 `allergies`；
 * - 非儿童：只覆盖更新其既有启用档案，没有启用档案 → **400**（服务端文案，如
 *   `非儿童必须先建立饮食档案（需在院入住）` / `encounter_id is required`），
 *   调用方必须原样展示，不得吞错；
 * - 请求体字段名是 `allergies`（与档案列同口径）；**覆盖式：整体替换，不是追加**，
 *   传空数组即清空全部过敏原标记。
 */
export function upsertDietProfileAllergensByPatient(
  patientId: string,
  allergies: string[],
  options: { meal_type?: string } = {},
): Promise<DietProfile> {
  const body: Record<string, unknown> = { allergies };
  if (options.meal_type?.trim()) body.meal_type = options.meal_type.trim();
  return request<DietProfile>(
    `/dining/v1/diet-profiles/by-patient/${encodeURIComponent(patientId)}/allergens`,
    {
      method: "PUT",
      body: JSON.stringify(body),
    },
  );
}

export interface SpecialDietRequest {
  id: string;
  patient_id: string;
  patient_name: string;
  /** 过敏回避 / 治疗膳食 / 其他 */
  request_type: string;
  meal_type: string;
  allergens: string[];
  reason: string | null;
  /** 待审批 / 已批准 / 已驳回 / 已终止 */
  status: string;
  requested_by: string;
  reviewed_by: string | null;
  reviewed_at: string | null;
  effective_from: string | null;
  effective_to: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface SpecialDietRequestList {
  records: SpecialDietRequest[];
  meta: { total: number };
}

export interface SpecialDietRequestInput {
  patient_id: string;
  request_type: string;
  meal_type: string;
  allergens?: string[];
  reason?: string | null;
  effective_from?: string | null;
  effective_to?: string | null;
  metadata?: Record<string, unknown>;
}

/** 创建特殊膳食申请；状态恒为「待审批」，申请人由服务端按会话派生。 */
export function createSpecialDietRequest(input: SpecialDietRequestInput): Promise<SpecialDietRequest> {
  return request<SpecialDietRequest>("/dining/v1/special-diet-requests", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listSpecialDietRequests(params: {
  patient_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<SpecialDietRequestList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<SpecialDietRequestList>(`/dining/v1/special-diet-requests${suffix}`);
}

/** 状态流转；非法流转服务端 400（合法目标见 `lib/nutrition.ts` 的状态机）。 */
export function updateSpecialDietRequestStatus(id: string, status: string): Promise<SpecialDietRequest> {
  return request<SpecialDietRequest>(`/dining/v1/special-diet-requests/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

// ─── 054 A5：营养周报（显式生成 + 快照；同儿童同周重复生成覆盖）──────────────

export interface NutritionWeeklyReportDaily {
  intake_date: string;
  energy_kcal: number;
  protein_g: number;
  fat_g: number;
  carb_g: number;
  sodium_mg: number;
  conclusion: string;
}

export interface NutritionWeeklyReport {
  id: string;
  patient_id: string;
  patient_name: string;
  /** 周一（`DiningConstants.weekStartOf` 口径） */
  week_start: string;
  /** 周日 */
  week_end: string;
  /** 当周有摄入记录的天数（均值为「按有记录天数」平均） */
  intake_days: number;
  energy_avg_kcal: number;
  protein_avg_g: number;
  fat_avg_g: number;
  carb_avg_g: number;
  sodium_avg_mg: number;
  conclusion: string;
  gaps: string[];
  daily: NutritionWeeklyReportDaily[];
  /** 参考值常量版本（快照落库，历史周报可追溯口径） */
  reference_version: string | null;
  generated_by: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface NutritionWeeklyReportList {
  records: NutritionWeeklyReport[];
  meta: { total: number };
}

/** 生成某儿童某周周报；`week_start` 缺省为当周，传非周一日期会被归一到该周周一。 */
export function generateNutritionWeeklyReport(input: {
  patient_id: string;
  week_start?: string;
}): Promise<NutritionWeeklyReport> {
  return request<NutritionWeeklyReport>("/dining/v1/nutrition-weekly-reports/generate", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listNutritionWeeklyReports(params: {
  patient_id?: string;
  week_start?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<NutritionWeeklyReportList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.week_start?.trim()) query.set("week_start", params.week_start.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<NutritionWeeklyReportList>(`/dining/v1/nutrition-weekly-reports${suffix}`);
}

// ============================================================================
//  054 B 链（nursing）— 康复师工作台
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.2；
//  形状以 B1–B4 已交付实现为准（`ChildRehabPeriodService` / `ChildRehabPlanService` /
//  `ChildRehabSessionService` / `ChildRehabMilestoneService` / `ChildRehabHandoutService`）。
//  写路由受 `rehab:execute`（B5 页面按此显隐），读路由不上门禁（046 D7）。
//  本段只追加，不改既有签名。
// ============================================================================

// ─── 054 B1：儿童康复服务周期 ──────────────────────────────────────────────

export interface ChildRehabPeriod {
  id: string;
  patient_id: string;
  patient_name: string;
  /** 恒为 `CHILD_REHAB`（服务端写入，客户端提交即忽略） */
  service_type: string;
  start_date: string;
  end_date: string | null;
  coordinator: string | null;
  /** ACTIVE / SUSPENDED / COMPLETED / CANCELLED */
  status: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildRehabPeriodList {
  records: ChildRehabPeriod[];
  meta: { total: number };
}

export interface ChildRehabPeriodInput {
  patient_id: string;
  start_date: string;
  end_date?: string | null;
  coordinator?: string | null;
  metadata?: Record<string, unknown>;
}

/** 创建康复周期；`encounter_id` 恒 NULL（儿童无入住），同儿童重复启用 → 409。 */
export function createChildRehabPeriod(input: ChildRehabPeriodInput): Promise<ChildRehabPeriod> {
  return request<ChildRehabPeriod>("/nursing/v1/child-rehab-periods", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildRehabPeriods(params: {
  patient_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildRehabPeriodList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildRehabPeriodList>(`/nursing/v1/child-rehab-periods${suffix}`);
}

/** 周期状态流转；非法流转 400，重新启用命中部分唯一索引 → 409。 */
export function updateChildRehabPeriodStatus(id: string, status: string): Promise<ChildRehabPeriod> {
  return request<ChildRehabPeriod>(`/nursing/v1/child-rehab-periods/${encodeURIComponent(id)}/status`, {
    method: "PATCH",
    body: JSON.stringify({ status }),
  });
}

// ─── 054 B1：训练计划与明细（复用 nursing_plans/plan_items）──────────────────

export interface ChildRehabPlanItem {
  id: string;
  plan_id: string;
  action: string;
  frequency_code: string | null;
  frequency_name: string | null;
  duration_days: number | null;
  remark: string | null;
  status: string;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface ChildRehabPlan {
  id: string;
  period_id: string;
  /** join 派生（`nursing_plans` 不加列） */
  patient_id: string;
  plan_name: string;
  goals: string | null;
  status: string;
  created_by: string | null;
  start_date: string | null;
  end_date: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
  items: ChildRehabPlanItem[];
}

export interface ChildRehabPlanList {
  records: ChildRehabPlan[];
  meta: { total: number };
}

export interface ChildRehabPlanItemInput {
  action: string;
  frequency_code?: string | null;
  frequency_name?: string | null;
  duration_days?: number | null;
  remark?: string | null;
}

export interface ChildRehabPlanInput {
  period_id: string;
  plan_name: string;
  goals?: string | null;
  start_date?: string | null;
  end_date?: string | null;
  items?: ChildRehabPlanItemInput[];
  metadata?: Record<string, unknown>;
}

export function createChildRehabPlan(input: ChildRehabPlanInput): Promise<ChildRehabPlan> {
  return request<ChildRehabPlan>("/nursing/v1/child-rehab-plans", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildRehabPlans(params: {
  period_id?: string;
  patient_id?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildRehabPlanList> {
  const query = new URLSearchParams();
  if (params.period_id?.trim()) query.set("period_id", params.period_id.trim());
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildRehabPlanList>(`/nursing/v1/child-rehab-plans${suffix}`);
}

// ─── 054 B2：训练执行打卡与进度（进度为读时派生，不落库）─────────────────────

export interface ChildRehabSession {
  id: string;
  plan_id: string;
  /** join 派生 */
  patient_id: string;
  session_date: string;
  /** 0–1 的完成度 */
  completion_ratio: number;
  performance: string | null;
  note: string | null;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

/** 计划进度汇总；`progress_ratio = 完成天数 ÷ 计划天数`（**不封顶**）。 */
export interface ChildRehabProgress {
  plan_id: string;
  session_count: number;
  completed_days: number;
  /** 计划起止含端点天数；任一端缺失为 null */
  planned_days: number | null;
  avg_completion_ratio: number;
  progress_ratio: number;
  last_session_date: string | null;
}

export interface ChildRehabSessionList {
  records: ChildRehabSession[];
  meta: { total: number };
  /** 仅当请求带 `plan_id` 时返回 */
  progress?: ChildRehabProgress;
}

export interface ChildRehabSessionInput {
  plan_id: string;
  session_date: string;
  completion_ratio?: number;
  performance?: string | null;
  note?: string | null;
  metadata?: Record<string, unknown>;
}

export function createChildRehabSession(input: ChildRehabSessionInput): Promise<ChildRehabSession> {
  return request<ChildRehabSession>("/nursing/v1/child-rehab-sessions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildRehabSessions(params: {
  plan_id?: string;
  patient_id?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildRehabSessionList> {
  const query = new URLSearchParams();
  if (params.plan_id?.trim()) query.set("plan_id", params.plan_id.trim());
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildRehabSessionList>(`/nursing/v1/child-rehab-sessions${suffix}`);
}

// ─── 054 B3：里程碑登记 + 读时四态派生（未达/临近/已达成/逾期）───────────────

export interface ChildRehabMilestoneRecord {
  id: string;
  patient_id: string;
  milestone_code: string;
  milestone_name: string;
  expected_age_months: number | null;
  achieved_at: string | null;
  /** 登记态：未达成 / 已达成 */
  status: string;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildRehabMilestoneList {
  records: ChildRehabMilestoneRecord[];
  meta: { total: number };
}

export interface ChildRehabMilestoneStatusItem {
  milestone_code: string;
  milestone_name: string;
  expected_age_months: number | null;
  achieved_at: string | null;
  /** 读时派生四态：未达 / 临近 / 已达成 / 逾期 */
  state: string;
}

export interface ChildRehabMilestoneStatus {
  patient_id: string;
  age_months: number;
  as_of: string;
  items: ChildRehabMilestoneStatusItem[];
}

/** 登记/更新里程碑达成（幂等 upsert）；`achieved_at` 缺省表示「未达成」。 */
export function createChildRehabMilestone(input: {
  patient_id: string;
  milestone_code: string;
  achieved_at?: string | null;
  metadata?: Record<string, unknown>;
}): Promise<ChildRehabMilestoneRecord> {
  return request<ChildRehabMilestoneRecord>("/nursing/v1/child-rehab-milestones", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildRehabMilestones(params: {
  patient_id?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildRehabMilestoneList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildRehabMilestoneList>(`/nursing/v1/child-rehab-milestones${suffix}`);
}

/** 读时四态派生（常量表全量 + 已登记 code）；同一儿童跨月再查会变化。 */
export function getChildRehabMilestoneStatus(patientId: string): Promise<ChildRehabMilestoneStatus> {
  return request<ChildRehabMilestoneStatus>(
    `/nursing/v1/child-rehab-milestone-status/${encodeURIComponent(patientId)}`,
  );
}

// ─── 054 B4：家庭训练指导手册（模板常量 + 分发台账 + 签收单向）───────────────

export interface ChildRehabHandoutTemplate {
  template_code: string;
  template_name: string;
  content: string;
}

export interface ChildRehabHandoutTemplateList {
  records: ChildRehabHandoutTemplate[];
  meta: { total: number };
}

export interface ChildRehabHandout {
  id: string;
  patient_id: string;
  template_code: string;
  template_name: string;
  content: string;
  /** 自由文本，缺省「现场」（建议值：现场/微信/纸质） */
  channel: string;
  delivered_at: string | null;
  /** 签收事实源：非空即已签收 */
  acknowledged_at: string | null;
  acknowledged_by: string | null;
  /** 派生字段：未签收 / 已签收（事实源是 acknowledged_at） */
  status: string;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildRehabHandoutList {
  records: ChildRehabHandout[];
  meta: { total: number };
}

export function listChildRehabHandoutTemplates(): Promise<ChildRehabHandoutTemplateList> {
  return request<ChildRehabHandoutTemplateList>("/nursing/v1/child-rehab-handout-templates");
}

export function createChildRehabHandout(input: {
  patient_id: string;
  template_code: string;
  content?: string;
  channel?: string;
  delivered_at?: string;
  metadata?: Record<string, unknown>;
}): Promise<ChildRehabHandout> {
  return request<ChildRehabHandout>("/nursing/v1/child-rehab-handouts", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildRehabHandouts(params: {
  patient_id?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildRehabHandoutList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildRehabHandoutList>(`/nursing/v1/child-rehab-handouts${suffix}`);
}

/** 签收（**单向**）：已签收再签 → 409；签收事实源是 `acknowledged_at`。 */
export function acknowledgeChildRehabHandout(id: string): Promise<ChildRehabHandout> {
  return request<ChildRehabHandout>(
    `/nursing/v1/child-rehab-handouts/${encodeURIComponent(id)}/acknowledge`,
    { method: "PATCH", body: JSON.stringify({}) },
  );
}

// ============================================================================
//  054 D 链（pharmacy）— 药师工作台：儿科用药规则库 + 用量计算辅助
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.4；
//  形状以 D1/D2 已交付实现为准（`PediatricDoseRuleService` / `PediatricDoseCalculator`）。
//  写路由（POST/PUT 规则）受 `pharmacy:dispense`；读路由（列表/计算）读开放（046 D7）。
//  本段只追加，不改既有签名。
//
//  **安全边界（054 §12 R6）**：规则与计算结果只是「辅助计算 + 上限提示」，
//  **不写医嘱、不替代药师判断**；`capped` 为真时药师必须人工复核上限。
// ============================================================================

// ─── 054 D2：儿科用药规则库 ────────────────────────────────────────────────

export interface PediatricDoseRule {
  id: string;
  /** 药品目录 `public.materials.id`（跨 schema 弱关联，无 FK） */
  material_id: string;
  /** 写入时落库的名称快照 */
  material_name: string | null;
  age_min_months: number | null;
  age_max_months: number | null;
  weight_min_kg: number | null;
  weight_max_kg: number | null;
  /** 体重依赖剂量（与 fixed_dose 至少一项） */
  dose_per_kg: number | null;
  /** 固定剂量（与 dose_per_kg 至少一项；两者同时存在时优先 dose_per_kg） */
  fixed_dose: number | null;
  single_dose_max: number | null;
  daily_dose_max: number | null;
  doses_per_day: number | null;
  unit: string;
  /** 启用 / 停用 */
  status: string;
  note: string | null;
  created_by: string | null;
  created_at: string;
  updated_at: string;
}

export interface PediatricDoseRuleList {
  records: PediatricDoseRule[];
  meta: { total: number };
}

/**
 * 规则写入体。服务端只接受白名单字段（提交 `id/material_name/created_by` 等未声明字段 → 400）；
 * `PUT` 语义为「只覆盖请求体中出现的字段」，显式 `null` 清空可选字段。
 */
export interface PediatricDoseRuleInput {
  material_id: string;
  unit: string;
  age_min_months?: number | null;
  age_max_months?: number | null;
  weight_min_kg?: number | null;
  weight_max_kg?: number | null;
  dose_per_kg?: number | null;
  fixed_dose?: number | null;
  single_dose_max?: number | null;
  daily_dose_max?: number | null;
  doses_per_day?: number | null;
  status?: string;
  note?: string | null;
}

export function listPediatricDoseRules(params: {
  material_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PediatricDoseRuleList> {
  const query = new URLSearchParams();
  if (params.material_id?.trim()) query.set("material_id", params.material_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<PediatricDoseRuleList>(`/pharmacy/v1/pediatric-dose-rules${suffix}`);
}

/** 创建规则；`material_id` 必须存在于 `public.materials`，否则 404。 */
export function createPediatricDoseRule(input: PediatricDoseRuleInput): Promise<PediatricDoseRule> {
  return request<PediatricDoseRule>("/pharmacy/v1/pediatric-dose-rules", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 修改规则（PUT：只覆盖请求体中出现的字段）。 */
export function updatePediatricDoseRule(
  id: string,
  input: Partial<PediatricDoseRuleInput>,
): Promise<PediatricDoseRule> {
  return request<PediatricDoseRule>(`/pharmacy/v1/pediatric-dose-rules/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(input),
  });
}

// ─── 054 D1/D2：用量计算辅助（读开放）───────────────────────────────────────

/** 规则来源诊断码（D1 内部词汇，非业务枚举、不由客户端提交）。 */
export type PediatricDoseSource =
  | "WEIGHT_AND_AGE_BAND"
  | "WEIGHT_BAND"
  | "AGE_BAND"
  | "GENERIC"
  | "GENERIC_FALLBACK";

export interface PediatricDoseCalculation {
  material_id: string;
  material_name: string | null;
  weight_kg: number | null;
  age_months: number | null;
  single_dose: number;
  daily_dose: number;
  unit: string;
  /** 是否触发过任一上限封顶（单次或日总量）；true 时药师必须人工复核 */
  capped: boolean;
  rule_id: string | null;
  /** 规则来源：命中区间 / 通用 / 区间缺失回落 */
  source: PediatricDoseSource | string;
}

/**
 * 用量计算辅助。无可用启用规则 → 404（**不返回 0 剂量**）；
 * 体重/年龄非法（NaN/非正数/非整数）→ 400。
 */
export function getPediatricDoseCalculation(params: {
  material_id: string;
  weight_kg?: number | string;
  age_months?: number | string;
}): Promise<PediatricDoseCalculation> {
  const query = new URLSearchParams({ material_id: params.material_id });
  if (params.weight_kg !== undefined && String(params.weight_kg).trim() !== "") {
    query.set("weight_kg", String(params.weight_kg).trim());
  }
  if (params.age_months !== undefined && String(params.age_months).trim() !== "") {
    query.set("age_months", String(params.age_months).trim());
  }
  return request<PediatricDoseCalculation>(`/pharmacy/v1/pediatric-dose-calculations?${query.toString()}`);
}

// ============================================================================
//  054 C 链（nursing）— 保育员工作台：活动日志 / 行为观测 / 班级日程 / 安全巡查
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.3；
//  形状以 C1/C2 已交付实现为准（`ChildcareActivityLogService` /
//  `ChildcareBehaviorObservationService` / `ChildcareClassScheduleService` /
//  `ChildcareSafetyInspectionService`）。写路由受 `nursing:document`（C3 页面按此显隐），
//  读路由不上门禁（046 D7）。本段只追加，不改既有签名。
// ============================================================================

// ─── 054 C1：日常活动日志（班级级；patient_id 可空）─────────────────────────

export interface ChildcareActivityLog {
  id: string;
  /** 班级级日志为 null；儿童级日志为具体儿童 */
  patient_id: string | null;
  class_name: string;
  activity_date: string;
  activity_name: string;
  participant_count: number;
  note: string | null;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildcareActivityLogList {
  records: ChildcareActivityLog[];
  meta: { total: number };
}

export interface ChildcareActivityLogInput {
  class_name: string;
  activity_name: string;
  patient_id?: string | null;
  /** 缺省为服务端业务当天 */
  activity_date?: string;
  /** 缺省 0 */
  participant_count?: number;
  note?: string | null;
  metadata?: Record<string, unknown>;
}

export function createChildcareActivityLog(
  input: ChildcareActivityLogInput,
): Promise<ChildcareActivityLog> {
  return request<ChildcareActivityLog>("/nursing/v1/childcare-activity-logs", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildcareActivityLogs(params: {
  patient_id?: string;
  class_name?: string;
  date?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildcareActivityLogList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.class_name?.trim()) query.set("class_name", params.class_name.trim());
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildcareActivityLogList>(`/nursing/v1/childcare-activity-logs${suffix}`);
}

// ─── 054 C1：行为观测（append-only，**无 updated_at**）────────────────────────

export interface ChildcareBehaviorObservation {
  id: string;
  patient_id: string;
  /** OffsetDateTime：观测时刻 */
  observed_at: string;
  category: string;
  description: string;
  /** 一般 / 关注 / 需干预（可空） */
  level: string | null;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface ChildcareBehaviorObservationList {
  records: ChildcareBehaviorObservation[];
  meta: { total: number };
}

export interface ChildcareBehaviorObservationInput {
  patient_id: string;
  category: string;
  description: string;
  level?: string | null;
  /** 缺省为服务端当前时刻；接受 ISO-8601 日期或日期时间 */
  observed_at?: string;
  metadata?: Record<string, unknown>;
}

/** 行为观测为 append-only（只新增、不可改），无更新端点。 */
export function createChildcareBehaviorObservation(
  input: ChildcareBehaviorObservationInput,
): Promise<ChildcareBehaviorObservation> {
  return request<ChildcareBehaviorObservation>("/nursing/v1/childcare-behavior-observations", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildcareBehaviorObservations(params: {
  patient_id?: string;
  date?: string;
  level?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildcareBehaviorObservationList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.level?.trim()) query.set("level", params.level.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildcareBehaviorObservationList>(`/nursing/v1/childcare-behavior-observations${suffix}`);
}

// ─── 054 C2：班级日程（固定时段；同时段冲突 409）─────────────────────────────

export interface ChildcareClassSchedule {
  id: string;
  class_name: string;
  schedule_date: string;
  /** 固定 8 个 `HH:mm-HH:mm` 时段之一（服务端白名单） */
  time_slot: string;
  activity_name: string;
  /** 草稿 / 已发布 / 已取消 */
  status: string;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildcareClassScheduleList {
  records: ChildcareClassSchedule[];
  meta: { total: number };
}

export interface ChildcareClassScheduleInput {
  class_name: string;
  schedule_date: string;
  time_slot: string;
  activity_name: string;
  metadata?: Record<string, unknown>;
}

/** 排课；初始状态恒为「草稿」（客户端提交的 status 被忽略）；同时段冲突 → 409。 */
export function createChildcareClassSchedule(
  input: ChildcareClassScheduleInput,
): Promise<ChildcareClassSchedule> {
  return request<ChildcareClassSchedule>("/nursing/v1/childcare-class-schedules", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildcareClassSchedules(params: {
  class_name?: string;
  date?: string;
  status?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildcareClassScheduleList> {
  const query = new URLSearchParams();
  if (params.class_name?.trim()) query.set("class_name", params.class_name.trim());
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildcareClassScheduleList>(`/nursing/v1/childcare-class-schedules${suffix}`);
}

/** 发布/取消；非法流转（如取消后再发布）→ 409，服务端文案原样透出。 */
export function updateChildcareClassScheduleStatus(
  id: string,
  status: string,
): Promise<ChildcareClassSchedule> {
  return request<ChildcareClassSchedule>(
    `/nursing/v1/childcare-class-schedules/${encodeURIComponent(id)}/status`,
    { method: "PATCH", body: JSON.stringify({ status }) },
  );
}

// ─── 054 C2：环境安全巡查（12 项检查项；隐患闭环 待整改→已整改→已关闭）───────

export interface ChildcareSafetyInspectionItem {
  /** 12 项检查项常量之一 */
  item_code: string;
  /** 合格 / 不合格 / 不适用 */
  result: string;
  hazard?: string | null;
}

export interface ChildcareSafetyInspection {
  id: string;
  area: string;
  inspected_at: string;
  items: ChildcareSafetyInspectionItem[];
  /** 待整改 / 已整改 / 已关闭 */
  status: string;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildcareSafetyInspectionList {
  records: ChildcareSafetyInspection[];
  meta: { total: number };
}

export interface ChildcareSafetyInspectionInput {
  area: string;
  /** 缺省为服务端当前时刻；接受 ISO-8601 日期或日期时间 */
  inspected_at?: string;
  items?: ChildcareSafetyInspectionItem[];
  metadata?: Record<string, unknown>;
}

/** 创建巡查记录（初始「待整改」）；`items` 可为空，随后用追加接口补录。 */
export function createChildcareSafetyInspection(
  input: ChildcareSafetyInspectionInput,
): Promise<ChildcareSafetyInspection> {
  return request<ChildcareSafetyInspection>("/nursing/v1/childcare-safety-inspections", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildcareSafetyInspections(params: {
  area?: string;
  date?: string;
  status?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildcareSafetyInspectionList> {
  const query = new URLSearchParams();
  if (params.area?.trim()) query.set("area", params.area.trim());
  if (params.date?.trim()) query.set("date", params.date.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildcareSafetyInspectionList>(`/nursing/v1/childcare-safety-inspections${suffix}`);
}

/**
 * 追加检查/整改项（追加到 `items` 末尾）。
 * 状态为「待整改」时顺带流转到「已整改」；「已关闭」→ 409。
 */
export function appendChildcareSafetyInspectionItem(
  id: string,
  item: ChildcareSafetyInspectionItem,
): Promise<ChildcareSafetyInspection> {
  return request<ChildcareSafetyInspection>(
    `/nursing/v1/childcare-safety-inspections/${encodeURIComponent(id)}/items`,
    { method: "POST", body: JSON.stringify(item) },
  );
}

/** 关闭隐患：仅「已整改 → 已关闭」；「待整改」跳步 → 409（服务端文案原样透出）。 */
export function closeChildcareSafetyInspection(id: string): Promise<ChildcareSafetyInspection> {
  return request<ChildcareSafetyInspection>(
    `/nursing/v1/childcare-safety-inspections/${encodeURIComponent(id)}/close`,
    { method: "PATCH", body: JSON.stringify({}) },
  );
}

// ============================================================================
//  054 E 链（healthcare）— 收银工作台：套餐/订阅 / 结算/收款 / 发票
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.5；
//  形状以 E1–E3 已交付实现为准（`ChildServicePackageService` /
//  `ChildServiceSettlementService` / `InvoiceService`）。
//  写路由受 `billing:write`（E4 页面按此显隐），读路由不上门禁（046 D7）。
//  本段只追加，不改既有签名。
//
//  **金额纪律**：明细金额 = `unit_price × quantity`（HALF_UP 到分）、合计 = 明细之和、
//  订阅 `amount` 为购买时套餐价快照、付费状态与结算状态一律**服务端读时派生**；
//  客户端提交 `total_amount`/`paid_amount`/`used_amount`/`status` 等派生字段一律 400，
//  前端只展示、不重算。
// ============================================================================

// ─── 054 E1：儿保服务套餐定义 ──────────────────────────────────────────────

export interface ChildServicePackageItem {
  item_code: string;
  item_name: string;
  quantity: number;
}

export interface ChildServicePackage {
  id: string;
  code: string;
  name: string;
  price: number;
  items: ChildServicePackageItem[];
  /** 启用 / 停用 */
  status: string;
  metadata: Record<string, unknown> | null;
  created_by: string | null;
  created_at: string;
  updated_at: string;
}

export interface ChildServicePackageList {
  records: ChildServicePackage[];
  meta: { total: number };
}

/** 套餐写入体；`id`/`created_by`/时间戳由服务端管控（提交即 400）。 */
export interface ChildServicePackageInput {
  code: string;
  name: string;
  /** 非负、最多 2 位小数 */
  price: number;
  items: ChildServicePackageItem[];
  status?: string;
  metadata?: Record<string, unknown>;
}

export function listChildServicePackages(params: {
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildServicePackageList> {
  const query = new URLSearchParams();
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildServicePackageList>(`/healthcare/v1/child-service-packages${suffix}`);
}

export function createChildServicePackage(
  input: ChildServicePackageInput,
): Promise<ChildServicePackage> {
  return request<ChildServicePackage>("/healthcare/v1/child-service-packages", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 修改套餐（PUT：只覆盖请求体中出现的字段；`code` 业务唯一）。 */
export function updateChildServicePackage(
  id: string,
  input: Partial<ChildServicePackageInput>,
): Promise<ChildServicePackage> {
  return request<ChildServicePackage>(
    `/healthcare/v1/child-service-packages/${encodeURIComponent(id)}`,
    { method: "PUT", body: JSON.stringify(input) },
  );
}

// ─── 054 E1：套餐订阅与付费状态（读时派生四态）──────────────────────────────

export interface ChildPackageSubscription {
  id: string;
  patient_id: string;
  package_id: string;
  package_code: string | null;
  package_name: string | null;
  purchased_at: string | null;
  /** 购买时套餐价快照（服务端写入） */
  amount: number;
  paid_amount: number;
  used_amount: number;
  /** 启用 / 停用 */
  status: string;
  /** 读时派生：未付 / 部分 / 已付 / 已用完 */
  pay_state: string;
  metadata: Record<string, unknown> | null;
  operator: string | null;
  created_at: string;
  updated_at: string;
}

export interface ChildPackageSubscriptionList {
  records: ChildPackageSubscription[];
  meta: { total: number };
}

/** 订阅写入体；`amount`/`paid_amount`/`used_amount`/`status` 由服务端派生（提交即 400）。 */
export interface ChildPackageSubscriptionInput {
  patient_id: string;
  package_id: string;
  /** ISO-8601 带偏移的日期时间；不得晚于当前时刻 */
  purchased_at?: string;
  metadata?: Record<string, unknown>;
}

export function createChildPackageSubscription(
  input: ChildPackageSubscriptionInput,
): Promise<ChildPackageSubscription> {
  return request<ChildPackageSubscription>("/healthcare/v1/child-package-subscriptions", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildPackageSubscriptions(params: {
  patient_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildPackageSubscriptionList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildPackageSubscriptionList>(`/healthcare/v1/child-package-subscriptions${suffix}`);
}

/** 订阅状态流转：启用 ⇄ 停用（非法值 400）。 */
export function updateChildPackageSubscriptionStatus(
  id: string,
  status: string,
): Promise<ChildPackageSubscription> {
  return request<ChildPackageSubscription>(
    `/healthcare/v1/child-package-subscriptions/${encodeURIComponent(id)}/status`,
    { method: "PATCH", body: JSON.stringify({ status }) },
  );
}

// ─── 054 E2：儿保服务结算单与收款 ──────────────────────────────────────────

/** 明细写入体：`amount` 由服务端按 `unit_price × quantity` 计算（提交即 400）。 */
export interface ChildServiceSettlementItemInput {
  item_code: string;
  item_name: string;
  /** 非负、最多 2 位小数 */
  unit_price: number;
  /** 正数、最多 3 位小数 */
  quantity: number;
}

export interface ChildServiceSettlementItem extends ChildServiceSettlementItemInput {
  /** 服务端计算：`unit_price × quantity` HALF_UP 到分 */
  amount: number;
}

export interface ChildServiceSettlement {
  id: string;
  settlement_no: string;
  patient_id: string;
  subscription_id: string | null;
  /** 服务端计算：明细金额之和 */
  total_amount: number;
  paid_amount: number;
  /** 待收款 / 部分收款 / 已结清 / 已作废（读时派生） */
  status: string;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
  items: ChildServiceSettlementItem[];
}

export interface ChildServiceSettlementList {
  records: ChildServiceSettlement[];
  meta: { total: number };
}

/** 结算单写入体；`settlement_no`/金额/状态由服务端派生（提交即 400）。 */
export interface ChildServiceSettlementInput {
  patient_id: string;
  subscription_id?: string | null;
  items: ChildServiceSettlementItemInput[];
  metadata?: Record<string, unknown>;
}

export function createChildServiceSettlement(
  input: ChildServiceSettlementInput,
): Promise<ChildServiceSettlement> {
  return request<ChildServiceSettlement>("/healthcare/v1/child-service-settlements", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildServiceSettlements(params: {
  patient_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildServiceSettlementList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildServiceSettlementList>(`/healthcare/v1/child-service-settlements${suffix}`);
}

/** 收款写入体：金额 > 0、最多 2 位小数；单笔不得超未收金额（超额 400）。 */
export interface ChildServicePaymentInput {
  amount: number;
  /** 现金 / 转账 / 银行卡 / 微信 / 支付宝 */
  method: string;
  remark?: string | null;
  metadata?: Record<string, unknown>;
}

/** 登记收款（返回更新后的结算单）；超额收款 → 400，服务端文案原样透出。 */
export function registerChildServiceSettlementPayment(
  settlementId: string,
  input: ChildServicePaymentInput,
): Promise<ChildServiceSettlement> {
  return request<ChildServiceSettlement>(
    `/healthcare/v1/child-service-settlements/${encodeURIComponent(settlementId)}/payments`,
    { method: "POST", body: JSON.stringify(input) },
  );
}

// ─── 054 E3：儿保发票台账 ──────────────────────────────────────────────────

export interface ChildServiceInvoice {
  id: string;
  invoice_no: string;
  patient_id: string;
  title: string;
  /** 关联结算单/订阅时由服务端取该单金额；否则取提交的 `amount` */
  amount: number;
  /** 已开具 / 已作废（单向：已开具 → 已作废） */
  status: string;
  settlement_id: string | null;
  subscription_id: string | null;
  issued_at: string | null;
  operator: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  updated_at: string;
}

export interface ChildServiceInvoiceList {
  records: ChildServiceInvoice[];
  meta: { total: number };
}

/**
 * 发票开具写入体。
 * `settlement_id` 与 `subscription_id` **互斥**；二者任一存在时**不得**再提交 `amount`
 * （金额由服务端按该单派生，违反即 400）；两者都不给时必须给正数 `amount`。
 * `invoice_no` 可省略（服务端生成 `INV-<ulid>`），最多 64 字符且唯一（重复 409）。
 */
export interface ChildServiceInvoiceInput {
  patient_id: string;
  title: string;
  invoice_no?: string;
  amount?: number;
  settlement_id?: string | null;
  subscription_id?: string | null;
  metadata?: Record<string, unknown>;
}

export function createChildServiceInvoice(
  input: ChildServiceInvoiceInput,
): Promise<ChildServiceInvoice> {
  return request<ChildServiceInvoice>("/healthcare/v1/invoices", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

export function listChildServiceInvoices(params: {
  patient_id?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<ChildServiceInvoiceList> {
  const query = new URLSearchParams();
  if (params.patient_id?.trim()) query.set("patient_id", params.patient_id.trim());
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ChildServiceInvoiceList>(`/healthcare/v1/invoices${suffix}`);
}

/** 发票状态流转：仅 `已开具 → 已作废`；非法流转服务端 400（文案原样透出）。 */
export function updateChildServiceInvoiceStatus(
  id: string,
  status: string,
): Promise<ChildServiceInvoice> {
  return request<ChildServiceInvoice>(
    `/healthcare/v1/invoices/${encodeURIComponent(id)}/status`,
    { method: "PATCH", body: JSON.stringify({ status }) },
  );
}

// ============================================================================
//  054 F 链（healthcare）— 基础数据维护（F2）
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.6；
//  形状以 F2 已交付实现为准（`BaseDataService`，迁移 V539）。
//  写路由受 `base_data:manage`；读路由读开放（046 D7），且读取时会顺带刷新
//  内容覆盖层（**读库优先、常量回落**：库为空/读失败 → 完全等价于既有常量基线）。
//  本段只追加，不改既有签名。
// ============================================================================

// ─── 054 F2：疫苗类型内容 ──────────────────────────────────────────────────

export interface BaseVaccinationType {
  id: string;
  code: string;
  name: string;
  /** 剂次数（≥ 1） */
  dose_count: number;
  /** 各剂次推荐月龄数组，长度必须等于 `dose_count` */
  recommended_months: number[];
  /** 启用 / 停用 */
  status: string;
  sort_order: number;
  metadata: Record<string, unknown> | null;
  created_by: string | null;
  updated_by: string | null;
  created_at: string;
  updated_at: string;
}

export interface BaseVaccinationTypeList {
  records: BaseVaccinationType[];
  meta: { total: number };
}

/** 写入白名单字段（`id`/`created_by`/`updated_by`/时间戳由服务端管控，提交即 400）。 */
export interface BaseVaccinationTypeInput {
  code: string;
  name: string;
  dose_count: number;
  recommended_months: number[];
  status?: string;
  sort_order?: number;
  metadata?: Record<string, unknown>;
}

export function listBaseVaccinationTypes(params: {
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<BaseVaccinationTypeList> {
  const query = new URLSearchParams();
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<BaseVaccinationTypeList>(`/healthcare/v1/base-vaccination-types${suffix}`);
}

export function createBaseVaccinationType(
  input: BaseVaccinationTypeInput,
): Promise<BaseVaccinationType> {
  return request<BaseVaccinationType>("/healthcare/v1/base-vaccination-types", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 修改疫苗类型（PUT：只覆盖请求体中出现的字段；`code` 唯一，重复 409）。 */
export function updateBaseVaccinationType(
  id: string,
  input: Partial<BaseVaccinationTypeInput>,
): Promise<BaseVaccinationType> {
  return request<BaseVaccinationType>(
    `/healthcare/v1/base-vaccination-types/${encodeURIComponent(id)}`,
    { method: "PUT", body: JSON.stringify(input) },
  );
}

// ─── 054 F2：发育量表内容 ──────────────────────────────────────────────────

/** 量表领域（`definition.domains` 元素）。 */
export interface DevelopmentalScaleDomain {
  code: string;
  name?: string;
}

/** 量表条目选项（`definition.items[].options` 元素）。 */
export interface DevelopmentalScaleOption {
  value: number;
  label: string;
}

/** 量表条目（`definition.items` 元素）。 */
export interface DevelopmentalScaleItem {
  code: string;
  /** 必须命中 `definition.domains[].code` */
  domain: string;
  prompt: string;
  min_months: number;
  max_months: number;
  options: DevelopmentalScaleOption[];
}

export interface DevelopmentalScaleDefinition {
  domains: DevelopmentalScaleDomain[];
  items: DevelopmentalScaleItem[];
}

export interface BaseDevelopmentalScale {
  id: string;
  scale_code: string;
  scale_version: string;
  name: string;
  age_min_months: number;
  age_max_months: number;
  /** `{domains:[...], items:[...]}`（服务端校验形状，畸形行加载时跳过并回落常量） */
  definition: DevelopmentalScaleDefinition;
  status: string;
  metadata: Record<string, unknown> | null;
  created_by: string | null;
  updated_by: string | null;
  created_at: string;
  updated_at: string;
}

export interface BaseDevelopmentalScaleList {
  records: BaseDevelopmentalScale[];
  meta: { total: number };
}

export interface BaseDevelopmentalScaleInput {
  scale_code: string;
  scale_version: string;
  name: string;
  age_min_months: number;
  age_max_months: number;
  definition: DevelopmentalScaleDefinition;
  status?: string;
  metadata?: Record<string, unknown>;
}

export function listBaseDevelopmentalScales(params: {
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<BaseDevelopmentalScaleList> {
  const query = new URLSearchParams();
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<BaseDevelopmentalScaleList>(`/healthcare/v1/base-developmental-scales${suffix}`);
}

export function createBaseDevelopmentalScale(
  input: BaseDevelopmentalScaleInput,
): Promise<BaseDevelopmentalScale> {
  return request<BaseDevelopmentalScale>("/healthcare/v1/base-developmental-scales", {
    method: "POST",
    body: JSON.stringify(input),
  });
}

/** 修改量表（PUT：只覆盖请求体中出现的字段；`(scale_code, scale_version)` 唯一，重复 409）。 */
export function updateBaseDevelopmentalScale(
  id: string,
  input: Partial<BaseDevelopmentalScaleInput>,
): Promise<BaseDevelopmentalScale> {
  return request<BaseDevelopmentalScale>(
    `/healthcare/v1/base-developmental-scales/${encodeURIComponent(id)}`,
    { method: "PUT", body: JSON.stringify(input) },
  );
}

// ============================================================================
//  054 F 链（healthcare）— 操作审计查询（F3）
//
//  冻结契约：`docs/plans/054.aceso-child-health-role-workflows.md` §5.6；
//  形状以 F3 已交付实现为准（`AuditLogService` + `AuditMiddleware`，迁移 V540）。
//
//  **本接口是全系统唯一受门禁的读路由**（054 §11.2 Q8）：受 `access:manage`。
//  审计写入由 apps/aceso 的旁路中间件负责（只记 POST/PUT/PATCH/DELETE，**不读请求体**，
//  失败只 warn 不阻断，403 尝试也入账）；本客户端**只有查询**，没有任何写入口。
//  本段只追加，不改既有签名。
// ============================================================================

export interface AuditLogEntry {
  id: string;
  /** 操作主体（认证主体） */
  actor: string | null;
  /** POST / PUT / PATCH / DELETE（中间件只审计这四类写方法） */
  method: string;
  path: string;
  status_code: number | null;
  /** 路径参数 `:id`（无该参数时为 null） */
  resource_id: string | null;
  occurred_at: string;
  /** 仅元数据（如 `platform_admin`），**不含请求体** */
  metadata: Record<string, unknown> | null;
}

export interface AuditLogList {
  records: AuditLogEntry[];
  meta: { total: number };
}

/**
 * 审计查询（只读）。筛选：`actor` 精确、`method` 精确（服务端会转大写）、
 * `path` **前缀**匹配、`from`/`to` 为 ISO-8601 带偏移的日期时间（`from` 不得晚于 `to`）。
 */
export function listAuditLogs(params: {
  actor?: string;
  method?: string;
  path?: string;
  from?: string;
  to?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<AuditLogList> {
  const query = new URLSearchParams();
  if (params.actor?.trim()) query.set("actor", params.actor.trim());
  if (params.method?.trim()) query.set("method", params.method.trim().toUpperCase());
  if (params.path?.trim()) query.set("path", params.path.trim());
  if (params.from?.trim()) query.set("from", params.from.trim());
  if (params.to?.trim()) query.set("to", params.to.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<AuditLogList>(`/healthcare/v1/audit-logs${suffix}`);
}
