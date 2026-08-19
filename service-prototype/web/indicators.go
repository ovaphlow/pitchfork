package web

import (
	"html/template"
	"io"
)

// IndicatorsPageData carries the display data of the evaluation
// indicator configuration page (评估指标配置). The page renders purely
// from this in-memory payload — no database access, no API call — so
// the caller builds it from the built-in seed data
// (evaluation.SeedData), applies the dimension filter and mints the
// server-side ids.
type IndicatorsPageData struct {
	// ActiveDimension is the applied dimension filter ("" renders every
	// dimension); the filter form selects it.
	ActiveDimension string
	// DimensionOptions is the six evaluation dimensions in the fixed
	// display order (响应速度/处置规范性/协同效率/观众安全/文物安全/舆情
	// 管控), backing the filter, create and edit selects.
	DimensionOptions []string
	// Groups is the indicators grouped by dimension in the fixed order;
	// within a group the indicators are ordered by sort_order ascending
	// (the list ordering of the 4.1 contract).
	Groups []DimensionGroupView
}

// DimensionGroupView is one dimension group of the page: the dimension
// name and its indicators.
type DimensionGroupView struct {
	Name       string
	Indicators []IndicatorView
}

// IndicatorView is one evaluation indicator of the page: the
// server-generated id (minted per render, exactly like the backing API
// mints ids at creation), the business fields of the contract and the
// demo flag backing the 「演示」 badge. The server-maintained fields
// (created_at / updated_at / created_by) are never part of the view:
// the page neither displays nor submits them.
type IndicatorView struct {
	ID          string
	Dimension   string
	Title       string
	Weight      int
	Demo        bool
	SortOrder   int
	Description string
}

// indicatorsTemplate is the parsed template collection of the
// evaluation indicator configuration page (layout + indicators page).
// It lives in its own template set because the layout's content/title
// hooks are page-specific: every page defines its own content block,
// and one shared parse set would let the alphabetically last page win
// for every page (same pattern as the scenarios and drills pages).
var indicatorsTemplate = template.Must(template.ParseFS(templateFiles, "templates/layout.html", "templates/indicators.html"))

// RenderIndicators renders the evaluation indicator configuration page
// (layout + indicators content) with the given in-memory display data.
// All user-controlled input is HTML-escaped by html/template.
func RenderIndicators(w io.Writer, data IndicatorsPageData) error {
	return indicatorsTemplate.ExecuteTemplate(w, "layout.html", data)
}
