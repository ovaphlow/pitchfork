import { useEffect, useState } from "react";

/**
 * 054 F1：前端权限感知（菜单/入口显隐）。
 *
 * 定位与边界（计划 §7 F1、§8.6）：
 * - 只做**前端显隐**：让没有对应权限码的账号看不到入口。**后端仍是唯一强制点**，
 *   绕过前端直接请求写路由依然 403；前端隐藏不替代 403，也不承担鉴权职责。
 * - 权限码取**本人**：`GET /crate-api/v1/subject-permissions?subject_id=<本人>`
 *   （platformd 共享 API）。platformd 只允许查自己（043 D5：查他人需 `access:manage`），所以先取会话
 *   `subject_id`，再用共享客户端既有导出 `getSubjectPermissions` 查本人。
 * - **平台管理员短路**：后端 `SubjectPermissionGate` 对 `platform_admin` 直接放行，
 *   而平台管理员通常**不持有任何权限码**。前端必须同口径——`platformAdmin === true`
 *   时跳过权限过滤、菜单全量可见（见 `useSubjectPermissions`），否则管理员的菜单会全空，
 *   与后端语义相反。
 * - 请求一律走 `@pitchfork/shared/aceso`（复用 token 注入 / JSON / 401 处理），
 *   本模块不写裸 `fetch`、不拼接产品 URL。
 *
 * 为什么共享客户端用动态 `import()`：本模块同时承载**纯函数**菜单显隐逻辑，必须能被
 * `node --test apps/aceso/tests/*.test.ts` 直接加载（见 `tests/permissions.test.ts`）；
 * 而 `@pitchfork/shared/aceso` 内部使用无扩展名相对导入（`./aceso-service-config`），
 * Node 的 ESM 解析器在测试运行时无法链接它——静态导入会让测试连纯函数都取不到。
 * 浏览器构建（Vite/Astro）对静态与动态导入一视同仁，运行时仍是同一个客户端实现。
 */

/**
 * 权限码规范化：去首尾空白、丢弃空串、按首次出现去重。
 *
 * 保持输入顺序（稳定输出），便于测试断言与跨组件比较；服务端返回的并集本身已去重，
 * 这里只做前端防御，不改变码的语义。
 */
export function normalizePermissionCodes(codes: readonly string[] | null | undefined): string[] {
  if (!codes) return [];
  const seen = new Set<string>();
  const normalized: string[] = [];
  for (const raw of codes) {
    const code = raw.trim();
    if (code === "" || seen.has(code)) continue;
    seen.add(code);
    normalized.push(code);
  }
  return normalized;
}

/**
 * 入口是否满足所需权限码。
 *
 * - `platformAdmin === true`：**直接放行**（后端 `SubjectPermissionGate` 对平台管理员
 *   短路，平台管理员不持有权限码；前端同口径，菜单全量可见）；
 * - `required` 缺省或为空数组 = 该入口不设前端门槛（既有菜单项保持原行为）；
 * - 语义为**任一命中**（OR）：声明多个码时，拥有其中任意一个即可见；
 * - `codes === null` 表示权限**未知**（读取中或读取失败）→ 不显示受限入口。
 *   与后端 `SubjectPermissionGate` 的 fail-closed 口径一致：platformd 不可用时后端也拒绝
 *   受保护写操作，前端此时不应给出必然 403 的入口。空数组（已确认无任何权限码）同样不可见。
 */
export function hasRequiredPermissions(
  required: readonly string[] | null | undefined,
  codes: readonly string[] | null,
  platformAdmin = false,
): boolean {
  if (platformAdmin) return true;
  const needed = normalizePermissionCodes(required);
  if (needed.length === 0) return true;
  if (codes === null) return false;
  const owned = new Set(normalizePermissionCodes(codes));
  return needed.some((code) => owned.has(code));
}

/** 可做权限过滤的菜单/入口最小形状；`Sidebar.tsx` 的 `MenuChild` 结构上兼容本接口。 */
export interface PermissionAwareEntry {
  /** 访问该入口所需的权限码；**任一命中即可见**；缺省或空数组 = 不设前端门槛 */
  requiredPermissions?: readonly string[];
}

