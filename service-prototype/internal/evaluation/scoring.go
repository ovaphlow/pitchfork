package evaluation

import (
	"fmt"
	"math"
	"strconv"
	"time"
)

// ScoringInput carries every piece of data the automatic scoring
// engine reads: the completed drill run, the full indicator dictionary
// of the run, the human score records of the run and the
// drill/dispatch records (sim events, step records, orders, messages,
// department reports) the seven deterministic formulas consume.
type ScoringInput struct {
	Run               ReportRun
	Indicators        []Indicator
	Scores            []Score
	SimEvents         []ReportSimEvent
	StepRecords       []ReportStepRecord
	Orders            []ReportOrder
	Messages          []ReportMessage
	DepartmentReports []ReportDepartmentReport
}

// ReportContent is the engine output: the weighted overall score, the
// per-dimension scores with their breakdowns, the per-indicator final
// scores with their source values and the rule-based suggestions. It
// becomes the JSONB snapshot fields of a Report.
type ReportContent struct {
	OverallScore    float64
	DimensionScores map[Dimension]DimensionScore
	IndicatorScores map[string]IndicatorScore
	Suggestions     []Suggestion
}

// Suggestion levels of the report rule (改进建议 level 枚举): a
// dimension average below 40 is 严重, between 40 and 59.9 关注; the
// linkage follow-up suggestion is always 关注.
const (
	SuggestionLevelSevere = "严重"
	SuggestionLevelWatch  = "关注"
)

// The six fixed dimension suggestion templates of the specification,
// with the dimension average as the YY.Y placeholder (1 decimal). The
// threshold check and the placeholder use the same rounded dimension
// score, so the text always agrees with the level rule.
var dimensionSuggestionTemplates = map[Dimension]string{
	DimensionResponseSpeed:    "响应速度维度平均分 %s 分，未达 60 分，建议优化预警发现、信息上报与预案启动流程，缩短应急响应用时。",
	DimensionDisposalStandard: "处置规范性维度平均分 %s 分，未达 60 分，建议加强处置流程执行与操作标准培训，减少流程跳过。",
	DimensionCoordination:     "协同效率维度平均分 %s 分，未达 60 分，建议加强部门联动与信息共享，提升协同处置效率。",
	DimensionAudienceSafety:   "观众安全维度平均分 %s 分，未达 60 分，建议强化观众疏散组织与秩序维护措施。",
	DimensionRelicSafety:      "文物安全维度平均分 %s 分，未达 60 分，建议完善文物转移保护预案并加强损失防控。",
	DimensionPublicOpinion:    "舆情管控维度平均分 %s 分，未达 60 分，建议健全舆情监测预警与信息发布机制。",
}

// linkageSuggestionText is appended as an independent suggestion
// (dimension=协同效率, level=关注) when a department report of the run
// is not 已完成 yet; it coexists with the dimension template
// suggestions.
const linkageSuggestionText = "存在未完成联动处置的部门，建议跟进相关部门的处置进度并补全联动记录。"

// insufficientDataText is the single suggestion of a report without
// any scored indicator (演练数据不足).
const insufficientDataText = "演练数据不足，请补全评分后重新生成。"

// reportDepartmentStatusCompleted is the 已完成 department-report
// status. The evaluation package never imports the dispatch package, so
// the value is mirrored here and the dispatch status travels through
// ReportDepartmentReport as a plain string.
const reportDepartmentStatusCompleted = "已完成"

// reportMessageSenderField is the 现场人员 message sender type of the
// 信息传递及时性 formula.
const reportMessageSenderField = "现场人员"

// round1 rounds a score to 1 decimal (四舍五入保留 1 位小数; scores are
// non-negative, so math.Round's half-away-from-zero is the 四舍五入
// rounding).
func round1(value float64) float64 {
	return math.Round(value*10) / 10
}

// latencyScore applies the generic latency formula of the specification:
// max(0, min(100, 100 − seconds ÷ 5)); seconds is the float
// duration.Seconds() value and the score is rounded to 1 decimal.
func latencyScore(seconds float64) float64 {
	score := 100 - seconds/5
	if score < 0 {
		score = 0
	}
	if score > 100 {
		score = 100
	}
	return round1(score)
}

// latencyBetween applies latencyScore to the duration between two
// timestamps; it reports whether both timestamps exist.
func latencyBetween(start, end *time.Time) (float64, bool) {
	if start == nil || end == nil {
		return 0, false
	}
	return latencyScore(end.Sub(*start).Seconds()), true
}

