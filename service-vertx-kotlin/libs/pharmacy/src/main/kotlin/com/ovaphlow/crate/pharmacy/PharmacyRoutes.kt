package com.ovaphlow.crate.pharmacy

import com.ovaphlow.crate.common.OperatorResolution
import com.ovaphlow.crate.common.resolveOperator
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
    ): Router {
        val router = Router.router(vertx)
        val mPool = pool

        router.route().handler(BodyHandler.create())

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "pharmacy"))
        }

        // 同时匹配 /dispenses 和 /dispenses/...：列表契约是无尾斜杠的 /dispenses
        router.route("/dispenses*").subRouter(
            DispenseRoutes.create(vertx, mPool, medicalOrderReader, inventoryOutboundPort, drugCatalogPort),
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

        return router
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
