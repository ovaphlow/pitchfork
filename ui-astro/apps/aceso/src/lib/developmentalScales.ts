/**
 * 儿童发育筛查量表包与评分引擎 —— 纯逻辑（无 React、无副作用）。
 *
 * **与后端同源**：本文件逐条镜像
 * `service-vertx-kotlin/libs/healthcare/src/main/kotlin/com/ovaphlow/crate/healthcare/DevelopmentalScale.kt`
 * （053 A1）：量表编码 / 版本 / 领域 / 条目编码 / 适龄区间 / 选项 / 判读切点全部一致，
 * 计分与判读语义逐条对齐。**改动需两端同步**，否则前端预览与服务端判读会分叉。
 *
 * ## 版权声明（务必先读）
 * 量表编码、领域、条目提示语与选项文案均为**通用发育里程碑的原创占位描述**，仅用于打通
 * 「量表定义 → 按龄选条 → 领域/总合计分 → 判读」链路，**不是** ASQ-3、DST、Gesell、丹佛
 * （DDST）等任何受版权保护量表的条目，也不得作为临床诊断依据。正式量表内容（条目、常模、
 * 切点）待产品确认版权与标准后填入（053 §11 裁定 1）；届时只替换本文件的**数据常量**，
 * [scoreScreening] 的引擎与结构保持不变。
 *
 * ## 判读语义（与后端 A1 `DevelopmentalScoring.score` 逐条一致）
 *  1. `ageMonths < 0` 或超出量表适用区间 → 抛 `Error`（消息含 `age_months`）。
 *  2. `answers` 键集必须**恰好等于**该月龄适龄条目集：缺项 / 多余 / 非适龄 → 抛 `Error`。
 *  3. 每个分值须在该条目 `options` 的 `value` 内，否则 → 抛 `Error`（消息含 `invalid`）。
 *  4. 领域分 = 该领域适龄条目得分之和；领域满分 = 各适龄条目最大 `value` 之和。
 *  5. `triggeredItems` = 得分为该条目 `options` **最小值**的适龄条目编码，升序。
 *  6. 判读按达标率 `totalScore / maxTotalScore`：`>= 0.90` 正常；`>= 0.70 且 < 0.90` 可疑；`< 0.70` 异常。
 *  7. `maxTotalScore == 0` 视为量表/月龄配置错误 → 抛 `Error`。
 *
 * 本文件不校验儿童身份、不落库、不生成标识；服务端派生与持久化见 A2。
 */

/** 领域编码：大运动。 */
export const DOMAIN_GROSS_MOTOR = "GM";
/** 领域编码：精细动作。 */
export const DOMAIN_FINE_MOTOR = "FM";
/** 领域编码：语言。 */
export const DOMAIN_LANGUAGE = "LA";

/** 量表编码：占位量表（与后端 `DevelopmentalScaleCatalog.PLACEHOLDER_SCALE_CODE` 同值）。 */
export const PLACEHOLDER_SCALE_CODE = "CHILD_DEV_PLACEHOLDER";

/** 达标率切点：`>= 0.90` 判正常 —— 占位切点，正式量表由产品裁定。 */
export const NORMAL_THRESHOLD = 0.9;
/** 达标率切点：`>= 0.70` 且 `< 0.90` 判可疑 —— 占位切点，正式量表由产品裁定。 */
export const SUSPECT_THRESHOLD = 0.7;

/** 判读分类（与后端 `DevelopmentalClassification` 及 A2 `classification` 同值）。 */
export const CLASSIFICATIONS = {
	/** 正常。 */
	NORMAL: "正常",
	/** 可疑。 */
	SUSPECT: "可疑",
	/** 异常。 */
	ABNORMAL: "异常",
} as const;

/** 判读分类取值。 */
export type Classification = (typeof CLASSIFICATIONS)[keyof typeof CLASSIFICATIONS];

/** 一个条目的取值选项（整数值 + 中文标签）。 */
export interface ItemOption {
	value: number;
	label: string;
}

/** 一个发育筛查条目。 */
export interface Item {
	/** 条目编码，跨全部量表全局唯一。 */
	code: string;
	/** 所属领域编码，须在该量表 `domains` 内。 */
	domain: string;
	/** 条目提示语（占位文案，勿抄录受版权量表）。 */
	prompt: string;
	/** 适龄下界（含）。 */
	minMonths: number;
	/** 适龄上界（含）。 */
	maxMonths: number;
	/** 取值选项（2–3 个，值互不相同）。 */
	options: ItemOption[];
}