// earliestEventTrigger returns the first event trigger moment of the
// run — the earliest sim_event.triggered_at, tie-break by the earliest
// created_at (钉死口径) — and whether any triggered event exists at all.
func earliestEventTrigger(events []ReportSimEvent) (time.Time, bool) {
	var first time.Time
	var firstCreated time.Time
	found := false
	for _, event := range events {
		if event.TriggeredAt == nil {
			continue
		}
		if !found || event.TriggeredAt.Before(first) ||
			(event.TriggeredAt.Equal(first) && event.CreatedAt.Before(firstCreated)) {
			first = *event.TriggeredAt
			firstCreated = event.CreatedAt
			found = true
		}
	}
	return first, found
}

// earliestTimestamp returns the earliest non-nil timestamp of the
// slice and whether one exists.
func earliestTimestamp(values []*time.Time) (time.Time, bool) {
	var first time.Time
	found := false
	for _, value := range values {
		if value == nil {
			continue
		}
		if !found || value.Before(first) {
			first = *value
			found = true
		}
	}
	return first, found
}

// autoScore computes the deterministic engine score of one computable
// indicator (demo=false). The seven formulas of the specification are
// bound to the dictionary by (dimension, sort_order): the three 响应速
// 度 slots are 预警发现时间 (sort 1), 预案启动时间 (sort 2) and 信息上报时
// 间 (sort 3); the two 处置规范性 slots are 流程执行完整度 (sort 1) and
// 操作标准符合度 (sort 2); the two 协同效率 slots are 部门联动顺畅度
// (sort 1) and 信息传递及时性 (sort 2). The slot assignment follows the
// seed dictionary of 000023, whose descriptions document the mapping
// (预警响应速度: 预警发现; 指挥调度响应速度: 指令下达/预案启动; 力量到场速
// 度: 到场首报/信息上报). A slot whose underlying data is missing
// carries no score at all: the formula value 0 of the specification
// never counts as a score, so an indicator without data is 无分 — the
// pinned 演练数据不足 report shape (empty indicator_scores) requires
// exactly this.
func autoScore(input ScoringInput, indicator Indicator, firstEvent time.Time, hasEvent bool) (float64, bool) {
	switch indicator.Dimension {
	case DimensionResponseSpeed:
		switch indicator.SortOrder {
		case 1: // 预警发现时间: latency(首个 sim_event.triggered_at − run.started_at)
			if !hasEvent {
				return 0, false
			}
			return latencyBetween(input.Run.StartedAt, &firstEvent)
		case 2: // 预案启动时间: latency(首条 order.issued_at − 首个事件触发时刻)
			if !hasEvent {
				return 0, false
			}
			return latencyBetween(&firstEvent, earliestOrderIssued(input.Orders))
		case 3: // 信息上报时间: latency(首条 message.sent_at − 首个事件触发时刻)
			if !hasEvent {
				return 0, false
			}
			return latencyBetween(&firstEvent, earliestMessageSent(input.Messages, ""))
		}
	case DimensionDisposalStandard:
		switch indicator.SortOrder {
		case 1: // 流程执行完整度: 已执行 ÷ 步骤总数 × 100（含待执行/跳过；无步骤 → 0）
			executed, _, total := countSteps(input.StepRecords)
			if total == 0 {
				return 0, false
			}
			return round1(float64(executed) / float64(total) * 100), true
		case 2: // 操作标准符合度: 已执行 ÷ (已执行+跳过) × 100；分母 0 → 0
			executed, skipped, _ := countSteps(input.StepRecords)
			if executed+skipped == 0 {
				return 0, false
			}
			return round1(float64(executed) / float64(executed+skipped) * 100), true
		}
	case DimensionCoordination:
		switch indicator.SortOrder {
		case 1: // 部门联动顺畅度: 已完成部门报告 ÷ 报告总数 × 100；无报告 → 0
			completed, total := countDepartmentReports(input.DepartmentReports)
			if total == 0 {
				return 0, false
			}
			return round1(float64(completed) / float64(total) * 100), true
		case 2: // 信息传递及时性: latency(首条 sender_type=现场人员 消息 − 首个事件触发时刻)
			if !hasEvent {
				return 0, false
			}
			return latencyBetween(&firstEvent, earliestMessageSent(input.Messages, reportMessageSenderField))
		}
	}
	return 0, false
}

func earliestOrderIssued(orders []ReportOrder) *time.Time {
	var values []*time.Time
	for _, order := range orders {
		values = append(values, order.IssuedAt)
	}
	first, ok := earliestTimestamp(values)
	if !ok {
		return nil
	}
	return &first
}

