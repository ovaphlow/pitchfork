package evaluation

import (
	"testing"
	"time"
)

// ─── fixture 辅助 ────────────────────────────────────────────────────

// The fixed ULIDs of the built-in indicator seed (000023): the engine
// tests use the same dictionary rows the migration and the seed
// function produce, so the assertions stay readable.
const (
	idAlertResponse      = "06G01KFRJTE84EP3234FBD2YD4" // 预警响应速度   响应速度 s1
	idCommandResponse    = "06G01KFRJR51ZC8236ENVBYE04" // 指挥调度响应速度 响应速度 s2
	idForceArrival       = "06G01KFRJSSPP4A1WKASE61C90" // 力量到场速度   响应速度 s3
	idProcessStandard    = "06G01KFRJTM9G3YMSW4VADTNVM" // 处置流程规范性 处置规范性 s1
	idReportStandard     = "06G01KFRJR301CJGSBGAFWD0WC" // 信息报告规范性 处置规范性 s2
	idDepartmentSynergy  = "06G01KFRJT70GCYFVZQK8B05SW" // 部门协同效率   协同效率 s1
	idInformationSharing = "06G01KFRJT8YMF2JVH1G55CXCC" // 信息共享效率   协同效率 s2
	idAudienceEvacuation = "06G01KFRJVPT0X4DZ5WYCW6G38" // 观众疏散组织   观众安全 s1 (demo)
	idRelicTransfer      = "06G01KFRJTZ2WR0GWNKZATXR1R" // 文物转移保护   文物安全 s1 (demo)
	idOpinionMonitoring  = "06G01KFRJS44Q9521HC4CXTGX0" // 舆情监测预警   舆情管控 s1 (demo)
)

// fixtureIndicator builds one dictionary row of the built-in seed
// (fixed ULID, weight and per-dimension sort_order).
func fixtureIndicator(id string, dimension Dimension, title string, sortOrder int, demo bool, weight int) Indicator {
	return Indicator{
		ID:        id,
		Dimension: dimension,
		Title:     title,
		Weight:    weight,
		Demo:      demo,
		SortOrder: sortOrder,
	}
}

// fixtureIndicators returns the seven computable indicators of the
// seed (all weight 1, in seed order).
func fixtureIndicators() []Indicator {
	return []Indicator{
		fixtureIndicator(idAlertResponse, DimensionResponseSpeed, "预警响应速度", 1, false, 1),
		fixtureIndicator(idCommandResponse, DimensionResponseSpeed, "指挥调度响应速度", 2, false, 1),
		fixtureIndicator(idForceArrival, DimensionResponseSpeed, "力量到场速度", 3, false, 1),
		fixtureIndicator(idProcessStandard, DimensionDisposalStandard, "处置流程规范性", 1, false, 1),
		fixtureIndicator(idReportStandard, DimensionDisposalStandard, "信息报告规范性", 2, false, 1),
		fixtureIndicator(idDepartmentSynergy, DimensionCoordination, "部门协同效率", 1, false, 1),
		fixtureIndicator(idInformationSharing, DimensionCoordination, "信息共享效率", 2, false, 1),
	}
}

func timestamp(base time.Time, seconds float64) *time.Time {
	value := base.Add(time.Duration(seconds * float64(time.Second)))
	return &value
}

// ─── 通用时延公式 ────────────────────────────────────────────────────

// TestLatencyScore pins the generic latency formula
// max(0, min(100, 100 − 秒数 ÷ 5)) with float seconds and 1-decimal
// rounding, including the clamping at both ends.
func TestLatencyScore(t *testing.T) {
	cases := []struct {
		name    string
		seconds float64
		want    float64
	}{
		{"zero seconds", 0, 100},
		{"ten seconds", 10, 98},
		{"twenty-five seconds", 25, 95},
		{"thirty-three seconds rounds to 93.4", 33, 93.4},
		{"eight point four seconds rounds to 98.3", 8.4, 98.3},
		{"one hundred seconds", 100, 80},
		{"five hundred seconds clamps to zero", 500, 0},
		{"six hundred seconds stays zero", 600, 0},
		{"negative seconds clamp to one hundred", -5, 100},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			if got := latencyScore(tc.seconds); got != tc.want {
				t.Errorf("latencyScore(%v) = %v, want %v", tc.seconds, got, tc.want)
			}
		})
	}
}

// ─── 自动评分：7 项计算指标 ───────────────────────────────────────────

