interface ProblemDetails {
  detail?: string;
  title?: string;
}

interface RequestOptions {
  csrf?: boolean;
  redirectOnUnauthorized?: boolean;
}

export interface IdentitySession {
  subject_id: string;
  access: "完整";
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
    throw new Error(problem?.detail || problem?.title || responseText || `请求失败 (${response.status})`);
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
      headers: { "Content-Type": "application/x-www-form-urlencoded" },
      body,
      credentials: "include",
    });
  } catch {
    throw new Error("无法连接到认证服务，请稍后重试");
  }

  if (!response.ok) {
    await readResponse(response, false);
  }

  if (response.redirected && response.url.includes("/password")) {
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