func earliestMessageSent(messages []ReportMessage, senderType string) *time.Time {
	var values []*time.Time
	for _, message := range messages {
		if senderType != "" && message.SenderType != senderType {
			continue
		}
		values = append(values, message.SentAt)
	}
	first, ok := earliestTimestamp(values)
	if !ok {
		return nil
	}
	return &first
}

// countSteps tallies the step records of the run: executed (已执行),
// skipped (跳过) and the total (including 待执行).
func countSteps(records []ReportStepRecord) (executed, skipped, total int) {
	for _, record := range records {
		switch record.Status {
		case "已执行":
			executed++
		case "跳过":
			skipped++
		}
	}
	return executed, skipped, len(records)
}

// countDepartmentReports tallies the department reports of the run:
// the completed ones (已完成) and the total.
func countDepartmentReports(reports []ReportDepartmentReport) (completed, total int) {
	for _, report := range reports {
		if report.Status == reportDepartmentStatusCompleted {
			completed++
		}
	}
	return completed, len(reports)
}

// demoScore reads the presentation score of one demo indicator from
// run.metadata.demo_scores (key = indicator id, value 0–100). A missing
// key, a non-numeric value or an out-of-range value carries no score.
func demoScore(metadata map[string]any, indicatorID string) (float64, bool) {
	raw, ok := metadata["demo_scores"]
	if !ok {
		return 0, false
	}
	values, ok := raw.(map[string]any)
	if !ok {
		return 0, false
	}
	value, ok := values[indicatorID]
	if !ok {
		return 0, false
	}
	switch number := value.(type) {
	case float64:
		if number < 0 || number > 100 {
			return 0, false
		}
		return round1(number), true
	case int:
		if number < 0 || number > 100 {
			return 0, false
		}
		return round1(float64(number)), true
	}
	return 0, false
}

// meanScore averages the raw score records of one source (rounded to 1
// decimal); it reports whether any record exists.
func meanScore(scores []int) (float64, bool) {
	if len(scores) == 0 {
		return 0, false
	}
	sum := 0
	for _, score := range scores {
		sum += score
	}
	return round1(float64(sum) / float64(len(scores))), true
}

// scoreIndicator produces the final score entry of one indicator: the
// present sources (auto for computable indicators with data, expert,
// self_peer, demo for presentation indicators with a demo_scores value)
// averaged arithmetically and rounded to 1 decimal. The indicator is
// 无分 (absent) when no source is present; a presentation indicator is
// additionally only counted when demo_scores provides its value (pinned
// 口径: 演示指标仅在 metadata.demo_scores 提供值时计入).
func scoreIndicator(input ScoringInput, indicator Indicator, scoresByIndicator map[string][]Score, firstEvent time.Time, hasEvent bool) (IndicatorScore, bool) {
	var auto *float64
	if !indicator.Demo {
		if value, ok := autoScore(input, indicator, firstEvent, hasEvent); ok {
			auto = &value
		}
	}
	var expertScores, selfPeerScores []int
	for _, score := range scoresByIndicator[indicator.ID] {
		switch score.ScoreType {
		case ScoreTypeExpert:
			expertScores = append(expertScores, score.Score)
		case ScoreTypeSelf, ScoreTypePeer:
			selfPeerScores = append(selfPeerScores, score.Score)
		}
	}
	var expert *float64
	if value, ok := meanScore(expertScores); ok {
		expert = &value
	}
	var selfPeer *float64
	if value, ok := meanScore(selfPeerScores); ok {
		selfPeer = &value
	}
	var demo *float64
	if indicator.Demo {
		if value, ok := demoScore(input.Run.Metadata, indicator.ID); ok {
			demo = &value
		}
	}
	if indicator.Demo && demo == nil {
		return IndicatorScore{}, false
	}
	sources := 0
	total := 0.0
	for _, value := range []*float64{auto, expert, selfPeer, demo} {
		if value != nil {
			total += *value
			sources++
		}
	}
	if sources == 0 {
		return IndicatorScore{}, false
	}
	return IndicatorScore{
		Score:    round1(total / float64(sources)),
		Auto:     auto,
		Expert:   expert,
		SelfPeer: selfPeer,
		Demo:     demo,
	}, true
}

// weightedMean computes the weighted average of the scores with the
// indicator weights (weights normalized: sum(score×weight) ÷
// sum(weight)), rounded to 1 decimal. A zero total weight is guarded by
// the caller (the dimension only aggregates scored indicators).
func weightedMean(scores []float64, weights []int) float64 {
	totalWeight := 0
	total := 0.0
	for i, score := range scores {
		totalWeight += weights[i]
		total += score * float64(weights[i])
	}
	if totalWeight == 0 {
		return 0
	}
	return round1(total / float64(totalWeight))
}

