/**
 * Aceso 单产品（养老）文案的**单一事实来源**（056）。
 *
 * 背景：056 之前产品用 `localStorage["aceso-domain"]` 在 医疗 / 养老 / 儿保 之间切换，
 * 同一路径在不同域下叫法不同，标题与菜单需要在运行期按域改写。056 收窄为养老单产品后
 * 域概念不存在了，所有取词直接读本文件的常量——组件里不再有任何「按域选词」分支，
 * 也不会出现「菜单叫长者、页面叫居民」这类不同源问题。
 *
 * 未来儿保以 `/childcare/*` 二级路由回归时，应新增自己的 labels 模块，
 * 不要在本文件里重新引入「域」这个中间概念。
 */

/**
 * `patients.person_type` 的合法取值，与后端白名单逐字一致。
 *
 * 养老只写「长者」；「居民」「儿童」保留为**历史值**（056 D3：既有库中可能存在这两种档案，
 * 删值会让历史档案不可读）。
 */
export type PersonType = "居民" | "长者" | "儿童";

export interface EntityLabels {
  /** 主体名词：长者 */
  person: string;
  /** 档案名：长者档案 */
  archive: string;
  /** 写入 `patients.person_type` 的域值：长者 */
  personType: PersonType;
}

export const ENTITY_LABELS: EntityLabels = {
  person: "长者",
  archive: "长者档案",
  personType: "长者",
};

/** 「入院 / 入住」动作在养老下的说法 */
export const ADMISSION_PHRASE = "养老入住";
