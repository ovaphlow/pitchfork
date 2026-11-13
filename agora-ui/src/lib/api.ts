const configuredBase = import.meta.env.PUBLIC_API_URL?.trim() ?? "";

/** Normalized API origin, or an empty string when PUBLIC_API_URL is unset. */
export const API_BASE_URL = configuredBase.replace(/\/+$/, "");

export const isApiConfigured = API_BASE_URL.length > 0;

export interface ApiStatus {
  service: string;
  status: string;
  time: string;
}

export interface ApiReadiness {
  status: "ready" | "unavailable" | string;
}

export interface AuthSession {
  subject_id: string;
  display_name: string;
  identifier?: string;
  platform_admin: boolean;
  access: string;
  roles: string[];
  permissions: string[];
  nexus_ready: boolean;
}

export class AuthError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "AuthError";
    this.status = status;
  }
}

export class ApiError extends Error {
  status: number;

  constructor(message: string, status: number) {
    super(message);
    this.name = "ApiError";
    this.status = status;
  }
}

export class ConfigurationError extends Error {
  constructor() {
    super("PUBLIC_API_URL is not configured.");
    this.name = "ConfigurationError";
  }
}

export class StatusError extends Error {
  status: number;
  kind: "http" | "payload";

  constructor(status: number, kind: "http" | "payload") {
    super(`status request failed (${kind}, HTTP ${status})`);
    this.name = "StatusError";
    this.status = status;
    this.kind = kind;
  }
}

function endpoint(path: string): string {
  if (!isApiConfigured) {
    throw new ConfigurationError();
  }
  return `${API_BASE_URL}${path}`;
}

async function parseJson(response: Response): Promise<unknown> {
  try {
    return await response.json();
  } catch {
    return null;
  }
}

async function errorMessage(response: Response): Promise<string> {
  const payload = (await parseJson(response)) as { error?: unknown } | null;
  if (payload && typeof payload.error === "string" && payload.error.trim()) {
    return payload.error;
  }
  return `HTTP ${response.status}`;
}

function normalizeSession(payload: Partial<AuthSession> | null): AuthSession {
  if (!payload || typeof payload.subject_id !== "string") {
    throw new AuthError("unexpected authentication response", 502);
  }
  return {
    subject_id: payload.subject_id,
    display_name: typeof payload.display_name === "string" ? payload.display_name : "",
    identifier: typeof payload.identifier === "string" ? payload.identifier : undefined,
    platform_admin: payload.platform_admin === true,
    access: typeof payload.access === "string" ? payload.access : "",
    roles: Array.isArray(payload.roles) ? payload.roles : [],
    permissions: Array.isArray(payload.permissions) ? payload.permissions : [],
    nexus_ready: payload.nexus_ready === true,
  };
}

/** POST 认证类端点：非 2xx 统一抛 AuthError，成功返回解析后的 JSON。 */
async function postAuth(path: string, body: unknown): Promise<unknown> {
  const response = await fetch(endpoint(path), {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });

  if (!response.ok) {
    throw new AuthError(await errorMessage(response), response.status);
  }

  return parseJson(response);
}

export async function getSession(signal?: AbortSignal): Promise<AuthSession | null> {
  const response = await fetch(endpoint("/crate-api/agora/v1/auth/session"), {
    headers: { Accept: "application/json" },
    credentials: "include",
    signal,
  });

  if (response.status === 401) {
    return null;
  }
  if (!response.ok) {
    throw new AuthError(await errorMessage(response), response.status);
  }

  return normalizeSession((await parseJson(response)) as Partial<AuthSession> | null);
}

/** 密码登录：仅作为存量非邮箱账号的兜底入口。 */
export async function login(identifier: string, password: string): Promise<AuthSession> {
  const payload = await postAuth("/crate-api/agora/v1/auth/login", { identifier, password });
  return normalizeSession(payload as Partial<AuthSession> | null);
}