// TestAutoScoreLatencyMetrics builds a completed run with known
// timestamps and asserts the three latency-derived scores (预警发现时
// 间/预案启动时间/信息上报时间/信息传递及时性) against the hand
// computations of the specification: seconds are the float duration
// and the score is max(0, min(100, 100 − seconds ÷ 5)) rounded to 1
// decimal. 「首个事件触发时刻」= the earliest triggered_at.
func TestAutoScoreLatencyMetrics(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted, StartedAt: timestamp(base, 0)}
	// 首个事件触发 = base+10s (earliest triggered_at).
	simEvents := []ReportSimEvent{
		{TriggeredAt: timestamp(base, 20), CreatedAt: base.Add(3 * time.Second)},
		{TriggeredAt: timestamp(base, 10), CreatedAt: base.Add(2 * time.Second)},
	}
	orders := []ReportOrder{{IssuedAt: timestamp(base, 35)}} // 事件触发后 25s
	messages := []ReportMessage{
		{SenderType: "指挥中心", SentAt: timestamp(base, 15)}, // 事件触发后 5s（首条消息）
		{SenderType: "现场人员", SentAt: timestamp(base, 18)}, // 事件触发后 8s（首条现场消息）
	}

	content := ComposeReport(ScoringInput{
		Run:        run,
		Indicators: fixtureIndicators(),
		SimEvents:  simEvents,
		Orders:     orders,
		Messages:   messages,
	})

	assertAutoScore(t, content, idAlertResponse, 98, "预警发现时间: latency(10s)")
	assertAutoScore(t, content, idCommandResponse, 95, "预案启动时间: latency(25s)")
	assertAutoScore(t, content, idForceArrival, 99, "信息上报时间: latency(5s)")
	assertAutoScore(t, content, idInformationSharing, 98.4, "信息传递及时性: latency(8s)")
	if _, ok := content.IndicatorScores[idProcessStandard]; ok {
		t.Error("处置流程规范性 got an auto score without step records, want 无分")
	}
	if _, ok := content.IndicatorScores[idReportStandard]; ok {
		t.Error("信息报告规范性 got an auto score without step records, want 无分")
	}
	if _, ok := content.IndicatorScores[idDepartmentSynergy]; ok {
		t.Error("部门协同效率 got an auto score without department reports, want 无分")
	}
}

// TestAutoScoreCompletenessConformityLinkage asserts the three ratio
// formulas: 流程执行完整度 = 已执行 ÷ 步骤总数 × 100 (including 待执行/
// 跳过), 操作标准符合度 = 已执行 ÷ (已执行+跳过) × 100 and 部门联动顺畅
// 度 = 已完成部门报告 ÷ 报告总数 × 100, each rounded to 1 decimal.
func TestAutoScoreCompletenessConformityLinkage(t *testing.T) {
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted}
	stepRecords := []ReportStepRecord{
		{Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"},
		{Status: "跳过"}, {Status: "待执行"},
	}
	departmentReports := []ReportDepartmentReport{
		{Status: "已完成"}, {Status: "已完成"}, {Status: "处置中"},
	}

	content := ComposeReport(ScoringInput{
		Run:               run,
		Indicators:        fixtureIndicators(),
		StepRecords:       stepRecords,
		DepartmentReports: departmentReports,
	})

	assertAutoScore(t, content, idProcessStandard, 60, "流程执行完整度: 3÷5×100")
	assertAutoScore(t, content, idReportStandard, 75, "操作标准符合度: 3÷(3+1)×100")
	assertAutoScore(t, content, idDepartmentSynergy, 66.7, "部门联动顺畅度: 2÷3×100")

	// 维度与总分：处置规范性 (60+75)/2 = 67.5，协同效率 66.7，总分
	// (60+75+66.7)/3 = 67.233… → 67.2。
	if got := content.DimensionScores[DimensionDisposalStandard].Score; got != 67.5 {
		t.Errorf("处置规范性 score = %v, want 67.5", got)
	}
	if got := content.DimensionScores[DimensionCoordination].Score; got != 66.7 {
		t.Errorf("协同效率 score = %v, want 66.7", got)
	}
	if got := content.OverallScore; got != 67.2 {
		t.Errorf("overall = %v, want 67.2", got)
	}
}

