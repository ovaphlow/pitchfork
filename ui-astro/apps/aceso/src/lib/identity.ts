import { useCallback, useEffect, useMemo, useState } from "react";
import { listIdentitySubjects, type IdentitySubject } from "@pitchfork/shared/aceso";

/**
 * 认证主体目录（IdP）→ 姓名映射。
 *
 * 背景：`followup_plans.assignee`、`followup_records.operator`、
 * `vital_sign_records.recorded_by` / `reviewed_by`、`medical_orders.nurse_checked_by`
 * 等字段存的是认证主体 ID（ULID），直接渲染会出现「01KYE3F6CPNJS16RHT01M64D8B」。
 * 主体目录不在 Aceso PostgreSQL 内（`libs/users` 已无源码、无用户表），
 * 只能通过 IdP 的 `GET /crate-api/identity/v1/subjects` 解析。
 *
 * 约束与降级（与 `NursingPage`/`PharmacyPage` 既有口径一致）：
 *  1. 目录接口需要 `identity.admin` 角色，且服务端 `limit` 上限为 100，
 *     因此只覆盖目录第一页；未命中者按原始 ID 显示，绝不隐藏信息。
 *  2. 拉取失败（404/403/网络）静默降级，不影响主流程渲染。
 *  3. 存量自由文本（如手输的医生姓名）不是主体 ID，`subjectLabel` 原值回退。
 */
export interface SubjectDirectory {
  /** 已加载的主体（目录为空表示未加载成功或确实无成员） */
  subjects: IdentitySubject[];
  /** 主体 ID → 姓名 */
  subjectMap: Map<string, string>;
  /** 主体 ID → 姓名；无法解析时回退原值，空值回退 "-" */
  subjectLabel: (value: string | null | undefined) => string;
}

export function useSubjectDirectory(): SubjectDirectory {
  const [subjects, setSubjects] = useState<IdentitySubject[]>([]);

  useEffect(() => {
    let active = true;
    listIdentitySubjects(1, 100)
      .then((response) => {
        if (active) setSubjects(response.records);
      })
      .catch(() => {
        /* 静默失败：名称解析失败不阻塞主流程 */
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

  return { subjects, subjectMap, subjectLabel };
}
