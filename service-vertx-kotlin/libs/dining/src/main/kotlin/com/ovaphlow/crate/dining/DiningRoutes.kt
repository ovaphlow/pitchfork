package com.ovaphlow.crate.dining

import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

/**
 * 膳食营养模块路由（Aceso 长者餐食管理）。
 *
 * 写路由（建档/菜品/菜谱/配餐/登记）由 App 编排层注入认证中间件
 * （idpSessionAuthHandler），业务处理器再从 ctx 读取 userId 作为操作人；
 * 未注入认证中间件时写路由保持 401 兜底（与 healthcare 模块一致）。
 */
object DiningRoutes {

    private val log = LoggerFactory.getLogger(DiningRoutes::class.java)

    fun create(
        vertx: Vertx,
        pool: Pool,
        authHandler: Handler<RoutingContext>? = null,
    ): Router {
        val router = Router.router(vertx)
        val dietProfileService = DietProfileService(pool)
        val dishService = DishService(pool)
        val weeklyMenuService = WeeklyMenuService(pool)
        val rosterService = RosterService(pool)
        val mealExecutionService = MealExecutionService(pool)
        val statisticsService = DiningStatisticsService(pool)
        val mealIntakeService = MealIntakeService(pool)
        val nutritionAnalysisService = NutritionAnalysisService(pool)
        val specialDietRequestService = SpecialDietRequestService(pool)
        val weeklyReportService = NutritionWeeklyReportService(pool)

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "dining"))
        }

        // ========================================================================
        //  长者饮食档案（FR-1）
        // ========================================================================
        registerAuth(router, authHandler,
            "/diet-profiles" to listOf("POST"),
            "/diet-profiles/:id" to listOf("PUT", "PATCH", "DELETE"),
            // 054 A4：过敏原标记覆盖式写入（复用既有 allergies 列，不新建主数据表）
            "/diet-profiles/:id/allergens" to listOf("PUT"),
            // 054 A4b：按 patient 维度 upsert 最小启用档案 + 覆盖式写过敏原（儿保儿童无入住可达）
            "/diet-profiles/by-patient/:patient_id/allergens" to listOf("PUT"),
        )

        router.post("/diet-profiles").handler { ctx ->
            api(ctx, { dietProfileService.create(body(ctx)) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.get("/diet-profiles").handler { ctx ->
            api(ctx, {
                dietProfileService.list(
                    patientId = ctx.request().getParam("patient_id"),
                    encounterId = ctx.request().getParam("encounter_id"),
                    status = ctx.request().getParam("status"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }
        router.get("/diet-profiles/:id").handler { ctx ->
            api(ctx, { dietProfileService.get(requiredId(ctx)) }) { ctx.json(it) }
        }
        router.put("/diet-profiles/:id").handler { ctx ->
            api(ctx, { dietProfileService.update(requiredId(ctx), body(ctx)) }) { ctx.json(it) }
        }
        router.patch("/diet-profiles/:id/status").handler { ctx ->
            api(ctx, { dietProfileService.updateStatus(requiredId(ctx), body(ctx).getString("status") ?: "") }) { ctx.json(it) }
        }
        // 054 A4：过敏原标记（覆盖式写 dining_diet_profiles.allergies）
        router.put("/diet-profiles/:id/allergens").handler { ctx ->
            api(ctx, { dietProfileService.updateAllergens(requiredId(ctx), body(ctx)) }) { ctx.json(it) }
        }
        // 054 A4b：按 patient 维度 upsert 最小启用档案（儿童 encounter_id=NULL）+ 覆盖式写过敏原
        router.put("/diet-profiles/by-patient/:patient_id/allergens").handler { ctx ->
            api(ctx, {
                dietProfileService.upsertAllergensByPatient(
                    ctx.pathParam("patient_id"),
                    body(ctx),
                )
            }) { ctx.json(it) }
        }
        router.delete("/diet-profiles/:id").handler { ctx ->
            api(ctx, { dietProfileService.delete(requiredId(ctx)) }) { ctx.response().setStatusCode(204); ctx.response().end() }
        }

        // ========================================================================
        //  菜品库（FR-2）
        // ========================================================================
        registerAuth(router, authHandler,
            "/dishes" to listOf("POST"),
            "/dishes/:id" to listOf("PUT", "PATCH"),
        )

        router.post("/dishes").handler { ctx ->
            api(ctx, { dishService.create(body(ctx)) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.get("/dishes").handler { ctx ->
            api(ctx, {
                dishService.list(
                    category = ctx.request().getParam("category"),
                    status = ctx.request().getParam("status"),
                    keyword = ctx.request().getParam("keyword"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }
        router.get("/dishes/:id").handler { ctx ->
            api(ctx, { dishService.get(requiredId(ctx)) }) { ctx.json(it) }
        }
        router.put("/dishes/:id").handler { ctx ->
            api(ctx, { dishService.update(requiredId(ctx), body(ctx)) }) { ctx.json(it) }
        }
        router.patch("/dishes/:id/status").handler { ctx ->
            api(ctx, { dishService.updateStatus(requiredId(ctx), body(ctx).getString("status") ?: "") }) { ctx.json(it) }
        }

        // ========================================================================
        //  周菜谱编排（FR-3）
        // ========================================================================
        registerAuth(router, authHandler,
            "/weekly-menus" to listOf("POST"),
            "/weekly-menus/:id" to listOf("PUT", "PATCH", "POST"),
        )

        router.post("/weekly-menus").handler { ctx ->
            api(ctx, { weeklyMenuService.create(body(ctx)) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.get("/weekly-menus").handler { ctx ->
            api(ctx, {
                weeklyMenuService.list(
                    weekStart = ctx.request().getParam("week_start"),
                    status = ctx.request().getParam("status"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }
        // 按日期取当周启用菜谱：静态段必须在 /weekly-menus/:id 之前注册
        router.get("/weekly-menus/by-date").handler { ctx ->
            api(ctx, { weeklyMenuService.getByDate(ctx.request().getParam("date") ?: "") }) { ctx.json(it) }
        }
        router.get("/weekly-menus/:id").handler { ctx ->
            api(ctx, { weeklyMenuService.get(requiredId(ctx)) }) { ctx.json(it) }
        }
        router.put("/weekly-menus/:id").handler { ctx ->
            api(ctx, { weeklyMenuService.update(requiredId(ctx), body(ctx)) }) { ctx.json(it) }
        }
        router.patch("/weekly-menus/:id/status").handler { ctx ->
            api(ctx, { weeklyMenuService.updateStatus(requiredId(ctx), body(ctx).getString("status") ?: "") }) { ctx.json(it) }
        }
        router.post("/weekly-menus/:id/items").handler { ctx ->
            api(ctx, { weeklyMenuService.replaceItems(requiredId(ctx), body(ctx)) }) { ctx.json(it) }
        }
        router.post("/weekly-menus/:id/copy").handler { ctx ->
            api(ctx, { weeklyMenuService.copy(requiredId(ctx), body(ctx).getString("week_start") ?: "") }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }

        // ========================================================================
        //  配餐名单（FR-4）
        // ========================================================================
        registerAuth(router, authHandler,
            "/rosters/generate" to listOf("POST"),
            "/rosters/:id/items" to listOf("POST"),
            "/rosters/:id/items/:itemId" to listOf("DELETE"),
        )

        router.post("/rosters/generate").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            val b = body(ctx)
            api(ctx, {
                rosterService.generate(
                    date = b.getString("date") ?: "",
                    mealTime = b.getString("meal_time") ?: "",
                    remark = b.getString("remark"),
                    userId = userId,
                )
            }) { ctx.json(it) }
        }
        router.get("/rosters").handler { ctx ->
            api(ctx, {
                rosterService.list(
                    date = ctx.request().getParam("date"),
                    mealTime = ctx.request().getParam("meal_time"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }
        router.get("/rosters/:id").handler { ctx ->
            api(ctx, { rosterService.get(requiredId(ctx)) }) { ctx.json(it) }
        }
        router.post("/rosters/:id/items").handler { ctx ->
            api(ctx, { rosterService.addItem(requiredId(ctx), body(ctx)) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.delete("/rosters/:id/items/:itemId").handler { ctx ->
            api(ctx, {
                rosterService.removeItem(
                    requiredId(ctx),
                    ctx.pathParam("itemId")?.takeIf(String::isNotBlank)
                        ?: throw IllegalArgumentException("itemId is required"),
                )
            }) { ctx.response().setStatusCode(204); ctx.response().end() }
        }

        // ========================================================================
        //  就餐执行登记（FR-5）
        // ========================================================================
        registerAuth(router, authHandler, "/executions" to listOf("POST"))

        router.post("/executions").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            val b = body(ctx)
            api(ctx, {
                mealExecutionService.register(
                    rosterItemId = b.getString("roster_item_id") ?: "",
                    status = b.getString("status") ?: "",
                    remark = b.getString("remark"),
                    userId = userId,
                )
            }) { ctx.json(it) }
        }
        router.get("/executions").handler { ctx ->
            api(ctx, {
                mealExecutionService.list(
                    date = ctx.request().getParam("date"),
                    mealTime = ctx.request().getParam("meal_time"),
                    status = ctx.request().getParam("status"),
                    patientId = ctx.request().getParam("patient_id"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }

        // ========================================================================
        //  就餐统计（FR-6）
        // ========================================================================
        router.get("/statistics/meals").handler { ctx ->
            api(ctx, {
                statisticsService.mealStatistics(
                    dateFrom = ctx.request().getParam("date_from") ?: "",
                    dateTo = ctx.request().getParam("date_to") ?: "",
                    mealTime = ctx.request().getParam("meal_time"),
                )
            }) { ctx.json(it) }
        }

        // ========================================================================
        //  每餐膳食摄入记录（054 A1）
        //  本段无 :id 参数段；若后续新增，静态段必须先于参数段注册
        //  （与 /weekly-menus/by-date 同规则）。服务端按「食物项 × 菜品营养 × 摄入比例」
        //  汇总营养素，客户端提交的同名字段一律忽略。
        // ========================================================================
        registerAuth(router, authHandler, "/meal-intake-records" to listOf("POST"))

        router.post("/meal-intake-records").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            api(ctx, { mealIntakeService.register(body(ctx), userId) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.get("/meal-intake-records").handler { ctx ->
            api(ctx, {
                mealIntakeService.list(
                    patientId = ctx.request().getParam("patient_id"),
                    date = ctx.request().getParam("date"),
                    mealTime = ctx.request().getParam("meal_time"),
                    from = ctx.request().getParam("from"),
                    to = ctx.request().getParam("to"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }

        // ========================================================================
        //  营养分析（054 A3，读时聚合，读路由不上门禁）
        //  结论与缺口项一律服务端派生；当天无摄入记录返回零值结论，不 500。
        // ========================================================================
        router.get("/nutrition-analysis").handler { ctx ->
            api(ctx, {
                nutritionAnalysisService.analyze(
                    patientId = ctx.request().getParam("patient_id"),
                    date = ctx.request().getParam("date"),
                    mealTime = ctx.request().getParam("meal_time"),
                )
            }) { ctx.json(it) }
        }

        // ========================================================================
        //  特殊膳食申请（054 A4）
        //  状态机 待审批 → 已批准/已驳回 → 已终止（见 SpecialDietRequestService）；
        //  非法流转由服务层抛 400。过敏原复用既有列，不新建过敏主数据表。
        // ========================================================================
        registerAuth(router, authHandler,
            "/special-diet-requests" to listOf("POST"),
            "/special-diet-requests/:id/status" to listOf("PATCH"),
        )

        router.post("/special-diet-requests").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            api(ctx, { specialDietRequestService.create(body(ctx), userId) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.get("/special-diet-requests").handler { ctx ->
            api(ctx, {
                specialDietRequestService.list(
                    patientId = ctx.request().getParam("patient_id"),
                    status = ctx.request().getParam("status"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }
        router.patch("/special-diet-requests/:id/status").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            api(ctx, {
                specialDietRequestService.updateStatus(
                    requiredId(ctx),
                    body(ctx).getString("status"),
                    userId,
                )
            }) { ctx.json(it) }
        }

        // ========================================================================
        //  营养周报（054 A5）
        //  显式生成 + 快照落库；同儿童同周重复生成覆盖（ON CONFLICT 唯一索引）。
        //  静态段 /nutrition-weekly-reports/generate 先于任何参数段注册。
        // ========================================================================
        registerAuth(router, authHandler, "/nutrition-weekly-reports/generate" to listOf("POST"))

        router.post("/nutrition-weekly-reports/generate").handler { ctx ->
            val userId = userId(ctx) ?: return@handler
            api(ctx, { weeklyReportService.generate(body(ctx), userId) }) { result ->
                ctx.response().setStatusCode(201); ctx.json(result)
            }
        }
        router.get("/nutrition-weekly-reports").handler { ctx ->
            api(ctx, {
                weeklyReportService.list(
                    patientId = ctx.request().getParam("patient_id"),
                    weekStart = ctx.request().getParam("week_start"),
                    limit = limit(ctx),
                    offset = offset(ctx),
                )
            }) { ctx.json(it) }
        }

        return router
    }

    // ========================================================================
    //  认证与公共辅助
    // ========================================================================

    /** 为写路由注册认证中间件（与 healthcare 模式一致，业务处理器另有 401 兜底）。 */
    private fun registerAuth(
        router: Router,
        authHandler: Handler<RoutingContext>?,
        vararg paths: Pair<String, List<String>>,
    ) {
        if (authHandler == null) return
        for ((path, methods) in paths) {
            for (method in methods) {
                router.route(io.vertx.core.http.HttpMethod.valueOf(method), path).handler(authHandler)
            }
        }
    }

    private fun userId(ctx: RoutingContext): String? {
        val value = ctx.get<String>("userId")
        if (value.isNullOrBlank()) {
            respond(ctx, 401, "authentication required")
            return null
        }
        return value
    }

    private fun body(ctx: RoutingContext): JsonObject = ctx.body().asJsonObject() ?: JsonObject()

    private fun requiredId(ctx: RoutingContext): String =
        ctx.pathParam("id")?.takeIf(String::isNotBlank)
            ?: throw IllegalArgumentException("id is required")

    private fun limit(ctx: RoutingContext): Int =
        ctx.request().getParam("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50

    private fun offset(ctx: RoutingContext): Int =
        ctx.request().getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

    /**
     * 统一执行服务调用：同步抛出的参数校验异常（IllegalArgumentException）
     * 也映射为 400，避免 Vert.x 将其当作 500。
     */
    private fun <T> api(ctx: RoutingContext, action: () -> io.vertx.core.Future<T>, success: (T) -> Unit) {
        try {
            action()
                .onSuccess(success)
                .onFailure { respondFailure(ctx, it) }
        } catch (error: IllegalArgumentException) {
            respond(ctx, 400, error.message)
        } catch (error: Throwable) {
            respondError(ctx, error)
        }
    }

    private fun respondFailure(ctx: RoutingContext, error: Throwable) {
        when (error) {
            is IllegalArgumentException -> respond(ctx, 400, error.message)
            is DiningNotFoundException -> respond(ctx, 404, error.message)
            is DiningConflictException -> respond(ctx, 409, error.message)
            else -> respondError(ctx, error)
        }
    }

    private fun respondError(ctx: RoutingContext, error: Throwable) {
        log.error("dining route error", error)
        respond(ctx, 500, "internal error")
    }

    private fun respond(ctx: RoutingContext, status: Int, message: String?) {
        if (ctx.response().ended()) return
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }
}