/**
 * 申请一次性登录邮件。
 *
 * 服务端永远返回 202（未知邮箱、限流、投递失败都一样），所以这里没有返回值：
 * 调用方只能提示“如果该邮箱已注册，链接已发送”。
 */
export async function requestEmailLink(email: string, displayName?: string): Promise<void> {
  const body: Record<string, string> = { email };
  if (displayName) {
    body.display_name = displayName;
  }
  await postAuth("/crate-api/agora/v1/auth/email-link", body);
}

/** 兑换邮件中的一次性 token；成功即建立会话（Cookie 由响应写入）。 */
export async function exchangeEmailLink(token: string): Promise<AuthSession> {
  const payload = await postAuth("/crate-api/agora/v1/auth/email-link/exchange", { token });
  return normalizeSession(payload as Partial<AuthSession> | null);
}

export interface MembershipOrganization {
  id: string;
  name: string;
  org_type: string;
  status: string;
}

/** 当前主体所属的机构与其中的角色。 */
export interface Membership {
  organization: MembershipOrganization;
  member_role: string;
}
function normalizeMembership(value: unknown): Membership | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  const organization = record.organization as Record<string, unknown> | null | undefined;
  if (
    !organization ||
    typeof organization.id !== "string" ||
    typeof organization.name !== "string"
  ) {
    return null;
  }
  return {
    organization: {
      id: organization.id,
      name: organization.name,
      org_type: typeof organization.org_type === "string" ? organization.org_type : "",
      status: typeof organization.status === "string" ? organization.status : "",
    },
    member_role: typeof record.member_role === "string" ? record.member_role : "",
  };
}

export interface Page<T> {
  records: T[];
  total: number;
}

/** 后端对单个上传文件的限制（见 agora-api maxUploadBytes）。 */
export const MAX_UPLOAD_BYTES = 8 * 1024 * 1024;

export interface UploadedFile {
  id: string;
  content_url: string;
  mime_type: string;
}

/** 上传审核材料（营业执照等）；文件由 Nexus 存储，这里只拿到文件 id。 */
export async function uploadFile(file: File): Promise<UploadedFile> {
  const body = new FormData();
  body.append("file", file);

  const response = await fetch(endpoint("/crate-api/agora/v1/files"), {
    method: "POST",
    credentials: "include",
    headers: { Accept: "application/json" },
    body,
  });

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  const payload = (await parseJson(response)) as {
    id?: unknown;
    content_url?: unknown;
    mime_type?: unknown;
  } | null;
  if (!payload || typeof payload.id !== "string" || !payload.id) {
    throw new ApiError("unexpected upload response", 502);
  }

  return {
    id: payload.id,
    content_url: typeof payload.content_url === "string" ? payload.content_url : "",
    mime_type: typeof payload.mime_type === "string" ? payload.mime_type : "",
  };
}

/** 机构内岗位（含草稿）；与公开列表不同，可见全部状态。 */
export interface OrganizationPosition {
  id: string;
  organization_id: string;
  title: string;
  employment_type: string;
  work_mode: string;
  headcount: number;
  salary_negotiable: boolean;
  salary_min: number | null;
  salary_max: number | null;
  salary_unit: string;
  education: string;
  experience: string;
  city: string;
  status: string;
  created_at: string;
}

function normalizeOrganizationPosition(value: unknown): OrganizationPosition | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  if (typeof record.id !== "string" || typeof record.title !== "string") {
    return null;
  }
  return {
    id: record.id,
    organization_id: optionalText(record.organization_id),
    title: record.title,
    employment_type: optionalText(record.employment_type),
    work_mode: optionalText(record.work_mode),
    headcount: typeof record.headcount === "number" ? record.headcount : 1,
    salary_negotiable: record.salary_negotiable === true,
    salary_min: optionalNumber(record.salary_min),
    salary_max: optionalNumber(record.salary_max),
    salary_unit: optionalText(record.salary_unit),
    education: optionalText(record.education),
    experience: optionalText(record.experience),
    city: optionalText(record.city),
    status: optionalText(record.status),
    created_at: optionalText(record.created_at),
  };
}

