// Package httpapi provides the HTTP routing layer of prototyped: the
// unified API route prefix, a configurable CORS middleware and JSON error
// responses following the repository convention { "error": "<message>" }.
package httpapi

import (
	"encoding/json"
	"net/http"
	"strings"

	"github.com/ovaphlow/pitchfork/service-prototype/internal/assignments"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/chapters"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/courses"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/dispatch"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/drills"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/evaluation"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/examrecords"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/opinion"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/papers"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/progress"
	"github.com/ovaphlow/pitchfork/service-prototype/internal/questions"
	"github.com/ovaphlow/pitchfork/service-prototype/web"
)

// prototypePrefix is the unified API prefix. It follows the repository
// route convention /crate-api/<module>/v1/<resource> with the module fixed
// to "prototype" for this service.
const prototypePrefix = "/crate-api/prototype/v1"

type healthResponse struct {
	Status string `json:"status"`
}

// NewMux builds the route mux, applies the CORS middleware with the given
// allow list, and serves healthz, the training courses, the course
// chapters, the question bank, the training task assignments, the
// learning-progress routes, the exam papers, the online exam records,
// the drill scenario templates and the drill runs through the unified
// resource routes. The
// literal paths take precedence over the {resource} wildcard for the same
// prefix. The course, chapter, question, assignment, progress, paper,
// exam-record, drill, dispatch, opinion and evaluation stores are
// injected so the routing layer stays free of database access; the
// chapter store is also
// wired into the course service so deleting a course cascades to its
// chapters, the question store backs automatic paper generation (the
// papers service's question source), the paper store backs the exam-start
// existence check and snapshot (the exam-records service's paper lookup),
// and the dispatch store backs the command-session handler and the drills
// service's run-session cleaner (deleting a run cascades to its
// sessions); the opinion store backs the opinion-event handler and the
// drills service's run-opinion cleaner (deleting a run cascades to its
// opinion event).
// Routes:
//
//	GET/POST /crate-api/prototype/v1/courses      -> list / create courses
//	GET/PUT/DELETE /crate-api/prototype/v1/courses/{id} -> course by id
//	GET/POST /crate-api/prototype/v1/courses/{courseId}/chapters -> list / create chapters
//	GET/PUT/DELETE /crate-api/prototype/v1/chapters/{id} -> chapter by id
//	GET/POST /crate-api/prototype/v1/questions    -> list / create questions
//	POST /crate-api/prototype/v1/questions/import -> batch import questions
//	GET/PUT/DELETE /crate-api/prototype/v1/questions/{id} -> question by id
//	GET/POST /crate-api/prototype/v1/assignments  -> list / create assignments
//	DELETE /crate-api/prototype/v1/assignments/{id} -> assignment by id
//	GET  /crate-api/prototype/v1/assignments/{aid}/employees/{eid}/progress -> progress summary
//	PUT  /crate-api/prototype/v1/assignments/{aid}/employees/{eid}/progress/chapters/{cid} -> report chapter progress
//	POST /crate-api/prototype/v1/assignments/{aid}/employees/{eid}/complete -> complete every chapter
//	GET/POST /crate-api/prototype/v1/papers       -> list / create papers
//	GET/PUT/DELETE /crate-api/prototype/v1/papers/{id} -> paper by id
//	POST /crate-api/prototype/v1/papers/{id}/generate -> generate paper questions
//	GET/POST /crate-api/prototype/v1/exam-records -> list / open exam records
//	GET /crate-api/prototype/v1/exam-records/{id} -> exam record by id
//	POST /crate-api/prototype/v1/exam-records/{id}/submit -> submit and grade an exam
//	GET/POST /crate-api/prototype/v1/scenarios    -> list / create drill scenario templates
//	GET/PUT/DELETE /crate-api/prototype/v1/scenarios/{id} -> scenario by id
//	GET/POST /crate-api/prototype/v1/scenarios/{sid}/steps -> list / create scenario steps
//	GET/PUT/DELETE /crate-api/prototype/v1/steps/{id} -> step by id
//	GET/POST /crate-api/prototype/v1/scenarios/{sid}/assessment-points -> list / create assessment points
//	GET/PUT/DELETE /crate-api/prototype/v1/assessment-points/{id} -> assessment point by id
//	GET/POST /crate-api/prototype/v1/drills -> list / create drill runs
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{id} -> drill run by id
//	POST /crate-api/prototype/v1/drills/{id}/start|complete|terminate -> run state machine
//	GET  /crate-api/prototype/v1/drills/{rid}/steps -> list step execution records
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/steps/{stepId} -> step record by step
//	GET/POST /crate-api/prototype/v1/drills/{rid}/sim-events -> list / create simulated events
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/sim-events/{eid} -> simulated event by id
//	GET  /crate-api/prototype/v1/drills/{rid}/assessments -> list drill assessments
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/assessments/{pointId} -> assessment by point
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/command-session -> dispatch command session by run
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/opinion-event -> opinion event by run
//	GET/POST /crate-api/prototype/v1/drills/{rid}/posts -> list / create opinion posts
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/posts/{pid} -> opinion post by id
//	GET/POST /crate-api/prototype/v1/drills/{rid}/releases -> list / create opinion releases
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/releases/{lid} -> opinion release by id
//	GET/POST /crate-api/prototype/v1/drills/{rid}/media-questions -> list / create opinion media questions
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/media-questions/{mqid} -> opinion media question by id
//	GET/POST /crate-api/prototype/v1/drills/{rid}/complaints -> list / create opinion complaints
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/complaints/{cid} -> opinion complaint by id
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/review -> opinion review by run
//	GET/POST /crate-api/prototype/v1/drills/{rid}/orders -> list / create dispatch orders
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/orders/{oid} -> dispatch order by id
//	GET  /crate-api/prototype/v1/drills/{rid}/departments -> list dispatch department reports
//	PUT/DELETE /crate-api/prototype/v1/drills/{rid}/departments/{department} -> department report by department
//	GET/POST /crate-api/prototype/v1/drills/{rid}/messages -> list / send dispatch messages
//	GET/DELETE /crate-api/prototype/v1/drills/{rid}/messages/{mid} -> dispatch message by id
//	GET/POST /crate-api/prototype/v1/drills/{rid}/zone-densities -> list / report zone crowd densities
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/zone-densities/{zid} -> zone density report by id
//	GET/POST /crate-api/prototype/v1/drills/{rid}/devices -> list / report device running status
//	GET/PUT/DELETE /crate-api/prototype/v1/drills/{rid}/devices/{did} -> device report by id
//	GET/POST /crate-api/prototype/v1/evaluation/indicators -> list / create evaluation indicators
//	GET/PUT/DELETE /crate-api/prototype/v1/evaluation/indicators/{id} -> indicator by id
//	GET/POST /crate-api/prototype/v1/evaluation/runs/{rid}/scores -> list / create evaluation scores
//	GET/PUT/DELETE /crate-api/prototype/v1/evaluation/runs/{rid}/scores/{sid} -> evaluation score by id
//	POST /crate-api/prototype/v1/evaluation/runs/{rid}/reports/generate -> generate / regenerate the run report
//	GET  /crate-api/prototype/v1/evaluation/runs/{rid}/report -> the report of the run
//	GET  /crate-api/prototype/v1/evaluation/reports -> list evaluation reports (run_id filter, pagination)
//	GET  /crate-api/prototype/v1/healthz          -> JSON health
//	GET  /crate-api/prototype/v1/{resource}       -> 404 JSON for unknown resources
//	GET  /demo                -> server-rendered demo page
//	GET  /demo/scenarios      -> server-rendered drill scenario template management page
//	GET  /demo/drills         -> server-rendered drill execution and assessment page
//	GET  /demo/command        -> server-rendered command-center big screen page
//	GET  /demo/console        -> server-rendered command console and field terminal page
//	GET  /demo/opinion        -> server-rendered public-opinion monitoring and handling workbench page
//	GET  /demo/opinion/review  -> server-rendered media communication and after-action review page
//	GET  /demo/evaluation/indicators -> server-rendered evaluation indicator configuration page
//	GET  /demo/evaluation/reports -> server-rendered comprehensive evaluation and report page
//	GET  /static/{file}       -> embedded static asset (htmx)
//	any  other path                               -> 404 JSON
//	any  non-GET on a known resource path         -> 405 JSON with Allow
func NewMux(allowedOrigins []string, courseStore courses.Store, chapterStore chapters.Store, questionStore questions.Store, assignmentStore assignments.Store, progressStore progress.Store, paperStore papers.Store, examRecordStore examrecords.Store, drillStore drills.Store, dispatchStore dispatch.Store, opinionStore opinion.Store, evaluationStore evaluation.Store) http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc(prototypePrefix+"/{resource}", handleResource)
	courseHandler := newCoursesHandler(courseStore)
	mux.HandleFunc(coursesBase, courseHandler.handleCollection)
	mux.HandleFunc(coursesBase+"/{id}", courseHandler.handleItem)
	chapterHandler := newChaptersHandler(chapterStore, courseStore)
	mux.HandleFunc(coursesBase+"/{courseId}/chapters", chapterHandler.handleCourseChapters)
	mux.HandleFunc(chaptersBase+"/{id}", chapterHandler.handleItem)
	// Deleting a course cascades to its chapters at the service layer
	// (the in-memory chapter store implements the DB's ON DELETE
	// CASCADE).
	courseHandler.service.SetChapterCleaner(chapterStore)
	questionHandler := newQuestionsHandler(questionStore)
	mux.HandleFunc(questionsBase, questionHandler.handleCollection)
	mux.HandleFunc(questionsBase+"/{id}", questionHandler.handleItem)
	mux.HandleFunc("POST "+questionsBase+"/import", questionHandler.handleImport)
	assignmentHandler := newAssignmentsHandler(assignmentStore, courseStore)
	mux.HandleFunc(assignmentsBase, assignmentHandler.handleCollection)
	mux.HandleFunc(assignmentsBase+"/{id}", assignmentHandler.handleItem)
	// The learning-progress routes nest under the assignments prefix with
	// literal segments (employees/…), so they are more specific than the
	// /assignments/{id} item route and never collide with it. The progress
	// store is injected like the others; the assignment/chapter/course
	// stores back the existence and ownership checks behind the routes.
	progressHandler := newProgressHandler(progressStore, assignmentStore, chapterStore, courseStore)
	mux.HandleFunc("GET "+assignmentsBase+"/{aid}/employees/{eid}/progress", progressHandler.handleSummary)
	// The question-bank store backs automatic paper generation through
	// the papers package's QuestionSource adapter.
	paperHandler := newPapersHandler(paperStore, papers.NewQuestionSource(questionStore))
	mux.HandleFunc(papersBase, paperHandler.handleCollection)
	mux.HandleFunc(papersBase+"/{id}", paperHandler.handleItem)
	mux.HandleFunc("POST "+papersBase+"/{id}/generate", paperHandler.handleGenerate)
	// The paper store backs the exam-start existence check and snapshot
	// through the exam-records service's paper lookup.
	examRecordHandler := newExamRecordsHandler(examRecordStore, paperStore)
	mux.HandleFunc(examRecordsBase, examRecordHandler.handleCollection)
	mux.HandleFunc(examRecordsBase+"/{id}", examRecordHandler.handleItem)
	mux.HandleFunc("POST "+examRecordsBase+"/{id}/submit", examRecordHandler.handleSubmit)
	mux.HandleFunc("PUT "+assignmentsBase+"/{aid}/employees/{eid}/progress/chapters/{cid}", progressHandler.handleUpsert)
	mux.HandleFunc("POST "+assignmentsBase+"/{aid}/employees/{eid}/complete", progressHandler.handleComplete)
	scenarioHandler := newScenariosHandler(drillStore)
	mux.HandleFunc(scenariosBase, scenarioHandler.handleCollection)
	mux.HandleFunc(scenariosBase+"/{id}", scenarioHandler.handleItem)
	// Deleting a scenario cascades to its steps and assessment points at
	// the service layer (the in-memory drill store implements the DB's
	// ON DELETE CASCADE).
	scenarioHandler.service.SetScenarioChildCleaner(drillStore)
	stepHandler := newStepsHandler(drillStore)
	mux.HandleFunc(scenariosBase+"/{sid}/steps", stepHandler.handleScenarioSteps)
	mux.HandleFunc(stepsBase+"/{id}", stepHandler.handleItem)
	pointHandler := newAssessmentPointsHandler(drillStore)
	mux.HandleFunc(scenariosBase+"/{sid}/assessment-points", pointHandler.handleScenarioPoints)
	mux.HandleFunc(assessmentPointsBase+"/{id}", pointHandler.handleItem)
	runHandler := newRunsHandler(drillStore)
	mux.HandleFunc(runsBase, runHandler.handleCollection)
	mux.HandleFunc(runsBase+"/{id}", runHandler.handleItem)
	// The literal transition paths are more specific than the {id} item
	// route, so POST /drills/{id}/start|complete|terminate never collides
	// with it (same pattern as the paper generate and exam submit routes).
	mux.HandleFunc("POST "+runsBase+"/{id}/start", runHandler.handleStart)
	mux.HandleFunc("POST "+runsBase+"/{id}/complete", runHandler.handleComplete)
	mux.HandleFunc("POST "+runsBase+"/{id}/terminate", runHandler.handleTerminate)
	// Deleting a run cascades to its step records, sim events and
	// assessments at the service layer (the in-memory drill store
	// implements the DB's ON DELETE CASCADE).
	runHandler.service.SetRunChildCleaner(drillStore)
	// The dispatch command sessions cascade the same way: the in-memory
	// dispatch store implements the DB's ON DELETE CASCADE through the
	// drills service's run-session cleaner hook (wired here so the run
	// handler and the command-session handler share one store).
	runHandler.service.SetRunSessionCleaner(dispatchStore)
	// The evaluation score store backs the drills service's
	// evaluation-score cleaner hook (deleting a run cascades to its
	// evaluation scores, the in-memory counterpart of the DB's ON
	// DELETE CASCADE). The same store is shared by the evaluation
	// score routes and the indicator service's score-ref checker
	// further below.
	evaluationScoreStore := evaluation.NewInMemoryScoreStore()
	runHandler.service.SetEvaluationScoreCleaner(evaluationScoreStore)
	// The step-record routes nest under the runs prefix with literal
	// segments (…/steps…), so they are more specific than the
	// /drills/{id} item route and never collide with it (same pattern as
	// the progress chapter routes under the assignments prefix). The
	// record of a (run, step) pair is upserted with PUT and never created
	// via POST, so the collection only serves GET.
	stepRecordHandler := newStepRecordHandler(drillStore)
	mux.HandleFunc(stepRecordsBase, stepRecordHandler.handleCollection)
	mux.HandleFunc(stepRecordsBase+"/{stepId}", stepRecordHandler.handleItem)
	// The sim-event routes nest under the runs prefix with the literal
	// sim-events segment (…/sim-events…), so they are more specific than
	// the /drills/{id} item route and never collide with it (same pattern
	// as the step-record routes above).
	simEventHandler := newSimEventsHandler(drillStore)
	mux.HandleFunc(simEventsBase, simEventHandler.handleCollection)
	mux.HandleFunc(simEventsBase+"/{eid}", simEventHandler.handleItem)
	// The assessment routes nest under the runs prefix with the literal
	// assessments segment (…/assessments…), so they are more specific
	// than the /drills/{id} item route and never collide with it (same
	// pattern as the step-record and sim-event routes above). The
	// assessment of a (run, point) pair is upserted with PUT and never
	// created via POST, so the collection only serves GET.
	assessmentHandler := newAssessmentHandler(drillStore)
	mux.HandleFunc(assessmentsBase, assessmentHandler.handleCollection)
	mux.HandleFunc(assessmentsBase+"/{pointId}", assessmentHandler.handleItem)
	// The dispatch command-session route nests under the runs prefix
	// with the literal command-session segment (…/command-session), so
	// it is more specific than the /drills/{id} item route and never
	// collides with it (same pattern as the step-record, sim-event and
	// assessment routes above). The session of a run is upserted with
	// PUT and never created via POST; there is no collection route (the
	// resource is a single object per run).
	commandSessionHandler := newCommandSessionHandler(drillStore, dispatchStore)
	mux.HandleFunc(commandSessionBase, commandSessionHandler.handleItem)
	// The opinion event route nests under the runs prefix with the
	// literal opinion-event segment (…/opinion-event), so it is more
	// specific than the /drills/{id} item route and never collides with
	// it (same pattern as the step-record, sim-event, assessment and
	// command-session routes above). The opinion event of a run is
	// upserted with PUT and never created via POST; there is no
	// collection route (the resource is a single object per run). The
	// opinion event configuration cascades like the dispatch children:
	// the in-memory opinion store implements the DB's ON DELETE CASCADE
	// through the drills service's run-opinion cleaner hook (wired here
	// so the run handler and the opinion-event handler share one
	// store).
	opinionEventHandler := newOpinionEventHandler(drillStore, opinionStore)
	mux.HandleFunc(opinionEventBase, opinionEventHandler.handleItem)
	runHandler.service.SetOpinionCleaner(opinionStore)
	// The opinion post routes nest under the runs prefix with the
	// literal posts segment (…/posts…), so they are more specific than
	// the /drills/{id} item route and never collide with it (same
	// pattern as the sim-event routes above). The opinion posts of a
	// run are the simulated public-opinion feed (舆情信息流): they are
	// created with POST and listed with GET at the collection, fetched
	// / updated / removed by id at the item route; writes require the
	// run to be 进行中 (400 otherwise) and a missing run is a 404 on
	// every route. The posts cascade like the opinion event: the shared
	// in-memory opinion store implements the DB's ON DELETE CASCADE
	// through the drills service's run-opinion cleaner hook wired
	// above, so deleting a run removes its posts as well.
	opinionPostHandler := newOpinionPostHandler(drillStore, opinionStore)
	mux.HandleFunc(opinionPostsBase, opinionPostHandler.handleCollection)
	mux.HandleFunc(opinionPostsBase+"/{pid}", opinionPostHandler.handleItem)
	// The opinion release routes nest under the runs prefix with the
	// literal releases segment (…/releases…), so they are more specific
	// than the /drills/{id} item route and never collide with it (same
	// pattern as the sim-event, posts and orders routes above). The
	// releases of a run are the situation-statement publication records
	// (情况说明发布记录): they are created with POST and listed with GET
	// at the collection, fetched / updated / removed by id at the item
	// route; writes require the run to be 进行中 (400 otherwise) and a
	// missing run is a 404 on every route. The releases cascade like the
	// opinion posts: the shared in-memory opinion store implements the
	// DB's ON DELETE CASCADE through the drills service's run-opinion
	// cleaner hook wired above, so deleting a run removes its releases
	// as well.
	opinionReleaseHandler := newOpinionReleaseHandler(drillStore, opinionStore)
	mux.HandleFunc(opinionReleasesBase, opinionReleaseHandler.handleCollection)
	mux.HandleFunc(opinionReleasesBase+"/{lid}", opinionReleaseHandler.handleItem)
	// The opinion media-question routes nest under the runs prefix with
	// the literal media-questions segment (…/media-questions…), so they
	// are more specific than the /drills/{id} item route and never
	// collide with it (same pattern as the sim-event, posts and releases
	// routes above). The media questions of a run are the simulated
	// press-conference Q&A records (媒体问答记录): they are created with
	// POST and listed with GET at the collection (in question order,
	// created_at ASC, id ASC), fetched / updated / removed by id at the
	// item route; writes require the run to be 进行中 (400 otherwise) and
	// a missing run is a 404 on every route. The media questions cascade
	// like the other opinion objects: the shared in-memory opinion store
	// implements the DB's ON DELETE CASCADE through the drills service's
	// run-opinion cleaner hook wired above, so deleting a run removes
	// its media questions as well.
	opinionMediaQuestionHandler := newOpinionMediaQuestionHandler(drillStore, opinionStore)
	mux.HandleFunc(opinionMediaQuestionsBase, opinionMediaQuestionHandler.handleCollection)
	mux.HandleFunc(opinionMediaQuestionsBase+"/{mqid}", opinionMediaQuestionHandler.handleItem)
	// The opinion complaint routes nest under the runs prefix with the
	// literal complaints segment (…/complaints…), so they are more
	// specific than the /drills/{id} item route and never collide with
	// it (same pattern as the sim-event, posts, releases and
	// media-question routes above). The complaints of a run are the
	// visitor complaint tickets (观众投诉工单) of the 「投诉处理」 training
	// phase: they are created with POST and listed with GET at the
	// collection (in intake order, created_at ASC, id ASC), fetched /
	// updated / removed by id at the item route; writes require the run
	// to be 进行中 (400 otherwise) and a missing run is a 404 on every
	// route. The complaints cascade like the other opinion objects: the
	// shared in-memory opinion store implements the DB's ON DELETE
	// CASCADE through the drills service's run-opinion cleaner hook
	// wired above, so deleting a run removes its complaints as well.
	opinionComplaintHandler := newOpinionComplaintHandler(drillStore, opinionStore)
	mux.HandleFunc(opinionComplaintsBase, opinionComplaintHandler.handleCollection)
	mux.HandleFunc(opinionComplaintsBase+"/{cid}", opinionComplaintHandler.handleItem)
	// The opinion review route nests under the runs prefix with the
	// literal review segment (…/review), so it is more specific than
	// the /drills/{id} item route and never collides with it (same
	// pattern as the step-record, sim-event, assessment,
	// command-session and opinion-event routes above). The review of a
	// run is the after-action review report (舆情复盘记录) of the
	// 「舆情复盘」 phase, upserted with PUT and never created via POST;
	// there is no collection route (the resource is a single object per
	// run). Writes require the run to be 进行中/已完成 (400 otherwise)
	// and a missing run is a 404 on every route. The reviews cascade
	// like the other opinion objects: the shared in-memory opinion
	// store implements the DB's ON DELETE CASCADE through the drills
	// service's run-opinion cleaner hook wired above, so deleting a run
	// removes its review as well.
	opinionReviewHandler := newOpinionReviewHandler(drillStore, opinionStore)
	mux.HandleFunc(opinionReviewBase, opinionReviewHandler.handleItem)
	// The dispatch orders routes nest under the runs prefix with the
	// literal orders segment (…/orders…), so they are more specific than
	// the /drills/{id} item route and never collide with it (same pattern
	// as the step-record, sim-event, assessment and command-session routes
	// above). Orders are issued with POST and updated in place with PUT.
	orderHandler := newOrdersHandler(drillStore, dispatchStore)
	mux.HandleFunc(ordersBase, orderHandler.handleCollection)
	mux.HandleFunc(ordersBase+"/{oid}", orderHandler.handleItem)
	// The dispatch department-report routes nest under the runs prefix
	// with the literal departments segment (…/departments…), so they are
	// more specific than the /drills/{id} item route and never collide
	// with it (same pattern as the step-record, sim-event, assessment,
	// command-session and orders routes above). The report of a
	// (run, department) pair is upserted with PUT and removed with
	// DELETE, so the collection only serves GET.
	departmentHandler := newDepartmentsHandler(drillStore, dispatchStore)
	mux.HandleFunc(departmentsBase, departmentHandler.handleCollection)
	mux.HandleFunc(departmentsBase+"/{department}", departmentHandler.handleItem)
	// The dispatch message routes nest under the runs prefix with the
	// literal messages segment (…/messages…), so they are more specific
	// than the /drills/{id} item route and never collide with it (same
	// pattern as the step-record, sim-event, assessment, command-session,
	// orders and departments routes above). Messages are sent with POST
	// and removed with DELETE; they are immutable, so there is no PUT.
	messageHandler := newMessagesHandler(drillStore, dispatchStore)
	mux.HandleFunc(messagesBase, messageHandler.handleCollection)
	mux.HandleFunc(messagesBase+"/{mid}", messageHandler.handleItem)
	// The dispatch zone-density routes nest under the runs prefix with
	// the literal zone-densities segment (…/zone-densities…), so they are
	// more specific than the /drills/{id} item route and never collide
	// with it (same pattern as the step-record, sim-event, assessment,
	// command-session, orders, departments and messages routes above).
	// Reports are recorded with POST, updated in place with PUT (the
	// reported_at is refreshed) and removed with DELETE.
	zoneHandler := newZonesHandler(drillStore, dispatchStore)
	mux.HandleFunc(zonesBase, zoneHandler.handleCollection)
	mux.HandleFunc(zonesBase+"/{zid}", zoneHandler.handleItem)
	// The dispatch device routes nest under the runs prefix with the
	// literal devices segment (…/devices…), so they are more specific
	// than the /drills/{id} item route and never collide with it (same
	// pattern as the step-record, sim-event, assessment,
	// command-session, orders, departments, messages and zone-density
	// routes above). Devices report their running status with POST,
	// are updated in place with PUT and removed with DELETE.
	deviceHandler := newDevicesHandler(drillStore, dispatchStore)
	mux.HandleFunc(devicesBase, deviceHandler.handleCollection)
	mux.HandleFunc(devicesBase+"/{did}", deviceHandler.handleItem)
	// The evaluation indicator dictionary routes live under the literal
	// evaluation/indicators segment, so they are more specific than the
	// unified /{resource} wildcard and never collide with it. The
	// score-ref checker of the evaluation service (rejecting the
	// deletion of indicators referenced by evaluation scores) is wired
	// to the evaluation score store, the real reference source: deleting
	// an indicator that still has score records answers 400 with the
	// pinned message (指标已被评分引用，请先清理评分).
	indicatorHandler := newIndicatorsHandler(evaluationStore)
	mux.HandleFunc(indicatorsBase, indicatorHandler.handleCollection)
	mux.HandleFunc(indicatorsBase+"/{id}", indicatorHandler.handleItem)
	indicatorHandler.service.SetScoreRefChecker(evaluationScoreStore)
	// The evaluation score routes live under the literal
	// evaluation/runs/{rid}/scores segment, so they are more specific
	// than the unified /{resource} wildcard and never collide with it
	// (same pattern as the indicators routes above). The score records
	// of one drill run are listed and created at the collection route
	// and fetched / updated / deleted by id at the item route; the drill
	// store backs the run existence check and the evaluation indicator
	// store backs the indicator existence check.
	scoreHandler := newScoresHandler(evaluationStore, drillStore, evaluationScoreStore)
	mux.HandleFunc(scoresBase, scoreHandler.handleCollection)
	mux.HandleFunc(scoresBase+"/{sid}", scoreHandler.handleItem)
	// The evaluation report routes live under the literal
	// evaluation/runs/{rid}/report(s) segments, so they are more
	// specific than the unified /{resource} wildcard and never collide
	// with it (same pattern as the scores routes above). The report
	// engine reads the drill/dispatch data through the injected
	// drillsReportSource adapter and the evaluation stores; the report
	// store backs the drills service's run-report cleaner hook
	// (deleting a run cascades to its report, the in-memory counterpart
	// of the DB's ON DELETE CASCADE).
	evaluationReportStore := evaluation.NewInMemoryReportStore()
	runHandler.service.SetEvaluationReportCleaner(evaluationReportStore)
	reportHandler := newReportsHandler(
		evaluationReportStore,
		evaluationStore,
		evaluationScoreStore,
		drillsReportSource{drillStore: drillStore, dispatchStore: dispatchStore},
	)
	mux.HandleFunc("POST "+reportsGenerateBase, reportHandler.handleGenerate)
	mux.HandleFunc("GET "+reportBase, reportHandler.handleGetByRun)
	mux.HandleFunc(reportsBase, reportHandler.handleCollection)
	mux.HandleFunc("GET /demo", handleDemoPage)
	// The drill scenario template management page renders from the
	// in-memory seed data (drills.SeedData) — no database, no API call.
	mux.HandleFunc("GET "+scenariosPagePath, handleScenariosPage)
	// The drill execution and assessment page renders from the in-memory
	// example-runs fixture and drills.SeedData — no database, no API call.
	mux.HandleFunc("GET "+drillsPagePath, handleDrillsPage)
	// The command-center big screen page renders from the built-in
	// in-memory demo data — no database, no API call.
	mux.HandleFunc(commandPagePath, handleCommandPage)
	// The command console and field terminal page renders from the
	// built-in in-memory demo data — no database, no API call.
	mux.HandleFunc(consolePagePath, handleConsolePage)
	// The public-opinion monitoring and handling workbench page renders
	// from the built-in in-memory demo data — no database, no API call.
	mux.HandleFunc(opinionPagePath, handleOpinionPage)
	// The media communication and after-action review page renders from
	// the built-in in-memory demo data — no database, no API call.
	mux.HandleFunc(opinionReviewPagePath, handleOpinionReviewPage)
	// The evaluation indicator configuration page renders from the
	// in-memory seed data (evaluation.SeedData) — no database, no API
	// call.
	mux.HandleFunc(indicatorsPagePath, handleIndicatorsPage)
	// The comprehensive evaluation and report page renders from the
	// in-memory fixture (the evaluation seed dictionary with fixed
	// ULIDs, the example completed drill run with its demo scores and
	// score records, and the report snapshot produced by the
	// evaluation report service) — no database, no API call.
	mux.HandleFunc("GET "+reportsPagePath, handleReportsPage)
	mux.HandleFunc("GET /static/{file}", handleStaticAsset)
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, "not found")
	})
	return corsMiddleware(mux, allowedOrigins)
}

