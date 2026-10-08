package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.OperatorResolution
import com.ovaphlow.crate.common.resolveOperator
import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.sqlclient.Pool
import org.slf4j.LoggerFactory

object PharmacyRoutes {

    private val log = LoggerFactory.getLogger(PharmacyRoutes::class.java)

    fun create(
        vertx: Vertx,
        pool: Pool,
        medicalOrderReader: MedicalOrderReader,
        inventoryOutboundPort: InventoryOutboundPort,
        inventoryInboundPort: InventoryInboundPort,
        inventoryRequisitionTransferPort: InventoryRequisitionTransferPort,
        inventoryPurchaseReceiptPort: InventoryPurchaseReceiptPort,
        authHandler: io.vertx.core.Handler<RoutingContext>,
        /**
         * 025 药品目录端口（Pharmacy 侧）：由 Aceso `Main.kt` 注入。为 null 时
         * 存量医嘱补绑路径 fail-closed 返回 503；附加在末尾以保持既有调用点可编译。
         */
        drugCatalogPort: DrugCatalogPort? = null,
        /**
         * 040 组织部门目录端口：申领的「申领科室」必须是 Nexus departments.code。
         * 为 null 时保持旧行为（只校验非空）。
         */
        departmentDirectoryPort: DepartmentDirectoryPort? = null,
        /**
         * 054 D3 发药前过敏交叉检查端口：由 Aceso `Main.kt` 注入 healthcare 适配器。
         * 为 null 时发药确认不做检查（行为与接线前一致）。
         */
        drugAllergyCheckPort: DrugAllergyCheckPort? = null,
    ): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "pharmacy"))
        }

        // 同时匹配 /dispenses 和 /dispenses/...：列表契约是无尾斜杠的 /dispenses
        router.route("/dispenses*").subRouter(
            DispenseRoutes.create(
                vertx,
                mPool,
                medicalOrderReader,
                inventoryOutboundPort,
                drugCatalogPort,
                drugAllergyCheckPort,
            ),
        )
        router.route("/returns*").subRouter(ReturnRoutes.create(vertx, mPool, inventoryInboundPort))
        router.route("/requisitions*").subRouter(
            RequisitionRoutes.create(
                vertx,
                mPool,
                inventoryRequisitionTransferPort,
                authHandler,
                departmentDirectoryPort,
            ),
        )
        val (orderRouter, receiptRouter) = PurchaseOrderRoutes.create(
            vertx,
            mPool,
            inventoryPurchaseReceiptPort,
            authHandler,
        )
        router.route("/purchase-orders*").subRouter(orderRouter)
        router.route("/purchase-receipts*").subRouter(receiptRouter)

        // ====================================================================
        //  054 D2 儿科用药规则库与用量计算
        //  **直接挂聚合路由**（非子路由）：`DeclaredPermissionsTest` 的 BL2 门禁按聚合
        //  `writeRoutes(PharmacyRoutes.create(...), "/pharmacy/v1")` 全等比对，故新增写路由
        //  必须直接注册在本 router 上，否则门禁失效。
        //  写路由（POST /pediatric-dose-rules、PUT /pediatric-dose-rules/:id）由 `Main.kt`
        //  的集中式门禁按 `DECLARED_PERMISSIONS` 声明接线（`pharmacy:dispense`）；
        //  `GET /pediatric-dose-calculations` 为读路由，读开放（054 §5.4）。
        // ====================================================================
        val pediatricDoseRuleService = PediatricDoseRuleService(mPool)

        router.post("/pediatric-dose-rules").handler { ctx ->
            api(ctx, { pediatricDoseRuleService.create(body(ctx), ctx.get<String>("userId")) }) { result ->
                ctx.response().setStatusCode(201)
                ctx.json(result)
            }
        }

        router.get("/pediatric-dose-rules").handler { ctx ->
            val params = ctx.request()
            api(ctx, {
                pediatricDoseRuleService.list(
                    materialId = params.getParam("material_id"),
                    status = params.getParam("status"),
                    limit = params.getParam("limit")?.toIntOrNull()?.coerceIn(1, 100) ?: 50,
                    offset = params.getParam("offset")?.toIntOrNull()?.coerceAtLeast(0) ?: 0,
                )
            }) { ctx.json(it) }
        }

        router.put("/pediatric-dose-rules/:id").handler { ctx ->
            val id = ctx.pathParam("id")?.takeIf(String::isNotBlank)
                ?: return@handler respond(ctx, 400, "id is required")
            api(ctx, { pediatricDoseRuleService.update(id, body(ctx), ctx.get<String>("userId")) }) { ctx.json(it) }
        }

        router.get("/pediatric-dose-calculations").handler { ctx ->
            val params = ctx.request()
            val materialId = params.getParam("material_id")?.trim()
            if (materialId.isNullOrEmpty()) return@handler respond(ctx, 400, "material_id is required")
            api(ctx, {
                pediatricDoseRuleService.calculate(
                    materialId = materialId,
                    weightKg = PediatricDoseCalculator.parseWeightKg(params.getParam("weight_kg")),
                    ageMonths = PediatricDoseCalculator.parseAgeMonths(params.getParam("age_months")),
                )
            }) { ctx.json(it) }
        }

        return router
    }

    /**
     * 统一执行服务调用：同步抛出的参数校验异常（`IllegalArgumentException`，例如
     * [PediatricDoseCalculator.parseWeightKg] 拒绝 NaN/无穷/非正数）也映射为 400，
     * 避免 Vert.x 将其当作 500（与 dining 路由 `api()` 同口径）。
     */
    private fun <T> api(ctx: RoutingContext, action: () -> Future<T>, success: (T) -> Unit) {
        try {
            action()
                .onSuccess(success)
                .onFailure { DispenseRoutes.respondFailure(ctx, it) }
        } catch (error: IllegalArgumentException) {
            respond(ctx, 400, error.message)
        } catch (error: Throwable) {
            respondError(ctx, error)
        }
    }

    internal fun body(ctx: RoutingContext): JsonObject =
        ctx.body().asJsonObject() ?: JsonObject()

    internal fun respond(ctx: RoutingContext, status: Int, message: String?) {
        ctx.response().setStatusCode(status)
            .putHeader("Content-Type", "application/json")
            .end(JsonObject().put("error", message).encode())
    }

    /**
     * 042：解析本次请求的**有效操作人**并把"非管理员代操作"当场拒掉。
     *
     * 权威来源是认证会话（`ctx["userId"]`），客户端提交的 `operator` 只是"请求"：
     * 只有平台管理员（`ctx["platformAdmin"]`，来自 IDP `/session`）可以指定他人；
     * 非管理员提交他人 → `400 operator cannot be delegated`（fail-closed，**不静默改写**）；
     * 无认证挂载（嵌入式/集成测试路由）时回落请求体，保持既有测试语义（042 D4）。
     *
     * [block] 只在解析成功后执行，参数为有效操作人；`null` 表示既无会话也未提交，
     * 必填端点由服务端保持既有的 400/401 口径。判定失败时本函数已写完响应，不会调用 [block]。
     *
     * @param field 请求体里的操作人字段名（护理侧为 `assessor`/`created_by`/`executor`）
     */
    internal fun withOperator(
        ctx: RoutingContext,
        body: JsonObject,
        field: String = "operator",
        block: (String?) -> Unit,
    ) {
        when (
            val resolution = resolveOperator(
                actorId = ctx.get<String>("userId"),
                platformAdmin = ctx.get<Boolean>("platformAdmin") ?: false,
                requested = body.getString(field),
            )
        ) {
            is OperatorResolution.Resolved -> block(resolution.subjectId)
            is OperatorResolution.Delegated -> block(resolution.requested)
            OperatorResolution.Absent -> block(null)
            OperatorResolution.NotDelegatable -> respond(ctx, 400, "operator cannot be delegated")
        }
    }

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("pharmacy route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
