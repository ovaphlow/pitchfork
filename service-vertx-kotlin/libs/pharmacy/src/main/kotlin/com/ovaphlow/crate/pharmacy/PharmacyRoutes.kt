package com.ovaphlow.crate.pharmacy

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

    internal fun respondError(ctx: RoutingContext, err: Throwable?) {
        log.error("pharmacy route error", err)
        ctx.response().setStatusCode(500)
            .end(JsonObject().put("error", "internal error").encode())
    }
}