// handleDemoPage renders the server-rendered demo page.
func handleDemoPage(w http.ResponseWriter, r *http.Request) {
	greeting := "你好，prototyped"
	if r.URL.Query().Get("name") != "" {
		greeting = "你好，" + r.URL.Query().Get("name")
	}
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	if err := web.RenderDemo(w, greeting); err != nil {
		writeError(w, http.StatusInternalServerError, "render page failed")
	}
}

// handleStaticAsset serves the embedded static files (e.g. htmx.min.js).
func handleStaticAsset(w http.ResponseWriter, r *http.Request) {
	name := r.PathValue("file")
	if name == "" {
		writeError(w, http.StatusNotFound, "asset not found")
		return
	}
	data, err := web.StaticFiles.ReadFile("static/" + name)
	if err != nil {
		writeError(w, http.StatusNotFound, "asset not found")
		return
	}
	contentType := "application/octet-stream"
	if strings.HasSuffix(name, ".js") {
		contentType = "text/javascript"
	} else if strings.HasSuffix(name, ".css") {
		contentType = "text/css"
	}
	w.Header().Set("Content-Type", contentType)
	w.WriteHeader(http.StatusOK)
	_, _ = w.Write(data)
}

// handleResource serves resources under the unified prefix. GET healthz
// returns the JSON health payload; other resources are not implemented yet
// and yield a JSON 404 (later cards register further resources).
func handleResource(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", "GET")
		writeError(w, http.StatusMethodNotAllowed, "method not allowed")
		return
	}
	switch r.PathValue("resource") {
	case "healthz":
		writeJSON(w, http.StatusOK, healthResponse{Status: "ok"})
	case "demo-fragment":
		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		if err := web.RenderDemoFragment(w, "这是来自 htmx 片段的问候"); err != nil {
			writeError(w, http.StatusInternalServerError, "render fragment failed")
		}
	default:
		writeError(w, http.StatusNotFound, "resource not found")
	}
}

// corsMiddleware wraps the mux with CORS handling. Requests carrying an
// Origin that is in the allow list get Access-Control-Allow-Origin; allowed
// OPTIONS preflights short-circuit with 204 and the CORS headers. Requests
// with a disallowed origin, or without an Origin, are passed through
// untouched (a no-Origin OPTIONS falls back to the mux, which yields the
// normal 405 with Allow).
func corsMiddleware(next http.Handler, allowedOrigins []string) http.Handler {
	allowed := make(map[string]bool, len(allowedOrigins))
	for _, origin := range allowedOrigins {
		allowed[origin] = true
	}
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := strings.TrimSpace(r.Header.Get("Origin"))
		if origin == "" || !allowed[origin] {
			next.ServeHTTP(w, r)
			return
		}
		header := w.Header()
		header.Set("Access-Control-Allow-Origin", origin)
		header.Add("Vary", "Origin")
		if r.Method == http.MethodOptions {
			// The courses slice adds write methods, so preflights must
			// advertise them; otherwise browsers would block the writes.
			header.Set("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
			header.Set("Access-Control-Allow-Headers", "Content-Type, Authorization")
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
