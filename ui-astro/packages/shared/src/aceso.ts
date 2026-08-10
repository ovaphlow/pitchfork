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

