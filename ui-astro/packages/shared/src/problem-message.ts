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
 * 权限闸门在无权限时回 403 + `required_permission`，上游故障回 503，
 * 删除仍被引用的部门/角色时 Nexus 回 409 + 英文 detail；
 * 这几种若原样透出英文，用户只会看到 `forbidden` 或英文句子，不知道该找谁要哪权限、
 * 还是该先调整归属。
 *
 * 纯函数、不依赖运行环境，便于逐字锁定文案（见 `apps/aceso/tests/api-error-messages.test.ts`）。
 */

/**
 * 删除冲突（409）的中文映射：句式逐字对齐 Nexus 的英文错误约定
 * （`service-nexus-shared/src/departments/mod.rs` / `src/roles/mod.rs`）。
 * 只在这两条句式上动手：其余 409（如重复编码）继续走通用兜底，原样透出 detail。
 */
const DEPARTMENT_DELETE_CONFLICT = /^department is assigned to (\d+) subject\(s\); unassign before deleting$/;
const ROLE_DELETE_CONFLICT = /^role is assigned to (\d+) subject\(s\); unassign before deleting$/;

export function problemMessage(
  status: number,
  problem: ApiProblem | null,
  responseText: string,
): string {
  const requiredPermission = problem?.required_permission?.trim();
  if (status === 403) {
    if (requiredPermission) {
      return `权限不足：需要「${requiredPermission}」权限。请联系管理员在「角色管理」中把带有该权限码的角色分配给你。`;
    }
    // 043：没有权限码的 403（IDP 的管理员接口、Nexus 的平台管理员门禁）此前会原样透出英文
    // `not authorized`，业务账号只看到一句英文、不知道是权限问题还是数据为空。
    return "权限不足：该操作需要管理员权限，请联系平台管理员。";
  }
  if (status === 503) {
    if (problem?.error === "permission service unavailable") {
      return "角色权限服务暂时不可用，本次操作未执行，请稍后重试。";
    }
    if (problem?.error === "identity service unavailable") {
      return "认证服务暂时不可用，请稍后重试（未被登出）。";
    }
  }
  if (status === 409) {
    const detail = problem?.detail ?? "";
    const departmentConflict = DEPARTMENT_DELETE_CONFLICT.exec(detail);
    if (departmentConflict) {
      return `该部门下还有 ${departmentConflict[1]} 名成员，请先调整归属后再删除。`;
    }
    const roleConflict = ROLE_DELETE_CONFLICT.exec(detail);
    if (roleConflict) {
      return `该角色已分配给 ${roleConflict[1]} 个用户，请先取消分配后再删除。`;
    }
  }
  return problem?.error || problem?.detail || problem?.title || responseText || `请求失败 (${status})`;
}
