import type { IdentitySubject } from "@pitchfork/shared/aceso";

/** 042：当前登录主体 + 是否平台管理员（代操作门禁）。 */
export interface CurrentUser {
  id: string;
  name: string;
  platformAdmin: boolean;
}

const selectClass =
  "h-10 rounded-md border border-border bg-surface px-3 text-sm text-fg focus:outline-none focus-visible:ring-2 focus-visible:ring-accent";

interface OperatorPickerProps {
  /** 只读态用于 `id` 锚点；下拉态用于 `htmlFor` */
  id: string;
  label?: string;
  currentUser: CurrentUser | null;
  /** 仅在平台管理员下非空（候选来自管理员接口） */
  subjects: IdentitySubject[];
  value: string;
  onChange: (value: string) => void;
  /** 管理员目录加载失败提示；业务账号不需要目录，故为空 */
  directoryError?: string;
  /** 下拉首项文案（空值 = 本人，服务端按会话落库） */
  selfOptionLabel?: string;
  /**
   * 046：是否允许代操作（下拉挑选他人）。默认 `true`（042 口径：仅平台管理员可代操作）。
   * 临床署名（医嘱 `doctor`、诊断/病程 `physician`）传 `false` —— 署名即责任、**不开放代签**，
   * 任何账号（含平台管理员）都只显示只读的「<姓名>（当前登录用户）」。
   */
  allowDelegation?: boolean;
}

/**
 * 042 D9：业务操作人字段的分流渲染（药房与护理共用）。
 *
 * - **平台管理员**：下拉挑选他人（候选来自管理员接口 `/identity/v1/subjects`），
 *   option value 是**主体 ID** —— 此前多处误用 `display_name`，提交的是姓名而非主体 ID；
 * - **其他账号**：只读「<姓名>（当前登录用户）」，提交体**不带**操作人字段，
 *   由服务端按认证会话落库（非管理员提交他人会被服务端 `400 operator cannot be delegated` 拒绝）；
 * - 目录加载失败时给出可见提示，不再静默吞错（此前表现为"下拉为空且无从判断原因"，
 *   表单又强制必填 → 业务账号做不了本职工作）。
 *
 * 046：新增 [allowDelegation] —— 传 `false` 时即使是平台管理员也只读（临床署名字段专用）。
 */
export default function OperatorPicker({
  id,
  label = "操作人",
  currentUser,
  subjects,
  value,
  onChange,
  directoryError,
  selfOptionLabel = "本人（当前登录用户）",
  allowDelegation = true,
}: OperatorPickerProps) {
  if (!allowDelegation || !currentUser?.platformAdmin) {
    return (
      <div className="flex flex-col gap-1.5">
        <span className="text-sm font-medium text-fg-muted">{label}</span>
        <p className="text-sm text-fg" id={id}>
          {currentUser ? `${currentUser.name}（当前登录用户）` : "读取中…"}
        </p>
      </div>
    );
  }
  return (
    <div className="flex flex-col gap-1.5">
      <label className="text-sm font-medium text-fg-muted" htmlFor={id}>
        {label}
      </label>
      <select id={id} className={selectClass} value={value} onChange={(event) => onChange(event.target.value)}>
        <option value="">{selfOptionLabel}</option>
        {subjects.map((subject) => (
          <option key={subject.id} value={subject.id}>
            {subject.display_name}
          </option>
        ))}
      </select>
      {directoryError ? <span className="text-xs text-danger">{directoryError}</span> : null}
    </div>
  );
}
