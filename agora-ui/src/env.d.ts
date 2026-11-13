/// <reference types="astro/client" />

interface ImportMetaEnv {
  /** Base URL of agora-api. Required by the API client. */
  readonly PUBLIC_API_URL?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
