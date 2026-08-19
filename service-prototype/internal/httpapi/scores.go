package httpapi

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strconv"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
)

// scoresBase is the unified resource path of the evaluation scores
// (评估评分记录). The collection and the item routes live under the
// owning drill run (/evaluation/runs/{rid}/scores and
// /evaluation/runs/{rid}/scores/{sid}); the literal evaluation segment
// is more specific than the unified /{resource} wildcard and never
// collides with it.
const scoresBase = prototypePrefix + "/evaluation/runs/{rid}/scores"

// scoresHandler adapts the evaluation score service to the HTTP routing
// layer. It serves the per-run score collection (GET list with
// score_type / indicator_id filters and limit/offset pagination / POST
// create) and the item routes (GET / PUT / DELETE by id); other methods
// yield a JSON 405 with Allow. The owning run comes from the route
// path: a missing run is a 404 on every route.
type scoresHandler struct {
	service *evaluation.ScoreService
}

func newScoresHandler(indicatorStore evaluation.Store, drillStore drills.Store, scoreStore evaluation.ScoreStore) *scoresHandler {
	return &scoresHandler{
		service: evaluation.NewScoreService(scoreStore, indicatorStore, drillsRunExistence{store: drillStore}),
	}
}

// drillsRunExistence adapts the drills store to the evaluation
// RunExistenceChecker interface at the composition root: the evaluation
// package never imports the drills package, so the run lookup is
// injected behind the interface. The drills ErrRunNotFound travels
// unchanged and maps to HTTP 404 in writeScoreError.
type drillsRunExistence struct {
	store drills.Store
}

func (c drillsRunExistence) RunExists(ctx context.Context, runID string) error {
	_, err := c.store.GetRun(ctx, runID)
	return err
}

