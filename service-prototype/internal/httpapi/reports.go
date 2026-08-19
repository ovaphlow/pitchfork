package httpapi

import (
	"context"
	"errors"
	"net/http"
	"strconv"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
)

// The evaluation report routes: the generate action nests under the
// owning drill run (POST /evaluation/runs/{rid}/reports/generate, a
// literal segment more specific than the unified /{resource} wildcard),
// the single-report route reads the report of one run (GET
// /evaluation/runs/{rid}/report) and the collection route lists the
// reports with a run_id filter (GET /evaluation/reports). The literal
// evaluation segments never collide with the drills routes.
const (
	reportsBase         = prototypePrefix + "/evaluation/reports"
	reportBase          = prototypePrefix + "/evaluation/runs/{rid}/report"
	reportsGenerateBase = prototypePrefix + "/evaluation/runs/{rid}/reports/generate"
)

// reportsHandler adapts the evaluation report service to the HTTP
// routing layer. The generate route answers 201 on the first
// generation of a run and 200 on an in-place overwrite, both with the
// full report object; the single-report route answers 200 or 404; the
// collection only serves GET (other methods yield a JSON 405 with
// Allow).
type reportsHandler struct {
	service *evaluation.ReportService
}

func newReportsHandler(reportStore evaluation.ReportStore, indicatorStore evaluation.Store, scoreStore evaluation.ScoreStore, source evaluation.ReportDataSource) *reportsHandler {
	return &reportsHandler{
		service: evaluation.NewReportService(reportStore, indicatorStore, scoreStore, source),
	}
}

// drillsReportSource adapts the drill and dispatch stores to the
// evaluation ReportDataSource interface at the composition root: the
// evaluation package never imports the drills or dispatch packages, so
// the run, sim-event, step-record, order, message and department-report
// data of the scoring engine is injected behind the interface. The
// drills ErrRunNotFound travels unchanged and maps to HTTP 404 in
// writeReportError.
type drillsReportSource struct {
	drillStore    drills.Store
	dispatchStore dispatch.Store
}

func (s drillsReportSource) GetRun(ctx context.Context, runID string) (evaluation.ReportRun, error) {
	run, err := s.drillStore.GetRun(ctx, runID)
	if err != nil {
		return evaluation.ReportRun{}, err
	}
	return evaluation.ReportRun{
		ID:        run.ID,
		Status:    string(run.Status),
		StartedAt: run.StartedAt,
		Metadata:  run.Metadata,
	}, nil
}

func (s drillsReportSource) ListSimEvents(ctx context.Context, runID string) ([]evaluation.ReportSimEvent, error) {
	events, _, err := s.drillStore.ListSimEvents(ctx, runID, drills.SimEventFilter{Limit: -1})
	if err != nil {
		return nil, err
	}
	converted := make([]evaluation.ReportSimEvent, 0, len(events))
	for _, event := range events {
		converted = append(converted, evaluation.ReportSimEvent{
			TriggeredAt: event.TriggeredAt,
			CreatedAt:   event.CreatedAt,
		})
	}
	return converted, nil
}

func (s drillsReportSource) ListStepRecords(ctx context.Context, runID string) ([]evaluation.ReportStepRecord, error) {
	records, err := s.drillStore.ListStepRecordsByRun(ctx, runID)
	if err != nil {
		return nil, err
	}
	converted := make([]evaluation.ReportStepRecord, 0, len(records))
	for _, record := range records {
		converted = append(converted, evaluation.ReportStepRecord{Status: string(record.Status)})
	}
	return converted, nil
}

func (s drillsReportSource) ListOrders(ctx context.Context, runID string) ([]evaluation.ReportOrder, error) {
	orders, _, err := s.dispatchStore.ListOrders(ctx, runID, dispatch.OrderFilter{Limit: -1})
	if err != nil {
		return nil, err
	}
	converted := make([]evaluation.ReportOrder, 0, len(orders))
	for _, order := range orders {
		converted = append(converted, evaluation.ReportOrder{IssuedAt: order.IssuedAt})
	}
	return converted, nil
}

func (s drillsReportSource) ListMessages(ctx context.Context, runID string) ([]evaluation.ReportMessage, error) {
	messages, _, err := s.dispatchStore.ListMessages(ctx, runID, dispatch.MessageFilter{Limit: -1})
	if err != nil {
		return nil, err
	}
	converted := make([]evaluation.ReportMessage, 0, len(messages))
	for _, message := range messages {
		converted = append(converted, evaluation.ReportMessage{
			SenderType: string(message.SenderType),
			SentAt:     message.SentAt,
		})
	}
	return converted, nil
}

func (s drillsReportSource) ListDepartmentReports(ctx context.Context, runID string) ([]evaluation.ReportDepartmentReport, error) {
	reports, _, err := s.dispatchStore.ListDepartments(ctx, runID, dispatch.DepartmentFilter{Limit: -1})
	if err != nil {
		return nil, err
	}
	converted := make([]evaluation.ReportDepartmentReport, 0, len(reports))
	for _, report := range reports {
		converted = append(converted, evaluation.ReportDepartmentReport{Status: string(report.Status)})
	}
	return converted, nil
}

// handleGenerate serves POST /evaluation/runs/{rid}/reports/generate:
// 201 on the first generation, 200 on an in-place overwrite, both with
// the full report object. A missing run is a 404; a run that is not
// 已完成 is a 400. The route never reads a request body (generation has
// no required fields).
func (h *reportsHandler) handleGenerate(w http.ResponseWriter, r *http.Request) {
	report, created, err := h.service.GenerateReport(r.Context(), r.PathValue("rid"))
	if err != nil {
		writeReportError(w, err)
		return
	}
	status := http.StatusOK
	if created {
		status = http.StatusCreated
	}
	writeJSON(w, status, report)
}

// handleGetByRun serves GET /evaluation/runs/{rid}/report: 200 with the
// stored report, 404 when no report exists for the run.
func (h *reportsHandler) handleGetByRun(w http.ResponseWriter, r *http.Request) {
	report, err := h.service.GetReportByRun(r.Context(), r.PathValue("rid"))
	if err != nil {
		writeReportError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, report)
}

func (h *reportsHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.list(w, r)
	default:
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// reportListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type reportListResponse struct {
	Records []evaluation.Report `json:"records"`
	Meta    metaResponse        `json:"meta"`
}

func (h *reportsHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseReportListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListReports(r.Context(), filter)
	if err != nil {
		writeReportError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, reportListResponse{Records: records, Meta: metaResponse{Total: total}})
}

// parseReportListFilter reads the run_id / limit / offset query
// parameters. run_id is an exact match with no further validation (an
// unknown or malformed run_id simply matches nothing, the established
// scenario_id convention of the drills list); limit/offset must be
// non-negative integers (default limit 50), otherwise 400 with the
// repository wording "invalid limit" / "invalid offset".
func parseReportListFilter(w http.ResponseWriter, r *http.Request) (evaluation.ReportFilter, bool) {
	query := r.URL.Query()
	filter := evaluation.ReportFilter{Limit: defaultPageSize}

	if raw := query.Get("run_id"); raw != "" {
		filter.RunID = raw
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return evaluation.ReportFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return evaluation.ReportFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

// writeReportError maps the evaluation report service errors to JSON
// error responses: validation errors (a run that is not 已完成) become
// 400, unknown runs and missing reports 404, everything else 500. The
// error body follows the repository convention { "error": ... }.
func writeReportError(w http.ResponseWriter, err error) {
	var validationError *evaluation.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrRunNotFound),
		errors.Is(err, evaluation.ErrReportNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
