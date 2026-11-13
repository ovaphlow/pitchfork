import { derived, get, writable } from "svelte/store";

import { ApiError, AuthError, ConfigurationError, StatusError } from "../lib/api";
import { en, zhCN, type Messages } from "./messages";

export type Locale = "zh-CN" | "en";

export const catalog: Record<Locale, Messages> = {
  "zh-CN": zhCN,
  en,
};

const STORAGE_KEY = "qimu.locale";

function resolveTag(value: string | null | undefined): Locale | null {
  if (!value) {
    return null;
  }
  const tag = value.trim().toLowerCase();
  if (tag === "zh" || tag.startsWith("zh-")) {
    return "zh-CN";
  }
  if (tag === "en" || tag.startsWith("en-")) {
    return "en";
  }
  return null;
}

/**
 * Explicit choice > saved choice > browser/OS preference > 中文.
 *
 * `navigator.languages` already reflects the operating-system/browser
 * preference and is ordered from most to least preferred. Only a manual
 * switch is persisted; otherwise the next visit follows the browser again.
 */
export function detectLocale(): Locale {
  if (typeof window === "undefined") {
    return "zh-CN";
  }

  try {
    const saved = resolveTag(window.localStorage.getItem(STORAGE_KEY));
    if (saved) {
      return saved;
    }
  } catch {
    // localStorage can be unavailable in private modes.
  }

  const tags =
    navigator.languages && navigator.languages.length > 0
      ? navigator.languages
      : [navigator.language];

  for (const tag of tags) {
    const locale = resolveTag(tag);
    if (locale) {
      return locale;
    }
  }
  return "zh-CN";
}

export const locale = writable<Locale>(detectLocale());

export const t = derived(locale, ($locale) => catalog[$locale]);

export function setLocale(next: Locale) {
  locale.set(next);
  try {
    window.localStorage.setItem(STORAGE_KEY, next);
  } catch {
    // Persisting the preference is optional.
  }
}

export function toggleLocale() {
  setLocale(get(locale) === "zh-CN" ? "en" : "zh-CN");
}

export function describeRequestError(
  cause: unknown,
  messages: Messages,
  origin: string,
): string {
  if (cause instanceof ConfigurationError) {
    return messages.errors.notConfigured;
  }
  if (cause instanceof StatusError) {
    return cause.kind === "payload"
      ? messages.errors.statusPayload
      : messages.errors.statusHttp(cause.status);
  }
  if (cause instanceof AuthError) {
    return messages.errors.authStatus(cause.status);
  }
  if (cause instanceof ApiError) {
    return messages.errors.requestFailed(cause.status);
  }
  if (cause instanceof TypeError) {
    return messages.errors.network(origin);
  }
  return messages.errors.unknown;
}