/** 单个菜单/入口是否可见（`requiredPermissions` 语义见 `hasRequiredPermissions`）。 */
export function isMenuEntryVisible(
  entry: PermissionAwareEntry,
  codes: readonly string[] | null,
  platformAdmin = false,
): boolean {
  return hasRequiredPermissions(entry.requiredPermissions, codes, platformAdmin);
}

/**
 * 过滤菜单/入口：未声明 `requiredPermissions` 的项在任何权限码集合下都保留；
 * `platformAdmin === true` 时全量保留（平台管理员短路，见 `hasRequiredPermissions`）。
 *
 * 纯函数、不可变——返回新数组，不修改入参，也不改变项之间的相对顺序
 * （按域排序由调用方在过滤后自行处理）。
 */
export function filterVisibleMenuEntries<T extends PermissionAwareEntry>(
  entries: readonly T[],
  codes: readonly string[] | null,
  platformAdmin = false,
): T[] {
  if (platformAdmin) return [...entries];
  return entries.filter((entry) => isMenuEntryVisible(entry, codes, platformAdmin));
}

/** `loading`=读取中；`ready`=已拿到权限码；`error`=读取失败（按无权限处理）。 */
export type SubjectPermissionsStatus = "loading" | "ready" | "error";

export interface SubjectPermissionsState {
  status: SubjectPermissionsStatus;
  /** `null` = 权限未知（读取中或读取失败）；`[]` = 已确认该账号没有任何权限码 */
  codes: string[] | null;
  /** 会话主体 ID；未取到时为 `null` */
  subjectId: string | null;
  /**
   * 平台管理员（会话 `platform_admin === true`）。后端对其短路放行，前端跳过权限过滤。
   * 权限未知（读取中/读取失败）时一律 `false`，不放行受限入口。
   */
  platformAdmin: boolean;
}

const INITIAL_STATE: SubjectPermissionsState = {
  status: "loading",
  codes: null,
  subjectId: null,
  platformAdmin: false,
};

/**
 * 读取**本人**权限码与平台管理员标记（会话 → 本人 `subject_id` → platformd 共享 `/subject-permissions`）。
 *
 * 两个函数都是 `@pitchfork/shared/aceso` 的既有导出（`getCurrentSession`、
 * `getSubjectPermissions`），本任务不新增、不改动共享客户端。
 * `getCurrentSession(false)`：401 时交给调用方按「权限未知」处理，不在此处跳登录。
 * `platform_admin` 缺失（旧版 IDP）一律按 `false`，与 `currentUser.ts` 同口径。
 */
export async function fetchOwnPermissions(): Promise<SubjectPermissionsState> {
  const { getCurrentSession, getSubjectPermissions } = await import("@pitchfork/shared/aceso");
  const session = await getCurrentSession(false);
  const platformAdmin = session.platform_admin === true;
  // 平台管理员不持有权限码，后端短路放行；此处仍读一次会话以拿到 subject_id，
  // 但不再为管理员发多余的权限查询请求。
  const codes = platformAdmin
    ? []
    : normalizePermissionCodes((await getSubjectPermissions(session.subject_id)).permission_codes);
  return {
    status: "ready",
    codes,
    subjectId: session.subject_id,
    platformAdmin,
  };
}

/**
 * 订阅本人权限码与平台管理员标记；菜单（`Sidebar`）与后续入口据此决定
 * `requiredPermissions` 项是否显示。
 *
 * 读取失败一律落到 `{status:"error", codes:null, platformAdmin:false}`——不伪造权限码，
 * 也不放行受限入口（平台管理员仅在会话明确返回 `platform_admin === true` 时才短路）。
 */
export function useSubjectPermissions(): SubjectPermissionsState {
  const [state, setState] = useState<SubjectPermissionsState>(INITIAL_STATE);

  useEffect(() => {
    let active = true;
    fetchOwnPermissions()
      .then((next) => {
        if (active) setState(next);
      })
      .catch(() => {
        if (active) setState({ status: "error", codes: null, subjectId: null, platformAdmin: false });
      });
    return () => {
      active = false;
    };
  }, []);

  return state;
}