export async function listOrganizationPositions(
  organizationID: string,
  options: { limit?: number; offset?: number } = {},
  signal?: AbortSignal,
): Promise<Page<OrganizationPosition>> {
  const query = new URLSearchParams();
  if (options.limit !== undefined) {
    query.set("limit", String(options.limit));
  }
  if (options.offset !== undefined) {
    query.set("offset", String(options.offset));
  }
  const search = query.toString();

  const response = await fetch(
    endpoint(
      `/crate-api/agora/v1/organizations/${encodeURIComponent(organizationID)}/positions${
        search ? `?${search}` : ""
      }`,
    ),
    {
      headers: { Accept: "application/json" },
      credentials: "include",
      signal,
    },
  );

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  return pageFrom(await parseJson(response), normalizeOrganizationPosition);
}

export interface CreatePositionInput {
  organization_id: string;
  title: string;
  employment_type: string;
  work_mode: string;
  headcount: number;
  salary_negotiable: boolean;
  salary_min?: number;
  salary_max?: number;
  salary_unit: string;
  education: string;
  experience: string;
  city: string;
  metadata?: { description?: string; requirements?: string };
}

/** 新建岗位，初始状态为「草稿」。 */
export async function createPosition(
  input: CreatePositionInput,
): Promise<OrganizationPosition> {
  const response = await fetch(endpoint("/crate-api/agora/v1/positions"), {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(input),
  });

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  const position = normalizeOrganizationPosition(await parseJson(response));
  if (!position) {
    throw new ApiError("unexpected position response", 502);
  }
  return position;
}

/** 岗位状态流转：草稿 → 招聘中 → 已暂停 / 已关闭。 */
export async function setPositionStatus(
  positionID: string,
  status: string,
): Promise<OrganizationPosition> {
  const response = await fetch(
    endpoint(`/crate-api/agora/v1/positions/${encodeURIComponent(positionID)}/status`),
    {
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
      },
      body: JSON.stringify({ status }),
    },
  );

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  const position = normalizeOrganizationPosition(await parseJson(response));
  if (!position) {
    throw new ApiError("unexpected position response", 502);
  }
  return position;
}

export interface CreateOrganizationInput {
  name: string;
  short_name?: string;
  org_type?: string;
  metadata?: { credit_code?: string; license_file_id?: string };
}

/** 创建机构：当前主体成为所有者，机构初始状态为「待审核」。 */
export async function createOrganization(
  input: CreateOrganizationInput,
): Promise<MembershipOrganization> {
  const response = await fetch(endpoint("/crate-api/agora/v1/organizations"), {
    method: "POST",
    credentials: "include",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
    },
    body: JSON.stringify(input),
  });

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  const payload = (await parseJson(response)) as Record<string, unknown> | null;
  if (!payload || typeof payload.id !== "string" || typeof payload.name !== "string") {
    throw new ApiError("unexpected organisation response", 502);
  }

  return {
    id: payload.id,
    name: payload.name,
    org_type: typeof payload.org_type === "string" ? payload.org_type : "",
    status: typeof payload.status === "string" ? payload.status : "",
  };
}

/** 公开岗位列表里用到的字段；后端只返回「招聘中且未过期」的岗位。 */
export interface PositionSummary {
  id: string;
  organization_id: string;
  organization_name: string;
  title: string;
  city: string;
  employment_type: string;
  education: string;
  experience: string;
  salary_min: number | null;
  salary_max: number | null;
  salary_unit: string;
  salary_negotiable: boolean;
  published_at: string | null;
}

function optionalNumber(value: unknown): number | null {
  return typeof value === "number" && Number.isFinite(value) ? value : null;
}

function optionalText(value: unknown): string {
  return typeof value === "string" ? value : "";
}

