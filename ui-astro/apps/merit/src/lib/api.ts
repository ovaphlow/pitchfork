/**
 * API 基址工具：仅做地址构造，不含 token / 401 清理逻辑。
 *
 * 跨域访问后端服务：请求基址由 PUBLIC_API_URL 构造为 `{base}/crate-api/...`
 * 的绝对 URL，浏览器直接请求异源地址；后端 CORS 允许头由后端自行配置。
 */

const CRATE_API_PREFIX = "crate-api";

export function apiBaseUrl(): string {
  const base = (import.meta.env.PUBLIC_API_URL ?? "").trim().replace(/\/+$/, "");
  return base ? `${base}/${CRATE_API_PREFIX}` : `/${CRATE_API_PREFIX}`;
}

export function apiUrl(path: string): string {
  const normalized = path.replace(/^\/+/, "");
  return `${apiBaseUrl()}/${normalized}`;
}
