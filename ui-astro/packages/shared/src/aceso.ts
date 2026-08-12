import { serviceBase } from "./aceso-service-config";

interface ProblemDetails {
  error?: string;
  detail?: string;
  title?: string;
}

/** 带 HTTP 状态码的 API 错误；调用方可据此区分 404 等业务状态 */
export class ApiRequestError extends Error {
  readonly status: number;

  constructor(status: number, message: string) {
    super(message);
    this.name = "ApiRequestError";
    this.status = status;
  }
}

interface RequestOptions {
  csrf?: boolean;
  redirectOnUnauthorized?: boolean;
  service?: "aceso" | "identity" | "nexus";
}

export interface IdentitySession {
  subject_id: string;
  access: "完整" | "仅改密";
}

export interface IdentitySubject {
  id: string;
  status: "启用" | "禁用";
  security_version: number;
  display_name: string;
  identifier: string;
  roles: string[];
  created_at: string;
  updated_at: string;
}

export interface IdentitySubjectList {
  records: IdentitySubject[];
  meta: { total: number };
}

export interface DepartmentPayload {
  name: string;
  description?: string;
}

export interface Department {
  id: string;
  category: string;
  code: string;
  root_code: string;
  parent_code: string;
  payload: DepartmentPayload;
  created_at: string;
  updated_at: string;
}

export interface DepartmentInput {
  code: string;
  parent_code: string;
  root_code: string;
  name: string;
  description?: string;
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
  /** 结算收束冻结标记（养老收费）；未收束为 null */
  settled_at: string | null;
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
  quantity: number | null;
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
  quantity?: number;
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
  quantity: number | null;
  note: string | null;
  metadata: Record<string, unknown> | null;
  created_at: string;
  /** 任务摘要 */
  task_description: string | null;
  task_type: string | null;
  task_frequency_name: string | null;
  task_period_id: string | null;
  /** 长者摘要 */
  patient_id: string | null;
  patient_name: string | null;
  consumption_summary?: NursingConsumptionSummary;
  /** 逾期派生字段 */
  is_overdue: boolean;
  overdue_minutes: number | null;
}

interface NursingPage<T> {
  records: T[];
  meta: { total: number; overdue_total?: number };
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
  const body: ProblemDetails | T | null = contentType.includes("application/json") || contentType.includes("application/problem+json")
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
    throw new ApiRequestError(
      response.status,
      problem?.error || problem?.detail || problem?.title || responseText || `请求失败 (${response.status})`,
    );
  }
  return body as T;
}

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

export function listDepartments(): Promise<Department[]> {
  const params = new URLSearchParams({ category: "department", page: "1", page_size: "100" });
  return request<Department[]>(`/settings?${params}`, {}, { service: "nexus" });
}

function settingPayload(input: DepartmentInput): Record<string, unknown> {
  return {
    category: "department",
    code: input.code.trim(),
    parent_code: input.parent_code,
    root_code: input.root_code,
    payload: {
      name: input.name.trim(),
      ...(input.description?.trim() ? { description: input.description.trim() } : {}),
    },
  };
}

export function createDepartment(input: DepartmentInput): Promise<Department> {
  return request<Department>("/settings", {
    method: "POST",
    body: JSON.stringify(settingPayload(input)),
  }, { service: "nexus" });
}

