package httpapi

import (
	"net/http"
	"sort"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/ulid"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// indicatorsPagePath is the server-rendered evaluation indicator
// configuration page (htmx SSR, no shared client, no database).
const indicatorsPagePath = "/demo/evaluation/indicators"

// indicatorDimensionOrder is the fixed display order of the six
// evaluation dimensions on the page, mirroring the evaluation package
// enum order and the list ordering of the 4.1 contract (dimension,
// sort_order, created_at ascending).
var indicatorDimensionOrder = []evaluation.Dimension{
	evaluation.DimensionResponseSpeed,
	evaluation.DimensionDisposalStandard,
	evaluation.DimensionCoordination,
	evaluation.DimensionAudienceSafety,
	evaluation.DimensionRelicSafety,
	evaluation.DimensionPublicOpinion,
}

// handleIndicatorsPage renders the evaluation indicator configuration
// page. Non-GET requests on the page path yield the repository-standard
// 405 JSON with Allow: GET (same convention as the API resource routes
// and the command/console pages). The dimension query parameter follows
// the 4.1 list contract: a non-empty value must be one of the six enum
// values, otherwise 400 {"error":"invalid dimension"}; an empty or
// missing parameter renders every dimension. The display data is
// injected in memory from evaluation.SeedData — no database, no API
// call — and every seed row gets a fresh server-minted 26-character
// Crockford Base32 ULID per render, exactly like the backing API mints
// ids at creation; the page itself never constructs ids.
func handleIndicatorsPage(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	dimension := evaluation.Dimension(r.URL.Query().Get("dimension"))
	if dimension != "" && !dimension.Valid() {
		writeError(w, http.StatusBadRequest, "invalid dimension")
		return
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderIndicators(w, indicatorsPageData(evaluation.SeedData, dimension)); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// indicatorsPageData converts the built-in seed indicators into the
// page view model, applying the dimension filter (an empty dimension
// keeps every row). The rows are grouped by the six dimensions in the
// fixed display order; within a group the per-dimension sort_order is
// assigned 1..N in SeedData order (mirroring the seed function) and the
// rows are ordered by sort_order ascending, matching the 4.1 contract
// list ordering. The seed rows carry no ids and no weight (every
// built-in row uses the domain default 1), so each render mints a fresh
// server-side id and displays the default weight; the demo flag flows
// through to the 「演示」 badge.
func indicatorsPageData(seed []evaluation.SeedIndicator, dimension evaluation.Dimension) web.IndicatorsPageData {
	rows := seed
	if dimension != "" {
		rows = nil
		for _, item := range seed {
			if item.Dimension == dimension {
				rows = append(rows, item)
			}
		}
	}
	data := web.IndicatorsPageData{ActiveDimension: string(dimension)}
	for _, candidate := range indicatorDimensionOrder {
		data.DimensionOptions = append(data.DimensionOptions, string(candidate))
	}
	for _, groupDimension := range indicatorDimensionOrder {
		var views []web.IndicatorView
		order := 0
		for _, item := range rows {
			if item.Dimension != groupDimension {
				continue
			}
			order++
			views = append(views, web.IndicatorView{
				ID:          ulid.New(),
				Dimension:   string(item.Dimension),
				Title:       item.Title,
				Weight:      1, // the seed default of the domain
				Demo:        item.Demo,
				SortOrder:   order,
				Description: item.Description,
			})
		}
		if len(views) == 0 {
			continue
		}
		sort.SliceStable(views, func(i, j int) bool { return views[i].SortOrder < views[j].SortOrder })
		data.Groups = append(data.Groups, web.DimensionGroupView{Name: string(groupDimension), Indicators: views})
	}
	return data
}
