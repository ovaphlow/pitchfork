import { useEffect, useState } from "react";
import { getCurrentSession } from "@pitchfork/shared/aceso";
import type { CurrentUser } from "../components/OperatorPicker";

/**
 * 当前登录主体（042 `/session` 的 `subject_id` / `display_name` / `platform_admin`）。
 *
 * 046：临床署名（医嘱 `doctor`、诊断/病程 `physician`）改为**只读展示当前登录用户**，
 * 提交体不再带该字段，由服务端按会话派生；本 hook 提供只读展示所需的姓名与主体 ID。
 *
 * 对外只暴露 `CurrentUser | null`（`null` = 仍在读取或读取失败）——与 042 的
 * `OperatorPicker` 口径一致：读取失败时字段显示「读取中…」，**不伪造**一个操作人。
 * 服务端才是署名的权威来源，前端拿不到会话也不影响落库值。
 */
export function useCurrentUser(): CurrentUser | null {
  const [currentUser, setCurrentUser] = useState<CurrentUser | null>(null);

  useEffect(() => {
    let active = true;
    getCurrentSession(false)
      .then((session) => {
        if (!active) return;
        setCurrentUser({
          id: session.subject_id,
          name: session.display_name?.trim() || session.subject_id,
          platformAdmin: session.platform_admin === true,
        });
      })
      .catch(() => {
        if (!active) return;
        setCurrentUser(null);
      });
    return () => {
      active = false;
    };
  }, []);

  return currentUser;
}