export function updateDepartment(id: string, input: DepartmentInput): Promise<Department> {
  return request<Department>(`/settings/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(settingPayload(input)),
  }, { service: "nexus" });
}

export async function deleteDepartment(id: string): Promise<void> {
  await request<void>(`/settings/${encodeURIComponent(id)}`, { method: "DELETE" }, { service: "nexus" });
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

/** 发药单等业务页面使用的仓库下拉选项；无配置时返回空数组 */
export async function listWarehouseOptions(): Promise<WarehouseOption[]> {
  const warehouses = await listWarehouses();
  return warehouses.map((warehouse) => ({ code: warehouse.code, name: warehouse.payload.name }));
}

export function listPatients(params: {
  name?: string;
  status?: string;
  limit?: number;
  offset?: number;
} = {}): Promise<PatientList> {
  const query = new URLSearchParams();
  if (params.name?.trim()) query.set("name", params.name.trim());
  if (params.status) query.set("status", params.status);
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

export function listElderlyAdmissions(params: { status?: string; search?: string; limit?: number; offset?: number } = {}): Promise<EncounterList> {
  const query = new URLSearchParams();
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

export function dischargeEncounter(id: string, dischargeDate?: string): Promise<Encounter> {
  return request<Encounter>(`/healthcare/v1/encounters/${encodeURIComponent(id)}/discharge`, {
    method: "PATCH",
    body: JSON.stringify(dischargeDate ? { discharge_date: dischargeDate } : {}),
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

/** MAR 查询：按老人（encounter_id）/医嘱/给药日期/结果过滤给药明细 */
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
  physician: string;
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
      physician: input.physician.trim(),
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
  is_major: boolean;
  metadata: Record<string, unknown> | null;
  created_at: string;
}

export interface DiagnosisInput {
  diagnosis_type: "PRIMARY" | "SECONDARY";
  diagnosis_text: string;
  diagnosis_date: string;
  physician: string;
  icd_code?: string;
  is_major?: boolean;
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
      physician: input.physician.trim(),
      ...(input.icd_code?.trim() ? { icd_code: input.icd_code.trim() } : {}),
      ...(input.is_major !== undefined ? { is_major: input.is_major } : {}),
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
  doctor: string;
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
  frequency_code: string | null;
  frequency_name: string | null;
  start_time: string | null;
  end_time: string | null;
  doctor: string;
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
  operator: string;
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

/** 审方/调配/确认/取消入参 */
export interface PharmacyDispenseActionInput {
  operator: string;
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

export function confirmPharmacyReturn(id: string, operator: string): Promise<PharmacyReturn> {
  return request<PharmacyReturn>(`/pharmacy/v1/returns/${encodeURIComponent(id)}/confirm`, {
    method: "PUT",
    body: JSON.stringify({ operator }),
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
// 养老/福利院方向：离院老人回访 + 在院慢病老人定期随访。
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

/** 老人随访历史时间线（详情页用）：plans 与 records 各按时间倒序 */
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

/** 按老人查询体征记录（时间倒序，可分页，按类型/时间范围过滤） */
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

/** 趋势序列：指定老人、指定体征类型在一段时间内的测量点（时间升序，供绘图） */
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

/** 跨老人异常列表：仅 abnormal=true 的记录（patient_id 可选过滤），按测量时间倒序 */
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
//  （不强制关联费用项目字典；结算收束不自动冲抵押金）。
//  退押为独立操作：不校验 encounter 收束状态，离院/去世后仍可退押。
// ========================================================================

export type DepositType = "登记" | "退押";

/** 押金台账记录（登记/退押为同表两类记录） */
export interface DepositRecord {
  id: string;
  encounter_id: string;
  type: DepositType;
  /** 发生金额（元），登记与退押均为正数；余额 = Σ登记 − Σ退押 */
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

/** 按 encounter 查询押金台账（登记+退押倒序分页）；meta.balance 为当前余额 */
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
//  费用字典、账单（按月自动计费 + 手工加项）、缴费与欠费、结算收束入口，
//  路径 /healthcare/v1/*，沿用 request 封装（token 注入、JSON、401、{records,meta}）。
//  押金（deposits）为独立子任务：本组不引用押金接口，押金展示只在押金管理页。
// ========================================================================

// ─── 费用项目字典 (Fee Items) ────────────────────────────────────────

export type FeeItemCategory = "床位费" | "护理费" | "伙食费" | "个性化服务费" | "押金" | "其他";
export type FeeItemStatus = "启用" | "停用";

/** 费用项目字典条目；unit_price 为 NUMERIC(12,2) 数值（元） */
export interface FeeItem {
  id: string;
  category: FeeItemCategory;
  name: string;
  unit_price: number;
  status: FeeItemStatus;
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
  /** 仅详情接口返回（列表接口为空数组） */
  items?: BillItem[];
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

/** 结算收束：已离院/去世未结算的养老入住 → 生成区间最终账单并冻结全部账单；返回收束后的 encounter */
export function settleEncounterBilling(encounterId: string): Promise<Encounter> {
  return request<Encounter>(`/healthcare/v1/encounters/${encodeURIComponent(encounterId)}/billing-settlement`, {
    method: "POST",
    body: JSON.stringify({}),
  });
}

// ─── 缴费与欠费 (Payments / Arrears / Summary) ───────────────────────

export type PaymentMethod = "现金" | "转账" | "银行卡" | "微信" | "支付宝";

/** 缴费流水记录；operator 由服务端写入认证主体 */
export interface Payment {
  id: string;
  bill_id: string;
  amount: number;
  method: PaymentMethod;
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

export function listArrears(params: { limit?: number; offset?: number } = {}): Promise<ArrearsList> {
  const query = new URLSearchParams();
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<ArrearsList>(`/healthcare/v1/payments/arrears${suffix}`);
}

/** 收费汇总：应缴 = Σ账单合计、已缴 = Σ缴费金额、欠费 = Σ待缴费账单余额；无数据时三项均为 0 */
export interface PaymentSummary {
  due_amount: number;
  paid_amount: number;
  arrears_amount: number;
}

export function getPaymentSummary(): Promise<PaymentSummary> {
  return request<PaymentSummary>("/healthcare/v1/payments/summary");
}
