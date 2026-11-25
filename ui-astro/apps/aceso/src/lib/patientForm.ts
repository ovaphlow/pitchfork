/**
 * 长者档案录入的字段校验 —— 纯函数，无 React、无副作用。
 *
 * 口径：**只做「格式明显不对」的简单拦截**，不查库、不算身份证校验位、不判重
 * （身份证号唯一性由 `healthcare.patients.id_card_no` 唯一约束 + 服务端错误返回兜底）。
 *
 *  - 姓名：必填。
 *  - 身份证号：可留空（历史档案与暂无证件的长者都要能建档）；填写则须为
 *    18 位（末位可为 X/x）或 15 位老号码。
 *  - 本人联系电话 / 联系人电话：可留空；填写则须为 11 位手机号（1 开头、第二位 3-9）。
 *    固话、分机、带分隔符的写法一律按非法处理——两个电话字段在养老场景都按手机号口径填写。
 */

/** 18 位（末位 X）或 15 位身份证号 */
const ID_CARD_RE = /^(?:\d{17}[\dXx]|\d{15})$/;
/** 11 位手机号 */
const MOBILE_RE = /^1[3-9]\d{9}$/;

/** 表单里参与校验的字段（结构子集，便于单测直接构造） */
export interface PatientFormValues {
  name: string;
  idCardNo: string;
  phone: string;
  /** 紧急联系人电话 */
  emergencyPhone: string;
}

/** 字段级错误：键存在即代表该字段不合法 */
export interface PatientFormErrors {
  name?: string;
  idCardNo?: string;
  phone?: string;
  emergencyPhone?: string;
}

/** 身份证号：空串视为未填（通过）；填写则校验长度与字符 */
export function validateIdCardNo(value: string): string | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  if (!ID_CARD_RE.test(trimmed)) return "身份证号应为 18 位（末位可为 X）或 15 位";
  return undefined;
}

/** 手机号：空串视为未填（通过）；填写则校验 11 位手机号 */
export function validateMobilePhone(value: string): string | undefined {
  const trimmed = value.trim();
  if (!trimmed) return undefined;
  if (!MOBILE_RE.test(trimmed)) return "请输入 11 位手机号，例如 13800000000";
  return undefined;
}

/** 汇总字段级错误；返回空对象表示可以提交 */
export function validatePatientForm(values: PatientFormValues): PatientFormErrors {
  const errors: PatientFormErrors = {};
  const name = validateName(values.name);
  if (name) errors.name = name;
  const idCardNo = validateIdCardNo(values.idCardNo);
  if (idCardNo) errors.idCardNo = idCardNo;
  const phone = validateMobilePhone(values.phone);
  if (phone) errors.phone = phone;
  const emergencyPhone = validateMobilePhone(values.emergencyPhone);
  if (emergencyPhone) errors.emergencyPhone = emergencyPhone;
  return errors;
}

function validateName(value: string): string | undefined {
  return value.trim() ? undefined : "姓名不能为空";
}
