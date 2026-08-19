// 理论培训（merit）服务基址配置。
//
// 与 aceso-service-config.ts 的模式一致但独立成模块：服务端点为
// service-prototype（默认 127.0.0.1:8423），统一前缀为
// /crate-api/prototype/v1。本模块不修改 aceso 既有服务条目。

export interface MeritServiceEndpoint {
  host: string;
  port: number;
  basePath: string;
}

const publicEnv = import.meta.env as Record<string, string | undefined>;

function serviceHost(name: string, fallback: string): string {
  const configured = publicEnv[name]?.trim();
  return configured || fallback;
}

function servicePort(name: string, fallback: number): number {
  const configured = publicEnv[name]?.trim();
  if (!configured) return fallback;
  const port = Number(configured);
  if (!Number.isInteger(port) || port < 1 || port > 65535) {
    throw new Error(`${name} 必须是 1-65535 的端口`);
  }
  return port;
}

/** 理论培训（merit）服务端点：service-prototype，默认 127.0.0.1:8423 */
export const meritService: MeritServiceEndpoint = {
  host: serviceHost("PUBLIC_MERIT_API_HOST", "127.0.0.1"),
  port: servicePort("PUBLIC_MERIT_API_PORT", 8423),
  basePath: "/crate-api/prototype/v1",
};

/** 理论培训（merit）API 基址：`http://{host}:{port}/crate-api/prototype/v1` */
export function meritBase(): string {
  return `http://${meritService.host}:${meritService.port}${meritService.basePath}`;
}