func (h *scoresHandler) handleCollection(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.list(w, r)
	case http.MethodPost:
		h.create(w, r)
	default:
		w.Header().Set("Allow", "GET, POST")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

func (h *scoresHandler) handleItem(w http.ResponseWriter, r *http.Request) {
	switch r.Method {
	case http.MethodGet:
		h.get(w, r)
	case http.MethodPut:
		h.update(w, r)
	case http.MethodDelete:
		h.delete(w, r)
	default:
		w.Header().Set("Allow", "GET, PUT, DELETE")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
	}
}

// scoreBody mirrors the client-supplied fields of the request body.
// run_id is never part of the body (the owning run is decided by the
// route path); indicator_id is only read on create — a PUT body that
// carries it has it ignored, the existing record decides it. Score is a
// pointer so the handler can tell a missing field from an explicit 0: 0
// is a legal score, so the Go zero value must not pass for the required
// field. comment and created_by default to empty strings; target is
// required for 自评/互评 and forced to an empty string for 专家评分 (the
// service layer enforces both).
type scoreBody struct {
	IndicatorID string               `json:"indicator_id"`
	ScoreType   evaluation.ScoreType `json:"score_type"`
	Rater       string               `json:"rater"`
	Target      string               `json:"target"`
	Score       *int                 `json:"score"`
	Comment     string               `json:"comment"`
	CreatedBy   string               `json:"created_by"`
}

// decodeScoreBody reads a single JSON object from the request body. An
// empty, malformed or non-object body (including a JSON null) yields a
// 400 { "error": ... } response, matching the decode convention of the
// other PUT resources; an empty object {} is legal but fails later on
// the missing required fields.
func decodeScoreBody(w http.ResponseWriter, r *http.Request) (scoreBody, bool) {
	raw, err := io.ReadAll(io.LimitReader(r.Body, 1<<20))
	trimmed := strings.TrimSpace(string(raw))
	if err != nil || trimmed == "" || trimmed == "null" {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return scoreBody{}, false
	}
	var body scoreBody
	if err := json.Unmarshal(raw, &body); err != nil {
		writeError(w, http.StatusBadRequest, "invalid request body")
		return scoreBody{}, false
	}
	return body, true
}

func (h *scoresHandler) create(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeScoreBody(w, r)
	if !ok {
		return
	}
	score, err := h.service.CreateScore(r.Context(), r.PathValue("rid"), evaluation.ScoreInput{
		IndicatorID: body.IndicatorID,
		ScoreType:   body.ScoreType,
		Rater:       body.Rater,
		Target:      body.Target,
		Score:       body.Score,
		Comment:     body.Comment,
		CreatedBy:   body.CreatedBy,
	})
	if err != nil {
		writeScoreError(w, err)
		return
	}
	writeJSON(w, http.StatusCreated, score)
}

// scoreListResponse follows the repository list convention:
// { "records": [...], "meta": { "total": N } }.
type scoreListResponse struct {
	Records []evaluation.Score `json:"records"`
	Meta    metaResponse       `json:"meta"`
}

func (h *scoresHandler) list(w http.ResponseWriter, r *http.Request) {
	filter, ok := parseScoreListFilter(w, r)
	if !ok {
		return
	}
	records, total, err := h.service.ListScores(r.Context(), r.PathValue("rid"), filter)
	if err != nil {
		writeScoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, scoreListResponse{Records: records, Meta: metaResponse{Total: total}})
}

func (h *scoresHandler) get(w http.ResponseWriter, r *http.Request) {
	score, err := h.service.GetScore(r.Context(), r.PathValue("rid"), r.PathValue("sid"))
	if err != nil {
		writeScoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, score)
}

func (h *scoresHandler) update(w http.ResponseWriter, r *http.Request) {
	body, ok := decodeScoreBody(w, r)
	if !ok {
		return
	}
	// run_id and indicator_id are never modified by a PUT: the route
	// path and the existing record decide them (a body that carries
	// indicator_id has it ignored here).
	score, err := h.service.UpdateScore(r.Context(), r.PathValue("rid"), r.PathValue("sid"), evaluation.ScoreInput{
		ScoreType: body.ScoreType,
		Rater:     body.Rater,
		Target:    body.Target,
		Score:     body.Score,
		Comment:   body.Comment,
		CreatedBy: body.CreatedBy,
	})
	if err != nil {
		writeScoreError(w, err)
		return
	}
	writeJSON(w, http.StatusOK, score)
}

func (h *scoresHandler) delete(w http.ResponseWriter, r *http.Request) {
	if err := h.service.DeleteScore(r.Context(), r.PathValue("rid"), r.PathValue("sid")); err != nil {
		writeScoreError(w, err)
		return
	}
	w.WriteHeader(http.StatusNoContent)
}

// parseScoreListFilter reads the score_type / indicator_id / limit /
// offset query parameters. A non-empty score_type must be one of the
// three allowed values, otherwise 400; limit/offset must be non-negative
// integers (default limit 50), otherwise 400. indicator_id is an exact
// match and carries no existence check (a filter over an unknown
// indicator simply matches nothing).
func parseScoreListFilter(w http.ResponseWriter, r *http.Request) (evaluation.ScoreFilter, bool) {
	query := r.URL.Query()
	filter := evaluation.ScoreFilter{Limit: defaultPageSize}

	if raw := query.Get("score_type"); raw != "" {
		scoreType := evaluation.ScoreType(raw)
		if !scoreType.Valid() {
			writeError(w, http.StatusBadRequest, "invalid score_type")
			return evaluation.ScoreFilter{}, false
		}
		filter.ScoreType = scoreType
	}
	if raw := query.Get("indicator_id"); raw != "" {
		filter.IndicatorID = raw
	}
	if raw := query.Get("limit"); raw != "" {
		limit, err := strconv.Atoi(raw)
		if err != nil || limit < 0 {
			writeError(w, http.StatusBadRequest, "invalid limit")
			return evaluation.ScoreFilter{}, false
		}
		filter.Limit = limit
	}
	if raw := query.Get("offset"); raw != "" {
		offset, err := strconv.Atoi(raw)
		if err != nil || offset < 0 {
			writeError(w, http.StatusBadRequest, "invalid offset")
			return evaluation.ScoreFilter{}, false
		}
		filter.Offset = offset
	}
	return filter, true
}

// writeScoreError maps the evaluation score service errors to JSON error
// responses: validation errors and the expert-score duplicate rejection
// become 400, unknown runs, indicators or scores 404, everything else
// 500.
func writeScoreError(w http.ResponseWriter, err error) {
	var validationError *evaluation.ValidationError
	switch {
	case errors.As(err, &validationError):
		writeError(w, http.StatusBadRequest, validationError.Message)
	case errors.Is(err, drills.ErrRunNotFound),
		errors.Is(err, evaluation.ErrIndicatorNotFound),
		errors.Is(err, evaluation.ErrScoreNotFound):
		writeError(w, http.StatusNotFound, err.Error())
	case errors.Is(err, evaluation.ErrExpertScoreExists):
		writeError(w, http.StatusBadRequest, err.Error())
	default:
		writeError(w, http.StatusInternalServerError, "internal error")
	}
}