// format1 renders a score with exactly 1 decimal (the YY.Y placeholder
// of the suggestion templates).
func format1(score float64) string {
	return strconv.FormatFloat(score, 'f', 1, 64)
}

// buildSuggestions applies the suggestion rules: every dimension with a
// scored average below 60 gets its fixed template suggestion (below 40
// 严重, 40–59.9 关注), a run with non-已完成 department reports gets the
// appended linkage follow-up (dimension=协同效率, level=关注) and a
// report without any scored indicator gets the single 演练数据不足
// notice.
func buildSuggestions(dimensionScores map[Dimension]DimensionScore, departmentReports []ReportDepartmentReport) []Suggestion {
	if len(dimensionScores) == 0 {
		return []Suggestion{{Text: insufficientDataText}}
	}
	var suggestions []Suggestion
	for _, dimension := range validDimensions {
		score, ok := dimensionScores[dimension]
		if !ok {
			continue
		}
		if score.Score >= 60 {
			continue
		}
		level := SuggestionLevelWatch
		if score.Score < 40 {
			level = SuggestionLevelSevere
		}
		suggestions = append(suggestions, Suggestion{
			Dimension: dimension,
			Level:     level,
			Text:      fmt.Sprintf(dimensionSuggestionTemplates[dimension], format1(score.Score)),
		})
	}
	hasUnfinishedReport := false
	for _, report := range departmentReports {
		if report.Status != reportDepartmentStatusCompleted {
			hasUnfinishedReport = true
			break
		}
	}
	if hasUnfinishedReport {
		suggestions = append(suggestions, Suggestion{
			Dimension: DimensionCoordination,
			Level:     SuggestionLevelWatch,
			Text:      linkageSuggestionText,
		})
	}
	return suggestions
}

// ComposeReport runs the automatic scoring engine over the input and
// returns the full report content: the per-indicator final scores with
// their source values, the per-dimension weighted averages with their
// breakdowns, the weighted overall score and the rule-based
// suggestions. The engine is deterministic and pure (no store access);
// the service wraps the content into a Report with the id and the
// timestamps. Maps are always non-nil, so the empty 演练数据不足 report
// serializes as {} / [] and never as null.
func ComposeReport(input ScoringInput) ReportContent {
	content := ReportContent{
		DimensionScores: make(map[Dimension]DimensionScore),
		IndicatorScores: make(map[string]IndicatorScore),
	}
	scoresByIndicator := make(map[string][]Score, len(input.Scores))
	for _, score := range input.Scores {
		scoresByIndicator[score.IndicatorID] = append(scoresByIndicator[score.IndicatorID], score)
	}
	firstEvent, hasEvent := earliestEventTrigger(input.SimEvents)

	type scoredIndicator struct {
		id        string
		dimension Dimension
		score     float64
		weight    int
	}
	var scoredAll []scoredIndicator
	for _, indicator := range input.Indicators {
		entry, present := scoreIndicator(input, indicator, scoresByIndicator, firstEvent, hasEvent)
		if !present {
			continue
		}
		content.IndicatorScores[indicator.ID] = entry
		scoredAll = append(scoredAll, scoredIndicator{
			id:        indicator.ID,
			dimension: indicator.Dimension,
			score:     entry.Score,
			weight:    indicator.Weight,
		})
	}

	byDimension := make(map[Dimension][]scoredIndicator, len(scoredAll))
	for _, item := range scoredAll {
		byDimension[item.dimension] = append(byDimension[item.dimension], item)
	}
	for _, dimension := range validDimensions {
		items := byDimension[dimension]
		if len(items) == 0 {
			continue
		}
		breakdown := make(map[string]float64, len(items))
		scores := make([]float64, len(items))
		weights := make([]int, len(items))
		for i, item := range items {
			breakdown[item.id] = item.score
			scores[i] = item.score
			weights[i] = item.weight
		}
		content.DimensionScores[dimension] = DimensionScore{
			Score:     weightedMean(scores, weights),
			Breakdown: breakdown,
		}
	}
	if len(scoredAll) > 0 {
		scores := make([]float64, len(scoredAll))
		weights := make([]int, len(scoredAll))
		for i, item := range scoredAll {
			scores[i] = item.score
			weights[i] = item.weight
		}
		content.OverallScore = weightedMean(scores, weights)
	}
	content.Suggestions = buildSuggestions(content.DimensionScores, input.DepartmentReports)
	return content
}
