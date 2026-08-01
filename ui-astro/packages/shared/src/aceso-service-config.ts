type ServiceName = "aceso" | "identity" | "nexus";

interface ServiceEndpoint {
  host: string;
  port: number;
  basePath: string;
}

const publicEnv = import.meta.env as Record<string, string | undefined>;
const legacyAcesoUrl = parseUrl(import.meta.env.PUBLIC_API_URL, "PUBLIC_API_URL");

function parseUrl(value: string | undefined, name: string): URL | undefined {
  const configured = value?.trim();
  if (!configured) return undefined;
  try {
    return new URL(configured);
  } catch {
    throw new Error(`${name} 必须是有效的 URL`);
  }
}

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

const defaultHost = legacyAcesoUrl?.hostname || "127.0.0.1";
const defaultAcesoPort = legacyAcesoUrl?.port ? Number(legacyAcesoUrl.port) : 8422;

export const acesoServices: Record<ServiceName, ServiceEndpoint> = {
  aceso: {
    host: serviceHost("PUBLIC_ACESO_API_HOST", defaultHost),
    port: servicePort("PUBLIC_ACESO_API_PORT", defaultAcesoPort),
    basePath: "/crate-api",
  },
  identity: {
    host: serviceHost("PUBLIC_IDENTITY_API_HOST", defaultHost),
    port: servicePort("PUBLIC_IDENTITY_API_PORT", 8420),
    basePath: "/crate-api/identity/v1",
  },
  nexus: {
    host: serviceHost("PUBLIC_NEXUS_API_HOST", defaultHost),
    port: servicePort("PUBLIC_NEXUS_API_PORT", 8421),
    basePath: "/crate-api/shared/v1",
  },
};

export function serviceBase(service: ServiceName): string {
  const endpoint = acesoServices[service];
  const protocol = legacyAcesoUrl?.protocol || "http:";
  return `${protocol}//${endpoint.host}:${endpoint.port}${endpoint.basePath}`;
}