/** 一个领域（编码 + 中文名）。 */
export interface Domain {
	code: string;
	name: string;
}

/** 一个量表。 */
export interface Scale {
	code: string;
	version: string;
	name: string;
	minMonths: number;
	maxMonths: number;
	/** 领域列表（顺序即展示顺序）。 */
	domains: Domain[];
	/** 条目列表（顺序即作答/回显顺序）。 */
	items: Item[];
}

/** 领域定义（与后端 `placeholderScale()` 的 domains 同序同值）。 */
export const DOMAINS: Domain[] = [
	{ code: DOMAIN_GROSS_MOTOR, name: "大运动" },
	{ code: DOMAIN_FINE_MOTOR, name: "精细动作" },
	{ code: DOMAIN_LANGUAGE, name: "语言" },
];

function triple(low: string, mid: string, high: string): ItemOption[] {
	return [
		{ value: 0, label: low },
		{ value: 1, label: mid },
		{ value: 2, label: high },
	];
}

function binary(low: string, high: string): ItemOption[] {
	return [
		{ value: 0, label: low },
		{ value: 2, label: high },
	];
}

/**
 * 占位量表（与后端 `CHILD_DEV_PLACEHOLDER` v0.1 逐条镜像）。
 *
 * 14 条，按 0–6 / 7–12 / 13–24 / 25–60 月分档，覆盖 0–60 月无空档。
 */
export const SCALE: Scale = {
	code: PLACEHOLDER_SCALE_CODE,
	version: "0.1",
	name: "儿童发育筛查占位量表（通用里程碑描述，非受版权保护量表）",
	minMonths: 0,
	maxMonths: 60,
	domains: DOMAINS,
	items: [
		// ---- 0–6 月 ----
		{
			code: "GM_HEAD_CONTROL",
			domain: DOMAIN_GROSS_MOTOR,
			prompt: "俯卧时能短暂抬头并左右转头",
			minMonths: 0,
			maxMonths: 6,
			options: triple("不能抬头", "能短暂抬头", "能稳定抬头并转头"),
		},
		{
			code: "FM_GRASP",
			domain: DOMAIN_FINE_MOTOR,
			prompt: "能主动抓握递到手中的物品",
			minMonths: 0,
			maxMonths: 6,
			options: triple("不能抓握", "偶有抓握", "能稳定抓握"),
		},
		{
			code: "LA_VOICE",
			domain: DOMAIN_LANGUAGE,
			prompt: "对声音有反应并能发出单一元音",
			minMonths: 0,
			maxMonths: 6,
			options: triple("无反应", "偶有反应", "能稳定回应并发音"),
		},
		// ---- 7–12 月 ----
		{
			code: "GM_SIT",
			domain: DOMAIN_GROSS_MOTOR,
			prompt: "能独坐并保持身体平衡",
			minMonths: 7,
			maxMonths: 12,
			options: triple("不能独坐", "需扶持坐", "能独坐"),
		},
		{
			code: "FM_TRANSFER",
			domain: DOMAIN_FINE_MOTOR,
			prompt: "能把物品从一只手传递到另一只手",
			minMonths: 7,
			maxMonths: 12,
			options: triple("不能传递", "偶可传递", "能稳定传递"),
		},
		{
			code: "LA_BABBLE",
			domain: DOMAIN_LANGUAGE,
			prompt: "能发出连续音节（咿呀学语）",
			minMonths: 7,
			maxMonths: 12,
			options: triple("无连续音节", "偶尔发音", "能连续咿呀"),
		},
		// ---- 13–24 月 ----
		{
			code: "GM_WALK",
			domain: DOMAIN_GROSS_MOTOR,
			prompt: "能独站并迈步行走",
			minMonths: 13,
			maxMonths: 24,
			options: triple("不能独站", "需扶持走", "能独立行走"),
		},
		{
			code: "FM_STACK",
			domain: DOMAIN_FINE_MOTOR,
			prompt: "能把积木叠放或完成简单涂鸦",
			minMonths: 13,
			maxMonths: 24,
			options: triple("不能叠放", "偶可叠放", "能叠放多块"),
		},
		{
			code: "LA_WORD",
			domain: DOMAIN_LANGUAGE,
			prompt: "能有意识地叫人或说出简单单词",
			minMonths: 13,
			maxMonths: 24,
			options: triple("无单词", "偶有单词", "能主动说单词"),
		},
		// ---- 25–60 月 ----
		{
			code: "GM_JUMP",
			domain: DOMAIN_GROSS_MOTOR,
			prompt: "能双脚同时跳离地面",
			minMonths: 25,
			maxMonths: 60,
			options: triple("不能跳起", "能单脚跳", "能双脚跳"),
		},
		{
			code: "GM_BALANCE",
			domain: DOMAIN_GROSS_MOTOR,
			prompt: "能单脚站立数秒并沿直线行走",
			minMonths: 25,
			maxMonths: 60,
			options: triple("不能单脚站立", "可短暂站立", "能稳定单脚站"),
		},
		{
			code: "FM_DRAW",
			domain: DOMAIN_FINE_MOTOR,
			prompt: "能握笔画出圆形或简单图形",
			minMonths: 25,
			maxMonths: 60,
			options: triple("不能握笔画", "能随意涂画", "能画圆形图形"),
		},
		{
			code: "LA_SENTENCE",
			domain: DOMAIN_LANGUAGE,
			prompt: "能用两三个词组成短句表达需求",
			minMonths: 25,
			maxMonths: 60,
			options: triple("不能组词", "偶有短句", "能说完整短句"),
		},
		{
			code: "LA_UNDERSTAND",
			domain: DOMAIN_LANGUAGE,
			prompt: "能理解并执行两步简单指令",
			minMonths: 25,
			maxMonths: 60,
			options: binary("不能完成", "能完成"),
		},
	],
};

