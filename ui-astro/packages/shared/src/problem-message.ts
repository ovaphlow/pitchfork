/** 服务端错误体的公共形状（Problem Details 的超集，含权限闸门字段）。 */
export interface ApiProblem {
  error?: string;
  detail?: string;
  title?: string;
  /** 037/038 权限闸门：403 时告诉前端"缺哪个权限码"。 */
  required_permission?: string;
}

/**
 * 把服务端错误翻成用户能照做的提示。
 *
 * 权限闸门在无权限时回 403 + `required_permission`，上游故障回 503；
 * 这两种若原样透出英文错误码，用户只会看到 `forbidden`，不知道该找谁要哪个权限。
 *
 * 纯函数、不依赖运行环境，便于逐字锁定文案（见 `apps/aceso/tests/api-error-messages.test.ts`）。
 */
export function problemMessage(
  status: number,
  problem: ApiProblem | null,
  responseText: string,
): string {
  const requiredPermission = problem?.required_permission?.trim();
  if (status === 403 && requiredPermission) {
    return `权限不足：需要「${requiredPermission}」权限。请联系管理员在「角色管理」中把带有该权限码的角色分配给你。`;
  }
  if (status === 503) {
    if (problem?.error === "permission service unavailable") {
      return "角色权限服务暂时不可用，本次操作未执行，请稍后重试。";
    }
    if (problem?.error === "identity service unavailable") {
      return "认证服务暂时不可用，请稍后重试（未被登出）。";
    }
  }
  return problem?.error || problem?.detail || problem?.title || responseText || `请求失败 (${status})`;
}
