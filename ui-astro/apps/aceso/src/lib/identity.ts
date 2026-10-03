import { useCallback, useEffect, useMemo, useState } from "react";
import { listIdentityDirectory, type IdentityDirectoryEntry } from "@pitchfork/shared/aceso";

/**
 * 认证主体目录（IdP）→ 姓名映射。
 *
 * 背景：`medical_orders.nurse_checked_by`、`nursing_task_executions.executor`、
 * `nursing_assessments.assessor`、`followup_records.operator`、`vital_sign_records.recorded_by`
 * 等字段存的是认证主体 ID（ULID），直接渲染会出现「01M3ZR3CDAQ6ZKHCYD065RSYMT」。
 * 主体目录不在 Aceso PostgreSQL 内，只能通过 IdP 解析。
 *
 * 044 起改用**业务人员目录** `GET /crate-api/identity/v1/directory`：
 *
 *  - 任何登录用户都可读（此前用管理接口 `/subjects`，业务账号 403 → 空 map → 满屏 ULID）；
 *  - 只返回 `id` + `display_name`、只含启用主体；
 *  - 上限 200（默认 50），足够覆盖当前规模的下拉与姓名解析。
 *
 * 约束与降级：
 *  1. 未命中者按原始 ID 显示，绝不隐藏信息（存量自由文本如手输医生姓名同样原值回退）；
 *  2. 拉取失败**不再静默吞错**：暴露 `error`，由页面决定是否提示（此前静默导致
 *     「下拉为空且无从判断原因」，表单又强制必填 → 业务账号做不了本职工作）；
 *  3. 管理台的人员管理仍走管理员接口 `listIdentitySubjects`，不使用本 hook。
 */
export interface SubjectDirectory {
  /** 已加载的主体（空数组表示未加载成功或确实无成员） */
  subjects: IdentityDirectoryEntry[];
  /** 主体 ID → 姓名 */
  subjectMap: Map<string, string>;
  /** 主体 ID → 姓名；无法解析时回退原值，空值回退 "-" */
  subjectLabel: (value: string | null | undefined) => string;
  /** 目录加载失败提示；空串表示成功（或仍在加载） */
  error: string;
}

export function useSubjectDirectory(): SubjectDirectory {
  const [subjects, setSubjects] = useState<IdentityDirectoryEntry[]>([]);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    listIdentityDirectory({ limit: 200 })
      .then((response) => {
        if (!active) return;
        setSubjects(response.records);
        setError("");
      })
      .catch(() => {
        if (!active) return;
        setSubjects([]);
        setError("无法加载人员目录，姓名将显示为主体 ID");
      });
    return () => {
      active = false;
    };
  }, []);

  const subjectMap = useMemo(
    () => new Map(subjects.map((subject) => [subject.id, subject.display_name])),
    [subjects],
  );

  const subjectLabel = useCallback(
    (value: string | null | undefined) => {
      const trimmed = value?.trim();
      if (!trimmed) return "-";
      return subjectMap.get(trimmed) ?? trimmed;
    },
    [subjectMap],
  );

  return { subjects, subjectMap, subjectLabel, error };
}
