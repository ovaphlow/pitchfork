import { writable } from "svelte/store";

/**
 * 登录身份：求职者与招聘方共用同一套账号与认证接口。
 *
 * 身份只决定登录后的默认工作台，不写入后端、不改变注册/登录请求，
 * 也不参与权限判定——招聘方能否发岗位仍由机构成员角色决定。
 */
export type Intent = "job_seeker" | "recruiter";

const STORAGE_KEY = "qimu.intent";

function resolveIntent(value: string | null | undefined): Intent | null {
  const tag = value?.trim();
  return tag === "job_seeker" || tag === "recruiter" ? tag : null;
}

/** 已保存的身份优先，否则默认按求职者进入。 */
export function detectIntent(): Intent {
  if (typeof window === "undefined") {
    return "job_seeker";
  }
  try {
    return resolveIntent(window.localStorage.getItem(STORAGE_KEY)) ?? "job_seeker";
  } catch {
    // localStorage 在隐私模式下可能不可用。
    return "job_seeker";
  }
}

export const intent = writable<Intent>(detectIntent());

export function setIntent(next: Intent) {
  intent.set(next);
  try {
    window.localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // 持久化身份是可选的。
  }
}