// TestAutoScoreMissingData asserts the 缺数据 0 分 口径: the formulas
// yield 0 when their data is missing (无事件/无消息/无指令/无步骤/无报告),
// the auto source is absent and the indicators stay 无分 — a bare run
// produces the empty report shape.
func TestAutoScoreMissingData(t *testing.T) {
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted}
	// No events, no messages, no orders, no step records, no reports,
	// no scores, no demo scores: every auto source is absent.
	content := ComposeReport(ScoringInput{Run: run, Indicators: fixtureIndicators()})

	if len(content.IndicatorScores) != 0 {
		t.Errorf("indicator_scores = %v, want empty (缺数据一律无分)", content.IndicatorScores)
	}
	if len(content.DimensionScores) != 0 {
		t.Errorf("dimension_scores = %v, want empty", content.DimensionScores)
	}
	if content.OverallScore != 0 {
		t.Errorf("overall = %v, want 0", content.OverallScore)
	}
	assertInsufficientDataSuggestion(t, content.Suggestions)

	// The formula level still pins the 0: every missing case answers
	// (0, false) — 0 per the specification formula, but never a score.
	firstEvent, hasEvent := earliestEventTrigger(nil)
	if hasEvent {
		t.Fatal("earliestEventTrigger(nil) reported an event")
	}
	for _, indicator := range fixtureIndicators() {
		score, present := autoScore(ScoringInput{Run: run, Indicators: fixtureIndicators()}, indicator, firstEvent, hasEvent)
		if present {
			t.Errorf("autoScore(%s) present with %v, want absent (缺数据 0 分)", indicator.Title, score)
		}
		if score != 0 {
			t.Errorf("autoScore(%s) = %v, want the formula 0 for missing data", indicator.Title, score)
		}
	}
	// 部分缺数据：有事件但无消息/指令 → 预警发现时间有分，信息上报时间/
	// 预案启动时间无分。
	events := []ReportSimEvent{{TriggeredAt: timestamp(time.Date(2026, 8, 14, 10, 0, 10, 0, time.UTC), 0)}}
	partial := ComposeReport(ScoringInput{
		Run:        ReportRun{ID: "run-1", Status: reportRunStatusCompleted, StartedAt: timestamp(time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC), 0)},
		Indicators: fixtureIndicators(),
		SimEvents:  events,
	})
	if _, ok := partial.IndicatorScores[idAlertResponse]; !ok {
		t.Error("预警响应速度 absent with an event and started_at, want 有分")
	}
	for _, id := range []string{idCommandResponse, idForceArrival, idInformationSharing} {
		if _, ok := partial.IndicatorScores[id]; ok {
			t.Errorf("indicator %s present without its latency data, want 无分", id)
		}
	}
}

// TestEarliestEventTriggerTieBreak pins the 首个事件触发时刻 tie-break:
// equal triggered_at values order by the earliest created_at.
func TestEarliestEventTriggerTieBreak(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	events := []ReportSimEvent{
		{TriggeredAt: timestamp(base, 30), CreatedAt: base.Add(5 * time.Second)},
		{TriggeredAt: timestamp(base, 30), CreatedAt: base.Add(1 * time.Second)},
		{TriggeredAt: timestamp(base, 20), CreatedAt: base.Add(9 * time.Second)},
	}
	first, ok := earliestEventTrigger(events)
	if !ok {
		t.Fatal("earliestEventTrigger found no event")
	}
	want := base.Add(20 * time.Second)
	if !first.Equal(want) {
		t.Errorf("first trigger = %v, want %v", first, want)
	}
}

// ─── 聚合口径 ────────────────────────────────────────────────────────

