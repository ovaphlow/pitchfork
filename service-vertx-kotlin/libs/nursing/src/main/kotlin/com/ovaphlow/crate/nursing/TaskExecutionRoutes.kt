package com.ovaphlow.crate.nursing

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import java.math.BigDecimal
import java.time.LocalDate

object TaskExecutionRoutes {
    private val TERMINAL_STATUSES = setOf("COMPLETED", "SKIPPED", "CANCELLED")

    fun parseOverdueParam(value: String?): Boolean? = when {
        value == null -> null
        value == "true" -> true
        value == "false" -> false
        else -> throw IllegalArgumentException("overdue must be true or false")
    }

    fun isOverdueStatusAllowed(overdue: Boolean?, status: String?): Boolean =
        overdue != true || status !in TERMINAL_STATUSES

    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        val service = TaskExecutionService(pool)

        router.route().handler(BodyHandler.create())

        // ——— 今日待办（必须在 /:id 之前注册） ———
        router.get("/today").handler { ctx ->
            val params = ctx.request()
            val dateStr = params.getParam("date")
            val date = try {
                if (dateStr != null) LocalDate.parse(dateStr) else LocalDate.now()
            } catch (_: Exception) {
                NursingRoutes.respond(ctx, 400, "invalid date format, expected YYYY-MM-DD")
                return@handler
            }

            val overdue = try {
                parseOverdueParam(params.getParam("overdue"))
            } catch (error: IllegalArgumentException) {
                NursingRoutes.respond(ctx, 400, error.message)
                return@handler
            }

            val status = params.getParam("status")
            if (!isOverdueStatusAllowed(overdue, status)) {
                NursingRoutes.respond(ctx, 400, "overdue cannot be combined with terminal status")
                return@handler
            }

            service.todayExecutions(
                date = date,
                periodId = params.getParam("period_id"),
                executor = params.getParam("executor"),
                status = status,
                overdue = overdue,
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }
        // 容错尾部斜杠
        router.get("/today/").handler { ctx ->
            val params = ctx.request()
            val dateStr = params.getParam("date")
            val date = try {
                if (dateStr != null) LocalDate.parse(dateStr) else LocalDate.now()
            } catch (_: Exception) {
                NursingRoutes.respond(ctx, 400, "invalid date format, expected YYYY-MM-DD")
                return@handler
            }

            val overdue = try {
                parseOverdueParam(params.getParam("overdue"))
            } catch (error: IllegalArgumentException) {
                NursingRoutes.respond(ctx, 400, error.message)
                return@handler
            }

            val status = params.getParam("status")
            if (!isOverdueStatusAllowed(overdue, status)) {
                NursingRoutes.respond(ctx, 400, "overdue cannot be combined with terminal status")
                return@handler
            }

            service.todayExecutions(
                date = date,
                periodId = params.getParam("period_id"),
                executor = params.getParam("executor"),
                status = status,
                overdue = overdue,
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }

        // ——— 执行统计（护理员工作量与计划完成率） ———
        fun handleStatistics(ctx: io.vertx.ext.web.RoutingContext) {
            val params = ctx.request()
            val dateFromStr = params.getParam("date_from")
            val dateToStr = params.getParam("date_to")

            if (dateFromStr.isNullOrBlank()) {
                NursingRoutes.respond(ctx, 400, "date_from is required")
                return
            }
            if (dateToStr.isNullOrBlank()) {
                NursingRoutes.respond(ctx, 400, "date_to is required")
                return
            }

            val dateFrom = try { LocalDate.parse(dateFromStr) } catch (_: Exception) {
                NursingRoutes.respond(ctx, 400, "invalid date format, expected YYYY-MM-DD")
                return
            }
            val dateTo = try { LocalDate.parse(dateToStr) } catch (_: Exception) {
                NursingRoutes.respond(ctx, 400, "invalid date format, expected YYYY-MM-DD")
                return
            }

            if (dateFrom.isAfter(dateTo)) {
                NursingRoutes.respond(ctx, 400, "date_from must not be after date_to")
                return
            }

            val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(dateFrom, dateTo)
            // 闭区间最多 31 个日历日：date_to - date_from 最大为 30
            if (daysBetween > 30) {
                NursingRoutes.respond(ctx, 400, "date range must not exceed 31 days")
                return
            }

            val limitParam = params.getParam("limit")
            val limit = if (limitParam == null) 50 else {
                val parsed = limitParam.toIntOrNull()
                if (parsed == null || parsed < 0) {
                    NursingRoutes.respond(ctx, 400, "limit must be a non-negative integer")
                    return
                }
                parsed
            }
            val offsetParam = params.getParam("offset")
            val offset = if (offsetParam == null) 0 else {
                val parsed = offsetParam.toIntOrNull()
                if (parsed == null || parsed < 0) {
                    NursingRoutes.respond(ctx, 400, "offset must be a non-negative integer")
                    return
                }
                parsed
            }

            service.executionStatistics(
                dateFrom = dateFrom,
                dateTo = dateTo,
                periodId = params.getParam("period_id"),
                executor = params.getParam("executor"),
                limit = limit,
                offset = offset
            ).onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }
        router.get("/statistics").handler { handleStatistics(it) }
        router.get("/statistics/").handler { handleStatistics(it) }

        // ——— 批量生成执行记录 ———
        router.post("/generate").handler { ctx ->
            val b = NursingRoutes.body(ctx)
            val dateFrom = try {
                val s = b.getString("date_from") ?: return@handler NursingRoutes.respond(ctx, 400, "date_from is required")
                LocalDate.parse(s)
            } catch (_: Exception) {
                NursingRoutes.respond(ctx, 400, "invalid date_from format, expected YYYY-MM-DD")
                return@handler
            }
            val dateTo = try {
                val s = b.getString("date_to") ?: return@handler NursingRoutes.respond(ctx, 400, "date_to is required")
                LocalDate.parse(s)
            } catch (_: Exception) {
                NursingRoutes.respond(ctx, 400, "invalid date_to format, expected YYYY-MM-DD")
                return@handler
            }

            val periodId = b.getString("period_id")

            service.ensureExecutionsForDateRange(dateFrom, dateTo, periodId)
                .onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }

        // ——— 创建执行记录 ———
        router.post("/").handler { ctx ->
            val b = NursingRoutes.body(ctx)
            service.create(b)
                .onSuccess { ctx.response().setStatusCode(201); ctx.json(it) }
                .onFailure {
                    when (it) {
                        is IllegalArgumentException -> NursingRoutes.respond(ctx, 400, it.message)
                        else -> NursingRoutes.respondError(ctx, it)
                    }
                }
        }

        // ——— 列表查询 ———
        router.get("/").handler { ctx ->
            val params = ctx.request()
            service.list(
                taskId = params.getParam("task_id"),
                executor = params.getParam("executor"),
                status = params.getParam("status"),
                limit = params.getParam("limit")?.toIntOrNull() ?: 50,
                offset = params.getParam("offset")?.toIntOrNull() ?: 0
            ).onSuccess { ctx.json(it) }
                .onFailure { NursingRoutes.respondError(ctx, it) }
        }

        // ——— 获取单条 ———
        router.get("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            service.get(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) NursingRoutes.respond(ctx, 404, it.message)
                    else NursingRoutes.respondError(ctx, it)
                }
        }

        // ——— 获取耗材明细 ———
        router.get("/:id/consumptions").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            service.listExecutionConsumptions(id)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) NursingRoutes.respond(ctx, 404, it.message)
                    else NursingRoutes.respondError(ctx, it)
                }
        }

        // ——— 更新执行记录 ———
        router.put("/:id").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            val b = NursingRoutes.body(ctx)
            service.update(id, b)
                .onSuccess { ctx.json(it) }
                .onFailure {
                    if (it is NotFoundException) NursingRoutes.respond(ctx, 404, it.message)
                    else NursingRoutes.respondError(ctx, it)
                }
        }

        // ——— 状态更新（支持备注） ———
        router.patch("/:id/status").handler { ctx ->
            val id = ctx.pathParam("id") ?: return@handler NursingRoutes.respond(ctx, 400, "id required")
            val b = NursingRoutes.body(ctx)
            val status = b.getString("status", "")
            if (status.isBlank()) { NursingRoutes.respond(ctx, 400, "status required"); return@handler }

            val note = b.getString("note")
            val consumptionsJson = b.getJsonArray("consumptions")

            // 如果有耗材清单且状态为 COMPLETED，走带耗材流程
            if (consumptionsJson != null && consumptionsJson.size() > 0) {
                if (status != "COMPLETED") {
                    NursingRoutes.respond(ctx, 400, "consumptions only allowed for COMPLETED status")
                    return@handler
                }

                val authenticatedSubject = ctx.get("subject_id") as? String ?: ""
                val consumptions = mutableListOf<TaskExecutionService.ConsumptionInput>()
                for (i in 0 until consumptionsJson.size()) {
                    val item = consumptionsJson.getJsonObject(i)
                    val stockId = item.getString("stock_id")
                    val unit = item.getString("unit")
                    if (stockId.isNullOrBlank() || unit.isNullOrBlank()) {
                        NursingRoutes.respond(ctx, 400, "invalid consumption at index $i: stock_id and unit required")
                        return@handler
                    }
                    if (unit !in setOf("PACKAGE", "SPLIT")) {
                        NursingRoutes.respond(ctx, 400, "invalid unit at index $i: must be PACKAGE or SPLIT")
                        return@handler
                    }

                    val qty = item.getDouble("quantity")
                    val splitQty = item.getDouble("split_quantity")

                    if (unit == "PACKAGE" && (qty == null || qty <= 0)) {
                        NursingRoutes.respond(ctx, 400, "invalid consumption at index $i: quantity must be > 0 for PACKAGE unit")
                        return@handler
                    }
                    if (unit == "SPLIT" && (splitQty == null || splitQty <= 0)) {
                        NursingRoutes.respond(ctx, 400, "invalid consumption at index $i: split_quantity must be > 0 for SPLIT unit")
                        return@handler
                    }
                    if (unit == "SPLIT" && item.containsKey("quantity")) {
                        NursingRoutes.respond(ctx, 400, "invalid consumption at index $i: quantity not allowed for SPLIT unit")
                        return@handler
                    }

                    consumptions.add(TaskExecutionService.ConsumptionInput(
                        stockId = stockId,
                        unit = unit,
                        quantity = qty?.let { BigDecimal.valueOf(it) },
                        splitQuantity = splitQty?.let { BigDecimal.valueOf(it) }
                    ))
                }

                service.completeExecutionWithConsumptions(id, note, consumptions, authenticatedSubject)
                    .onSuccess { ctx.json(it) }
                    .onFailure {
                        when (it) {
                            is NotFoundException -> NursingRoutes.respond(ctx, 404, it.message)
                            is com.ovaphlow.crate.inventories.NotFoundException -> NursingRoutes.respond(ctx, 404, it.message)
                            is ConflictException -> NursingRoutes.respond(ctx, 409, it.message)
                            is com.ovaphlow.crate.inventories.ConflictException -> NursingRoutes.respond(ctx, 409, it.message)
                            is IllegalArgumentException -> NursingRoutes.respond(ctx, 400, it.message)
                            else -> NursingRoutes.respondError(ctx, it)
                        }
                    }
            } else {
                service.updateStatus(id, status, note)
                    .onSuccess { ctx.json(it) }
                    .onFailure {
                        when (it) {
                            is NotFoundException -> NursingRoutes.respond(ctx, 404, it.message)
                            is IllegalArgumentException -> NursingRoutes.respond(ctx, 400, it.message)
                            else -> NursingRoutes.respondError(ctx, it)
                        }
                    }
            }
        }

        return router
    }
}