/**
 * 该月龄的适龄条目（`minMonths <= ageMonths <= maxMonths`），保持量表定义顺序。
 *
 * 与后端 `DevelopmentalScaleCatalog.ageAppropriateItems` 同口径：本函数**不校验**区间，
 * 区间校验由 [scoreScreening] 负责。
 */
export function ageAppropriateItems(ageMonths: number): Item[] {
	return SCALE.items.filter((item) => ageMonths >= item.minMonths && ageMonths <= item.maxMonths);
}

/** 一次发育筛查的计分结果（与后端 A1 `DevelopmentalScoreResult` 对齐的展示子集）。 */
export interface ScreeningScoreResult {
	/** 领域编码 → 得分（适龄条目得分之和）。 */
	domainScores: Record<string, number>;
	/** 领域编码 → 该月龄适龄满分。 */
	domainMaxScores: Record<string, number>;
	/** 总分。 */
	totalScore: number;
	/** 该月龄最大可能总分。 */
	maxTotalScore: number;
	/** 判读：正常 / 可疑 / 异常。 */
	classification: Classification;
	/** 未通过（触发）条目编码，升序。 */
	triggeredItems: string[];
}

/**
 * 计分与判读（与后端 `DevelopmentalScoring.score` 语义逐条一致）。
 *
 * @param ageMonths 儿童月龄（须在量表适用区间内）。
 * @param answers   条目编码 → 分值，键集须恰好等于该月龄适龄条目集。
 * @throws Error 月龄越界 / 缺项 / 多余项 / 非法分值 / 量表配置错误。
 */