// TestComposeAggregationWithWeights mixes the three human sources
// (expert with multiple raters, self/peer with multiple raters) with
// the auto source and non-1 weights, and asserts the final scores, the
// dimension scores and the overall score against hand computations:
// 指标最终得分 = 有分来源算术平均；维度/总分 = weight 加权平均（权重归一
// 化）；全部 1 位小数。演示指标仅在 metadata.demo_scores 提供值时计入。
func TestComposeAggregationWithWeights(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	indicators := []Indicator{
		fixtureIndicator(idAlertResponse, DimensionResponseSpeed, "预警响应速度", 1, false, 1),
		fixtureIndicator(idCommandResponse, DimensionResponseSpeed, "指挥调度响应速度", 2, false, 3), // 非 1 权重
		fixtureIndicator(idProcessStandard, DimensionDisposalStandard, "处置流程规范性", 1, false, 1),
		fixtureIndicator(idDepartmentSynergy, DimensionCoordination, "部门协同效率", 1, false, 1),
		fixtureIndicator(idAudienceEvacuation, DimensionAudienceSafety, "观众疏散组织", 1, true, 1),
		fixtureIndicator(idRelicTransfer, DimensionRelicSafety, "文物转移保护", 1, true, 1), // 无 demo_scores → 不计入
		fixtureIndicator(idOpinionMonitoring, DimensionPublicOpinion, "舆情监测预警", 1, true, 1),
	}
	run := ReportRun{
		ID:        "run-1",
		Status:    reportRunStatusCompleted,
		StartedAt: timestamp(base, 0),
		Metadata: map[string]any{
			"demo_scores": map[string]any{
				idAudienceEvacuation: float64(88),
				idOpinionMonitoring:  float64(70),
			},
		},
	}
	scores := []Score{
		{IndicatorID: idAlertResponse, ScoreType: ScoreTypeExpert, Score: 80},
		{IndicatorID: idAlertResponse, ScoreType: ScoreTypeExpert, Score: 100}, // 多 rater 取均值
		{IndicatorID: idProcessStandard, ScoreType: ScoreTypeSelf, Score: 70},
		{IndicatorID: idProcessStandard, ScoreType: ScoreTypePeer, Score: 80},
		{IndicatorID: idDepartmentSynergy, ScoreType: ScoreTypeExpert, Score: 50},
		{IndicatorID: idOpinionMonitoring, ScoreType: ScoreTypeExpert, Score: 90},
	}
	simEvents := []ReportSimEvent{{TriggeredAt: timestamp(base, 10)}}
	orders := []ReportOrder{{IssuedAt: timestamp(base, 35)}}
	stepRecords := []ReportStepRecord{{Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"}, {Status: "跳过"}, {Status: "待执行"}}
	departmentReports := []ReportDepartmentReport{{Status: "已完成"}, {Status: "已完成"}}

	content := ComposeReport(ScoringInput{
		Run:               run,
		Indicators:        indicators,
		Scores:            scores,
		SimEvents:         simEvents,
		Orders:            orders,
		StepRecords:       stepRecords,
		DepartmentReports: departmentReports,
	})

	// 手工计算：
	//   预警响应速度: auto 98，专家 (80+100)/2=90 → (98+90)/2 = 94
	//   指挥调度响应速度: auto 95（权重 3）
	//   处置流程规范性: auto 60，自评互评 (70+80)/2=75 → (60+75)/2 = 67.5
	//   部门协同效率: auto 100，专家 50 → (100+50)/2 = 75
	//   观众疏散组织: demo 88
	//   文物转移保护: 无 demo_scores → 不出现
	//   舆情监测预警: demo 70，专家 90 → (70+90)/2 = 80
	//   响应速度维度: (94×1 + 95×3) ÷ 4 = 94.75 → 94.8（权重归一化）
	//   总分: (94×1 + 95×3 + 67.5×1 + 75×1 + 88×1 + 80×1) ÷ 8
	//        = 689.5 ÷ 8 = 86.1875 → 86.2
	wantScores := map[string]IndicatorScore{
		idAlertResponse:      {Score: 94, Auto: float64Ptr(98), Expert: float64Ptr(90)},
		idCommandResponse:    {Score: 95, Auto: float64Ptr(95)},
		idProcessStandard:    {Score: 67.5, Auto: float64Ptr(60), SelfPeer: float64Ptr(75)},
		idDepartmentSynergy:  {Score: 75, Auto: float64Ptr(100), Expert: float64Ptr(50)},
		idAudienceEvacuation: {Score: 88, Demo: float64Ptr(88)},
		idOpinionMonitoring:  {Score: 80, Demo: float64Ptr(70), Expert: float64Ptr(90)},
	}
	if len(content.IndicatorScores) != len(wantScores) {
		t.Fatalf("indicator_scores = %d entries, want %d", len(content.IndicatorScores), len(wantScores))
	}
	for id, want := range wantScores {
		got, ok := content.IndicatorScores[id]
		if !ok {
			t.Errorf("indicator %s missing from indicator_scores", id)
			continue
		}
		if !sameIndicatorScore(t, got, want) {
			t.Errorf("indicator %s = %+v, want %+v", id, got, want)
		}
	}
	if _, ok := content.IndicatorScores[idRelicTransfer]; ok {
		t.Error("文物转移保护 present without demo_scores, want 不计入")
	}

	responseSpeed, ok := content.DimensionScores[DimensionResponseSpeed]
	if !ok {
		t.Fatal("响应速度 missing from dimension_scores")
	}
	if responseSpeed.Score != 94.8 {
		t.Errorf("响应速度 score = %v, want 94.8 (weight-normalized)", responseSpeed.Score)
	}
	if responseSpeed.Breakdown[idAlertResponse] != 94 || responseSpeed.Breakdown[idCommandResponse] != 95 {
		t.Errorf("响应速度 breakdown = %v, want {预警响应速度: 94, 指挥调度响应速度: 95}", responseSpeed.Breakdown)
	}
	if got := content.DimensionScores[DimensionDisposalStandard].Score; got != 67.5 {
		t.Errorf("处置规范性 score = %v, want 67.5", got)
	}
	if got := content.DimensionScores[DimensionCoordination].Score; got != 75 {
		t.Errorf("协同效率 score = %v, want 75", got)
	}
	if got := content.DimensionScores[DimensionAudienceSafety].Score; got != 88 {
		t.Errorf("观众安全 score = %v, want 88", got)
	}
	if got := content.DimensionScores[DimensionPublicOpinion].Score; got != 80 {
		t.Errorf("舆情管控 score = %v, want 80", got)
	}
	if _, ok := content.DimensionScores[DimensionRelicSafety]; ok {
		t.Error("文物安全 present without any scored indicator, want absent")
	}
	if content.OverallScore != 86.2 {
		t.Errorf("overall = %v, want 86.2", content.OverallScore)
	}
}

// ─── 建议规则 ────────────────────────────────────────────────────────

// TestSuggestionRules pins the six fixed templates with their level
// thresholds (<40 严重, 40–59.9 关注), the appended linkage suggestion
// (dimension=协同效率, level=关注, fixed text) and their coexistence.
func TestSuggestionRules(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	indicators := []Indicator{
		fixtureIndicator(idAlertResponse, DimensionResponseSpeed, "预警响应速度", 1, false, 1),
		fixtureIndicator(idProcessStandard, DimensionDisposalStandard, "处置流程规范性", 1, false, 1),
		fixtureIndicator(idDepartmentSynergy, DimensionCoordination, "部门协同效率", 1, false, 1),
	}
	// 预警响应速度: latency(350s) = 30 → <40 严重；处置流程规范性:
	// 5/10×100 = 50 → 关注；部门协同效率: 2/5×100 = 40 → 关注；另有一
	// 份 未响应 部门报告 → 追加联动建议（与协同效率模板并存）。
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted, StartedAt: timestamp(base, 0)}
	simEvents := []ReportSimEvent{{TriggeredAt: timestamp(base, 350)}}
	stepRecords := make([]ReportStepRecord, 10)
	for i := range stepRecords {
		stepRecords[i].Status = "已执行"
	}
	for i := 5; i < 10; i++ {
		stepRecords[i].Status = "待执行"
	}
	departmentReports := []ReportDepartmentReport{
		{Status: "已完成"}, {Status: "已完成"}, {Status: "未响应"}, {Status: "处置中"}, {Status: "已响应"},
	}

	content := ComposeReport(ScoringInput{
		Run:               run,
		Indicators:        indicators,
		SimEvents:         simEvents,
		StepRecords:       stepRecords,
		DepartmentReports: departmentReports,
	})

	want := []Suggestion{
		{Dimension: DimensionResponseSpeed, Level: SuggestionLevelSevere, Text: "响应速度维度平均分 30.0 分，未达 60 分，建议优化预警发现、信息上报与预案启动流程，缩短应急响应用时。"},
		{Dimension: DimensionDisposalStandard, Level: SuggestionLevelWatch, Text: "处置规范性维度平均分 50.0 分，未达 60 分，建议加强处置流程执行与操作标准培训，减少流程跳过。"},
		{Dimension: DimensionCoordination, Level: SuggestionLevelWatch, Text: "协同效率维度平均分 40.0 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。"},
		{Dimension: DimensionCoordination, Level: SuggestionLevelWatch, Text: linkageSuggestionText},
	}
	if len(content.Suggestions) != len(want) {
		t.Fatalf("suggestions = %d entries, want %d: %+v", len(content.Suggestions), len(want), content.Suggestions)
	}
	for i := range want {
		if content.Suggestions[i] != want[i] {
			t.Errorf("suggestion[%d] = %+v, want %+v", i, content.Suggestions[i], want[i])
		}
	}
}

// TestSuggestionNoDimensionBelowSixty asserts that a dimension at or
// above 60 carries no template suggestion and that completed department
// reports carry no linkage suggestion.
func TestSuggestionNoDimensionBelowSixty(t *testing.T) {
	base := time.Date(2026, 8, 14, 10, 0, 0, 0, time.UTC)
	indicators := []Indicator{
		fixtureIndicator(idAlertResponse, DimensionResponseSpeed, "预警响应速度", 1, false, 1),
		fixtureIndicator(idProcessStandard, DimensionDisposalStandard, "处置流程规范性", 1, false, 1),
	}
	run := ReportRun{ID: "run-1", Status: reportRunStatusCompleted, StartedAt: timestamp(base, 0)}
	simEvents := []ReportSimEvent{{TriggeredAt: timestamp(base, 10)}} // 预警响应速度 98
	stepRecords := []ReportStepRecord{{Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"}, {Status: "已执行"}}

	content := ComposeReport(ScoringInput{
		Run:         run,
		Indicators:  indicators,
		SimEvents:   simEvents,
		StepRecords: stepRecords,
	})
	if len(content.Suggestions) != 0 {
		t.Errorf("suggestions = %+v, want none (all dimensions >= 60)", content.Suggestions)
	}

	// Completed-only department reports must not append the linkage
	// suggestion either.
	content = ComposeReport(ScoringInput{
		Run:               run,
		Indicators:        indicators,
		SimEvents:         simEvents,
		StepRecords:       stepRecords,
		DepartmentReports: []ReportDepartmentReport{{Status: "已完成"}, {Status: "已完成"}},
	})
	if len(content.Suggestions) != 0 {
		t.Errorf("suggestions with completed reports = %+v, want none", content.Suggestions)
	}
}

// TestComposeEmptyReportShape pins the 演练数据不足 report: overall 0,
// empty (non-nil) dimension/indicator objects and the single data-
// insufficient suggestion with empty dimension and level.
func TestComposeEmptyReportShape(t *testing.T) {
	content := ComposeReport(ScoringInput{
		Run:        ReportRun{ID: "run-1", Status: reportRunStatusCompleted},
		Indicators: fixtureIndicators(),
	})
	if content.DimensionScores == nil {
		t.Error("dimension_scores is nil, want an empty object")
	}
	if content.IndicatorScores == nil {
		t.Error("indicator_scores is nil, want an empty object")
	}
	assertInsufficientDataSuggestion(t, content.Suggestions)
}

// ─── 断言辅助 ────────────────────────────────────────────────────────

func assertAutoScore(t *testing.T, content ReportContent, id string, want float64, label string) {
	t.Helper()
	entry, ok := content.IndicatorScores[id]
	if !ok {
		t.Fatalf("%s: indicator %s missing from indicator_scores", label, id)
	}
	if entry.Auto == nil {
		t.Fatalf("%s: indicator %s carries no auto source", label, id)
	}
	if *entry.Auto != want {
		t.Errorf("%s: auto = %v, want %v", label, *entry.Auto, want)
	}
	if entry.Score != want {
		t.Errorf("%s: final score = %v, want %v", label, entry.Score, want)
	}
}

func assertInsufficientDataSuggestion(t *testing.T, suggestions []Suggestion) {
	t.Helper()
	if len(suggestions) != 1 {
		t.Fatalf("suggestions = %+v, want the single 演练数据不足 notice", suggestions)
	}
	if suggestions[0].Dimension != "" || suggestions[0].Level != "" {
		t.Errorf("notice = %+v, want empty dimension and level", suggestions[0])
	}
	if suggestions[0].Text != insufficientDataText {
		t.Errorf("notice text = %q, want %q", suggestions[0].Text, insufficientDataText)
	}
}

func float64Ptr(value float64) *float64 { return &value }

func sameIndicatorScore(t *testing.T, got, want IndicatorScore) bool {
	t.Helper()
	if got.Score != want.Score {
		return false
	}
	for name, pair := range map[string][2]*float64{
		"auto":     {got.Auto, want.Auto},
		"expert":   {got.Expert, want.Expert},
		"selfPeer": {got.SelfPeer, want.SelfPeer},
		"demo":     {got.Demo, want.Demo},
	} {
		if pair[0] == nil || pair[1] == nil {
			if pair[0] != pair[1] {
				t.Logf("source %s presence differs: %v vs %v", name, pair[0], pair[1])
				return false
			}
			continue
		}
		if *pair[0] != *pair[1] {
			return false
		}
	}
	return true
}