function normalizePosition(value: unknown): PositionSummary | null {
  if (!value || typeof value !== "object") {
    return null;
  }
  const record = value as Record<string, unknown>;
  if (typeof record.id !== "string" || typeof record.title !== "string") {
    return null;
  }
  return {
    id: record.id,
    organization_id: optionalText(record.organization_id),
    organization_name: optionalText(record.organization_name),
    title: record.title,
    city: optionalText(record.city),
    employment_type: optionalText(record.employment_type),
    education: optionalText(record.education),
    experience: optionalText(record.experience),
    salary_min: optionalNumber(record.salary_min),
    salary_max: optionalNumber(record.salary_max),
    salary_unit: optionalText(record.salary_unit),
    salary_negotiable: record.salary_negotiable === true,
    published_at: typeof record.published_at === "string" ? record.published_at : null,
  };
}

function pageFrom<T>(payload: unknown, normalize: (value: unknown) => T | null): Page<T> {
  const record = (payload ?? {}) as { records?: unknown; meta?: { total?: unknown } };
  const records = Array.isArray(record.records) ? record.records : [];
  const total = typeof record.meta?.total === "number" ? record.meta.total : records.length;
  return {
    records: records.map(normalize).filter((item): item is T => item !== null),
    total,
  };
}

/** 在招岗位列表；无需登录，分页参数与后端一致（limit / offset），可附关键词与城市。 */
export async function listPositions(
  options: { limit?: number; offset?: number; q?: string; city?: string } = {},
  signal?: AbortSignal,
): Promise<Page<PositionSummary>> {
  const query = new URLSearchParams();
  if (options.limit !== undefined) {
    query.set("limit", String(options.limit));
  }
  if (options.offset !== undefined) {
    query.set("offset", String(options.offset));
  }
  if (options.q) {
    query.set("q", options.q);
  }
  if (options.city) {
    query.set("city", options.city);
  }
  const search = query.toString();

  const response = await fetch(
    endpoint(`/crate-api/agora/v1/positions${search ? `?${search}` : ""}`),
    {
      headers: { Accept: "application/json" },
      credentials: "include",
      signal,
    },
  );

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  return pageFrom(await parseJson(response), normalizePosition);
}

/** 读取当前登录主体所属的机构；未登录或会话失效时抛出 ApiError(401)。 */
export async function listMyOrganizations(signal?: AbortSignal): Promise<Membership[]> {
  const response = await fetch(endpoint("/crate-api/agora/v1/organizations/mine"), {
    headers: { Accept: "application/json" },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new ApiError(await errorMessage(response), response.status);
  }

  const payload = (await parseJson(response)) as { records?: unknown } | null;
  const records = Array.isArray(payload?.records) ? payload.records : [];
  return records
    .map(normalizeMembership)
    .filter((membership): membership is Membership => membership !== null);
}
export async function logout(): Promise<void> {
  const response = await fetch(endpoint("/crate-api/agora/v1/auth/session"), {
    method: "DELETE",
    credentials: "include",
    headers: { Accept: "application/json" },
  });

  if (!response.ok && response.status !== 401) {
    throw new AuthError(await errorMessage(response), response.status);
  }
}

export async function getStatus(signal?: AbortSignal): Promise<ApiStatus> {
  const response = await fetch(endpoint("/crate-api/agora/v1/status"), {
    headers: { Accept: "application/json" },
    credentials: "include",
    signal,
  });

  if (!response.ok) {
    throw new StatusError(response.status, "http");
  }

  const payload = (await parseJson(response)) as Partial<ApiStatus> | null;
  if (!payload || typeof payload.status !== "string") {
    throw new StatusError(0, "payload");
  }

  return {
    service: typeof payload.service === "string" ? payload.service : "agora-api",
    status: payload.status,
    time: typeof payload.time === "string" ? payload.time : "",
  };
}

export async function getReadiness(signal?: AbortSignal): Promise<ApiReadiness> {
  const response = await fetch(endpoint("/readyz"), {
    headers: { Accept: "application/json" },
    credentials: "include",
    signal,
  });

  const payload = (await parseJson(response)) as Partial<ApiReadiness> | null;
  return { status: response.ok ? (payload?.status ?? "unavailable") : "unavailable" };
}