export function scoreScreening(
	ageMonths: number,
	answers: Record<string, number>,
): ScreeningScoreResult {
	const scale = SCALE;

	// 语义 1：月龄越界。
	if (ageMonths < 0 || ageMonths < scale.minMonths || ageMonths > scale.maxMonths) {
		throw new Error(
			`age_months must be within ${scale.minMonths}..${scale.maxMonths} for scale ${scale.code}`,
		);
	}

	const items = ageAppropriateItems(ageMonths);
	if (items.length === 0) {
		throw new Error(`no age-appropriate items for age_months=${ageMonths}`);
	}

	// 语义 2：键集必须恰好等于适龄条目集。
	const expectedCodes = items.map((item) => item.code);
	const expected = new Set(expectedCodes);
	const provided = new Set(Object.keys(answers));
	const missing = [...expected].filter((code) => !provided.has(code)).sort();
	if (missing.length > 0) {
		throw new Error(`missing answers for items: ${missing.join(", ")}`);
	}
	const unexpected = [...provided].filter((code) => !expected.has(code)).sort();
	if (unexpected.length > 0) {
		throw new Error(`unexpected answers for items: ${unexpected.join(", ")}`);
	}

	// 语义 3：分值须在选项内。
	items.forEach((item) => {
		const value = answers[item.code];
		if (!item.options.some((option) => option.value === value)) {
			throw new Error(`invalid score ${value} for item ${item.code}`);
		}
	});

	// 语义 4：领域分与领域满分。
	const domainScores: Record<string, number> = {};
	const domainMaxScores: Record<string, number> = {};
	scale.domains.forEach((domain) => {
		const domainItems = items.filter((item) => item.domain === domain.code);
		domainScores[domain.code] = domainItems.reduce((sum, item) => sum + (answers[item.code] ?? 0), 0);
		domainMaxScores[domain.code] = domainItems.reduce(
			(sum, item) => sum + Math.max(...item.options.map((option) => option.value)),
			0,
		);
	});

	const totalScore = Object.values(domainScores).reduce((sum, value) => sum + value, 0);
	const maxTotalScore = Object.values(domainMaxScores).reduce((sum, value) => sum + value, 0);

	// 语义 7：量表/月龄配置错误。
	if (maxTotalScore === 0) {
		throw new Error(
			`invalid scale configuration: maxTotalScore is 0 for scale ${scale.code} at age_months=${ageMonths}`,
		);
	}

	// 语义 6：按达标率判读（占位切点，正式量表由产品裁定）。
	const ratio = totalScore / maxTotalScore;
	const classification: Classification =
		ratio >= NORMAL_THRESHOLD
			? CLASSIFICATIONS.NORMAL
			: ratio >= SUSPECT_THRESHOLD
				? CLASSIFICATIONS.SUSPECT
				: CLASSIFICATIONS.ABNORMAL;

	// 语义 5：触发项 = 取到该条目最低选项值的适龄条目，升序。
	const triggeredItems = items
		.filter((item) => answers[item.code] === Math.min(...item.options.map((option) => option.value)))
		.map((item) => item.code)
		.sort();

	return {
		domainScores,
		domainMaxScores,
		totalScore,
		maxTotalScore,
		classification,
		triggeredItems,
	};
}

/** 实时预览（**非权威**）：仅对已作答条目累计，用于录入过程中的即时反馈。 */
export interface ScreeningPreview {
	/** 领域编码 → 已作答条目得分之和。 */
	domainScores: Record<string, number>;
	/** 领域编码 → 该月龄适龄满分（分母）。 */
	domainMaxScores: Record<string, number>;
	/** 已作答条目得分之和。 */
	totalScore: number;
	/** 该月龄最大可能总分。 */
	maxTotalScore: number;
	/** 已作答条目数。 */
	answeredCount: number;
	/** 适龄条目总数。 */
	itemCount: number;
}

/**
 * 实时预览：只累计**已作答且合法**的条目，用于录入过程中的领域分/总分展示。
 *
 * 判读结论一律以 [scoreScreening] 为准（服务端也以 A1 引擎重算）；本函数不产生 `classification`，
 * 避免把「未答完」误读成异常。月龄越界或无适龄条目时返回全零。
 */
export function previewScreening(
	ageMonths: number,
	answers: Record<string, number>,
): ScreeningPreview {
	const items = ageAppropriateItems(ageMonths);
	const domainScores: Record<string, number> = {};
	const domainMaxScores: Record<string, number> = {};
	let answeredCount = 0;

	SCALE.domains.forEach((domain) => {
		domainScores[domain.code] = 0;
		domainMaxScores[domain.code] = 0;
	});

	items.forEach((item) => {
		domainMaxScores[item.domain] =
			(domainMaxScores[item.domain] ?? 0) +
			Math.max(...item.options.map((option) => option.value));
		const value = answers[item.code];
		if (value !== undefined && item.options.some((option) => option.value === value)) {
			domainScores[item.domain] = (domainScores[item.domain] ?? 0) + value;
			answeredCount += 1;
		}
	});

	const totalScore = Object.values(domainScores).reduce((sum, value) => sum + value, 0);
	const maxTotalScore = Object.values(domainMaxScores).reduce((sum, value) => sum + value, 0);

	return { domainScores, domainMaxScores, totalScore, maxTotalScore, answeredCount, itemCount: items.length };
}

/** 该判读是否可建转诊（与后端 A3 `ChildScreeningService.canRefer` 同口径：可疑 / 异常）。 */
export function canRefer(classification: string | null | undefined): boolean {
	return classification === CLASSIFICATIONS.SUSPECT || classification === CLASSIFICATIONS.ABNORMAL;
}
