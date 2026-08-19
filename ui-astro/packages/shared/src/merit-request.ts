// 理论培训（merit）共享请求封装。
//
// 沿用 aceso.ts 的 request 模式：token 注入（CSRF cookie）、401 重定向、
// 非 2xx 抛 ApiRequestError（含 status 与 {error} 错误体解析）、204 返回
// void。ApiRequestError 直接复用 aceso.ts 的既有导出，保证整个 shared 包
// 内错误类身份唯一，且不修改 aceso.ts 的既有导出签名与行为。

import { ApiRequestError } from "./aceso";
import { meritBase } from "./merit-service-config";

export { ApiRequestError };

export interface MeritRequestOptions {
  csrf?: boolean;
  redirectOnUnauthorized?: boolean;
}

interface MeritProblemDetails {
  error?: string;
  detail?: string;
  title?: string;
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
  const body: MeritProblemDetails | T | null =
    contentType.includes("application/json") || contentType.includes("application/problem+json")
      ? (() => {
          try {
            return JSON.parse(responseText || "null") as MeritProblemDetails | T | null;
          } catch {
            return null;
          }
        })()
      : null;

  // 204 No Content（DELETE 等）没有响应体，调用方按 void 消费。
  if (response.status === 204) {
    return undefined as T;
  }

  if (!response.ok) {
    if (response.status === 401 && redirectOnUnauthorized) {
      redirectToLogin();
    }
    const problem = body as MeritProblemDetails | null;
    throw new ApiRequestError(
      response.status,
      problem?.error || problem?.detail || problem?.title || responseText || `请求失败 (${response.status})`,
    );
  }
  return body as T;
}

/**
 * merit 客户端共享请求封装：所有资源方法都经由本函数发出。
 * path 为相对基址（/crate-api/prototype/v1）的路径，例如 "/courses"。
 */
export async function meritRequest<T>(
  path: string,
  init: RequestInit = {},
  { csrf = false, redirectOnUnauthorized = true }: MeritRequestOptions = {},
): Promise<T> {
  const url = `${meritBase()}${path}`;
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
