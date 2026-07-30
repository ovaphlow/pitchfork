interface ProblemDetails {
  error?: string;
  detail?: string;
  title?: string;
}

interface RequestOptions {
  csrf?: boolean;
  redirectOnUnauthorized?: boolean;
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
}

export interface NursingServicePeriod {
  id: string;
  patient_id: string;
  service_type: string;
  start_date: string;
  end_date: string | null;
  coordinator: string | null;
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

function apiBase(): string {
  const configured = import.meta.env.PUBLIC_API_URL?.trim();
  if (!configured) {
    throw new Error("Aceso API 地址未配置，请联系系统管理员");
  }
  return configured.replace(/\/$/, "");
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
    throw new Error(problem?.error || problem?.detail || problem?.title || responseText || `请求失败 (${response.status})`);
  }
  return body as T;
}

async function request<T>(
  path: string,
  init: RequestInit = {},
  { csrf = false, redirectOnUnauthorized = true }: RequestOptions = {},
): Promise<T> {
  const url = `${apiBase()}${path}`;
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
  return request<IdentitySession>("/identity/v1/session", {}, { redirectOnUnauthorized });
}

export async function login(identifier: string, password: string): Promise<IdentitySession> {
  const body = new URLSearchParams({ identifier, password });
  const url = `${apiBase()}/identity/v1/sessions`;
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
  await request<void>("/identity/v1/sessions/current", { method: "DELETE" }, { csrf: true, redirectOnUnauthorized: false });
}

export function listIdentitySubjects(page: number, pageSize: number): Promise<IdentitySubjectList> {
  const params = new URLSearchParams({ limit: String(pageSize), offset: String((page - 1) * pageSize) });
  return request<IdentitySubjectList>(`/identity/v1/subjects?${params}`);
}

export function createIdentitySubject(input: {
  display_name: string;
  identifier: string;
  password: string;
}): Promise<IdentitySubject> {
  return request<IdentitySubject>(
    "/identity/v1/subjects",
    { method: "POST", body: JSON.stringify(input) },
    { csrf: true },
  );
}

export function disableIdentitySubject(id: string): Promise<IdentitySubject> {
  return request<IdentitySubject>(
    `/identity/v1/subjects/${encodeURIComponent(id)}`,
    { method: "PATCH", body: JSON.stringify({ status: "禁用" }) },
    { csrf: true },
  );
}

export function setIdentityTemporaryPassword(id: string, temporaryPassword: string): Promise<IdentitySubject> {
  return request<IdentitySubject>(
    `/identity/v1/subjects/${encodeURIComponent(id)}`,
    { method: "PATCH", body: JSON.stringify({ temporary_password: temporaryPassword }) },
    { csrf: true },
  );
}

export function listDepartments(): Promise<Department[]> {
  const params = new URLSearchParams({ category: "department", page: "1", page_size: "100" });
  return request<Department[]>(`/shared/v1/settings?${params}`);
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
  return request<Department>("/shared/v1/settings", {
    method: "POST",
    body: JSON.stringify(settingPayload(input)),
  });
}

export function updateDepartment(id: string, input: DepartmentInput): Promise<Department> {
  return request<Department>(`/shared/v1/settings/${encodeURIComponent(id)}`, {
    method: "PUT",
    body: JSON.stringify(settingPayload(input)),
  });
}

export async function deleteDepartment(id: string): Promise<void> {
  await request<void>(`/shared/v1/settings/${encodeURIComponent(id)}`, { method: "DELETE" });
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
  if (params.status?.trim()) query.set("status", params.status.trim());
  if (params.search?.trim()) query.set("search", params.search.trim());
  if (params.limit !== undefined) query.set("limit", String(params.limit));
  if (params.offset !== undefined) query.set("offset", String(params.offset));
  const suffix = query.toString() ? `?${query.toString()}` : "";
  return request<EncounterList>(`/healthcare/v1/elderly-admissions${suffix}`);
}

export function listActiveElderlyAdmissions(params: { search?: string; limit?: number; offset?: number } = {}): Promise<EncounterList> {
  return listElderlyAdmissions({ status: "ACTIVE", ...params });
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
  event_type: "ASSESSMENT" | "CARE_PLAN" | "TASK" | "TASK_EXECUTION" | "NURSING_RECORD";
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

/** 耗材摘要 */
export interface NursingConsumptionSummary {
  count: number;
  warehouse: string;
  total_cost: number;
}

/** 耗材明细 */
export interface NursingExecutionConsumption {
  id: string;
  stock_operation_detail_id: string;
  stock_id: string;
  material_id: string;
  material_name?: string;
  lot_id: string | null;
  warehouse: string;
  quantity: number;
  unit: string;
  split_quantity: number | null;
  unit_cost: number;
  total_cost: number;
  created_at: string;
}

/** 耗材输入项 */
export interface NursingConsumptionInput {
  stock_id: string;
  unit: "PACKAGE" | "SPLIT";
  quantity?: number;
  split_quantity?: number;
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

/** 可用库存 */
export interface InventoryStockAvailability {
  id: string;
  warehouse: string;
  material_id: string;
  material_code: string;
  material_name: string;
  category: string;
  package_unit: string;
  split_unit: string | null;
  split_ratio: number | null;
  lot_id: string | null;
  batch_no: string | null;
  expiry_date: string | null;
  quantity: number;
  locked_quantity: number;
  available_quantity: number;
  unit_cost: number;
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

/** 查询可用仓库列表 */
export function listInventoryWarehouses(): Promise<string[]> {
  return request<string[]>("/inventories/v1/stocks/warehouses");
}

/** 确认入库 */
export function confirmInventoryInbound(input: {
  warehouse: string;
  items: Array<{
    material_id: string;
    lot_id?: string;
    quantity: number;
    unit_cost: number;
  }>;
  note?: string;
}) {
  return request("/inventories/v1/operations/inbound", {
    method: "POST",
    body: JSON.stringify(input),
  });
}
