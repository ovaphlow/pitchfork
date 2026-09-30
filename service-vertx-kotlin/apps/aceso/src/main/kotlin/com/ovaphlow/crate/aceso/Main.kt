package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.dining.DiningRoutes
import com.ovaphlow.crate.healthcare.DrugCatalogMaterial
import com.ovaphlow.crate.healthcare.DrugCatalogPort
import com.ovaphlow.crate.healthcare.HealthcareRoutes
import com.ovaphlow.crate.healthcare.HealthcareService
import com.ovaphlow.crate.healthcare.HealthcareNotFoundException
import com.ovaphlow.crate.healthcare.MedicationOrderLockSnapshot
import com.ovaphlow.crate.inventories.ConflictException as InventoryConflictException
import com.ovaphlow.crate.inventories.InventoriesRoutes
import com.ovaphlow.crate.inventories.MaterialService
import com.ovaphlow.crate.inventories.NotFoundException as InventoryNotFoundException
import com.ovaphlow.crate.inventories.StockService
import com.ovaphlow.crate.log.Log
import com.ovaphlow.crate.nursing.ConflictException as NursingConflictException
import com.ovaphlow.crate.nursing.NursingRoutes
import com.ovaphlow.crate.pharmacy.InventoryOutboundPort
import com.ovaphlow.crate.pharmacy.InventoryInboundPort
import com.ovaphlow.crate.pharmacy.InventoryPurchaseReceiptPort
import com.ovaphlow.crate.pharmacy.InventoryRequisitionTransferPort
import com.ovaphlow.crate.pharmacy.MedicalOrderReader
import com.ovaphlow.crate.pharmacy.MedicationOrderSnapshot
import com.ovaphlow.crate.pharmacy.OutboundCommand
import com.ovaphlow.crate.pharmacy.OutboundResult
import com.ovaphlow.crate.pharmacy.InboundCommand
import com.ovaphlow.crate.pharmacy.InboundResult
import com.ovaphlow.crate.pharmacy.PharmacyRoutes
import com.ovaphlow.crate.pharmacy.PurchaseReceiptCommand
import com.ovaphlow.crate.pharmacy.PurchaseReceiptItemCommand
import com.ovaphlow.crate.pharmacy.PurchaseReceiptItemResult
import com.ovaphlow.crate.pharmacy.PurchaseReceiptResult
import com.ovaphlow.crate.pharmacy.RequisitionReserveCommand
import com.ovaphlow.crate.pharmacy.RequisitionReserveItem
import com.ovaphlow.crate.pharmacy.RequisitionReleaseCommand
import com.ovaphlow.crate.pharmacy.RequisitionReleaseItem
import com.ovaphlow.crate.pharmacy.RequisitionTransferCommand
import com.ovaphlow.crate.pharmacy.RequisitionTransferItem
import com.ovaphlow.crate.pharmacy.RequisitionTransferItemResult
import com.ovaphlow.crate.pharmacy.RequisitionTransferResult
import com.ovaphlow.crate.pharmacy.ConflictException as PharmacyConflictException
import com.ovaphlow.crate.pharmacy.DrugCatalogMaterial as PharmacyDrugCatalogMaterial
import com.ovaphlow.crate.pharmacy.DrugCatalogPort as PharmacyDrugCatalogPort
import com.ovaphlow.crate.pharmacy.NotFoundException as PharmacyNotFoundException
import io.vertx.config.ConfigRetriever
import io.vertx.config.ConfigRetrieverOptions
import io.vertx.config.ConfigStoreOptions
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpClientRequest
import io.vertx.core.http.HttpClientResponse
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import java.net.URI
import java.util.concurrent.TimeUnit
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.handler.CorsHandler
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import org.slf4j.LoggerFactory

private val log = Log.getLogger("com.ovaphlow.crate.aceso.MainKt")

fun main() {
    val vertx = Vertx.vertx()
    val configPath =
        System.getenv("PITCHFORK_CONFIG")?.takeIf(String::isNotBlank) ?: "config.json"

    val retriever =
        ConfigRetriever.create(
            vertx,
            ConfigRetrieverOptions().addStore(
                ConfigStoreOptions()
                    .setType("file")
                    .setFormat("json")
                    .setConfig(JsonObject().put("path", configPath)),
            ),
        )

    val config =
        retriever
            .getConfig()
            .toCompletionStage()
            .toCompletableFuture()
            .get()

    val consoleLevel = config.getString("console-level", "DEBUG")
    val ctx = LoggerFactory.getILoggerFactory() as ch.qos.logback.classic.LoggerContext
    ctx.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).level =
        ch.qos.logback.classic.Level
            .toLevel(consoleLevel)

    val dbConfig = config.getJsonObject("database", JsonObject())
    DatabaseConfig.migrate(dbConfig)
    val pool = DatabaseConfig.createPool(vertx, dbConfig)

    val mainRouter = Router.router(vertx)

    // --- CORS ---
    val corsHandler = CorsHandler.create()
    val defaultCorsOrigins = JsonArray()
        .add("http://localhost:4324")
        .add("http://127.0.0.1:4324")
    config
        .getJsonObject("server", JsonObject())
        .getJsonArray("cors-origins", defaultCorsOrigins)
        .forEach { origin -> corsHandler.addOrigin(origin.toString()) }
    mainRouter.route().handler(
        corsHandler
            .allowCredentials(true)
            .allowedMethod(HttpMethod.GET)
            .allowedMethod(HttpMethod.POST)
            .allowedMethod(HttpMethod.PUT)
            .allowedMethod(HttpMethod.PATCH)
            .allowedMethod(HttpMethod.DELETE)
            .allowedMethod(HttpMethod.OPTIONS)
            .allowedHeader("Content-Type")
            .allowedHeader("Authorization")
            .allowedHeader("X-CSRF-Token")
            .allowedHeader("Idempotency-Key"),
    )

    val nexusBaseUrl = config.getJsonObject("nexus", JsonObject()).getString("base-url", "http://127.0.0.1:8421")
    val identityConfig = config.getJsonObject("identity", JsonObject())
    val idpBaseUrl = identityConfig.getString("base-url", "http://127.0.0.1:8420")
    val idpSessionTimeoutMs = resolveIdpSessionTimeoutMs(identityConfig)
    mainRouter.route("/crate-api/*").subRouter(
        buildApiRouter(vertx, pool, idpBaseUrl, nexusBaseUrl, idpSessionTimeoutMs),
    )

    mainRouter.route("/health").handler { ctx ->
        ctx.json(JsonObject().put("status", "ok").put("app", "aceso"))
    }

    mainRouter.route().failureHandler { ctx ->
        val statusCode = ctx.statusCode() ?: 500
        val err = ctx.failure()
        log.error(
            "request exception: {} {} -> {}: {}",
            ctx.request().method(),
            ctx.request().path(),
            statusCode,
            err?.message ?: "unknown",
            err,
        )
        if (!ctx.response().ended()) {
            ctx.response().setStatusCode(statusCode).end(
                JsonObject()
                    .put("error", if (statusCode == 500) "internal error" else (err?.message ?: "unknown"))
                    .encode(),
            )
        }
    }

    val port = config.getJsonObject("server", JsonObject()).getInteger("port", 8080)
    val server =
        vertx
            .createHttpServer()
            .requestHandler(mainRouter)
            .listen(port)
            .toCompletionStage()
            .toCompletableFuture()
            .get()
    log.info("Server started on port {}", server.actualPort())
}

/**
 * 构建 crate-api 子树（挂载点 `/crate-api`）的 API 路由树：请求体处理 → 默认拒绝认证总闸 → 各模块子路由。
 *
 * **顺序不可调换**：`BodyHandler` 必须在总闸之前（详见函数内的根因注释）；总闸必须在一切
 * 模块子路由之前。`internal` 是为了让无数据库回归护栏 `ApiBodyHandlingTest` 能对
 * **生产同款装配**断言，而不是对着手抄的镜像装配断言（本次 P0 正是因为只测了镜像装配才漏掉）。
 */
internal fun buildApiRouter(
    vertx: Vertx,
    pool: Pool,
    idpBaseUrl: String,
    nexusBaseUrl: String,
    idpSessionTimeoutMs: Long = DEFAULT_IDP_SESSION_TIMEOUT_MS,
): Router {
    val healthcareService = HealthcareService(pool)
    val stockService = StockService(pool)
    val materialService = MaterialService(pool)

    val apiRouter = Router.router(vertx)

    // 请求体必须在总闸**之前**、于同步首轮路由里读完；不要依赖各模块子路由里的 BodyHandler。
    //
    // 根因（2026-09-29 P0「写操作全部挂起」）：Vert.x-Web 的 BodyHandler 用固定 handler id
    // 在 RoutingContext 上去重，**只有首个生效**，后续实例只做 form 属性合并后 ctx.next()。
    // 总闸是异步的（先发 IdP 会话校验，回调里才 ctx.next()）；让出事件循环期间请求体已被读完
    // （小请求体与请求头在同一个事件循环任务内到达），路由恢复时 request.isEnded() 已为 true，
    // 此时模块里的 BodyHandler 作为首个 BodyHandler 会走「请求已结束」分支**直接 return 且不调用
    // ctx.next()** —— 于是没有任何处理器写响应：带请求体的写请求永久挂起（前端只剩 pending，
    // 服务端无异常、无日志、无数据库写入），GET 无请求体故不受影响。
    // 无数据库回归护栏见 apps/aceso/src/test/.../ApiBodyHandlingTest.kt。
    // 上限 20MB 与应用内唯一既有自定义上限（ServiceProxyRoutes 的 Nexus 代理）一致；
    // 各模块接口均为 JSON，载荷远小于该值。
    apiRouter.route().handler(BodyHandler.create().setBodyLimit(20L * 1024 * 1024))

    // 默认拒绝：白名单（各模块 /health、Identity 代理子树、OPTIONS 预检）之外的一切
    // /crate-api/* 都必须先通过 IdP 会话校验；新路由默认受保护，不再依赖逐路由挂载。
    // 必须注册在任何模块子路由之前——Vert.x 按注册顺序匹配，注册晚了等于没注册。
    val sessionAuth = idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs)
    apiRouter.route().handler(apiAuthenticationGate(sessionAuth))

    apiRouter.route("/identity/v1/*").subRouter(
        ServiceProxyRoutes.create(
            vertx,
            idpBaseUrl,
            "identity",
            "/crate-api/identity/v1/problems/identity-unavailable",
        ),
    )
    apiRouter.route("/shared/v1/*").subRouter(
        ServiceProxyRoutes.create(
            vertx,
            nexusBaseUrl,
            "nexus",
            "/crate-api/shared/v1/problems/nexus-unavailable",
        ),
    )
    apiRouter.route("/inventories/v1/*").subRouter(InventoriesRoutes.create(vertx, pool))
    apiRouter.route("/healthcare/v1/*").subRouter(
        HealthcareRoutes.create(
            vertx,
            pool,
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            drugCatalogPort = healthcareDrugCatalogPort(materialService),
        ),
    )
    apiRouter.route("/nursing/v1/*").subRouter(
        NursingRoutes.create(
            vertx,
            pool,
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
        ),
    )
    apiRouter.route("/dining/v1/*").subRouter(
        DiningRoutes.create(
            vertx,
            pool,
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
        ),
    )
    apiRouter.route("/pharmacy/v1/*").subRouter(
        PharmacyRoutes.create(
            vertx,
            pool,
            medicalOrderReader(healthcareService),
            inventoryOutboundPort(stockService),
            inventoryInboundPort(stockService),
            inventoryRequisitionTransferPort(stockService),
            inventoryPurchaseReceiptPort(stockService),
            idpSessionAuthHandler(vertx, idpBaseUrl, idpSessionTimeoutMs),
            drugCatalogPort = pharmacyDrugCatalogPort(materialService),
        ),
    )
    return apiRouter
}

// ========================================================================
//  025 药品目录同连接端口适配器
//  药品目录即 inventories.materials 中 category='药品' 的记录（方案 A）；
//  两个适配器都复用调用方传入的 client（外层事务连接），
//  禁止在端口内部重新取 Pool 或开启新事务。
// ========================================================================

private fun healthcareDrugCatalogPort(materialService: MaterialService): DrugCatalogPort =
    object : DrugCatalogPort {
        override fun findDrugMaterial(
            client: SqlClient,
            materialId: String,
        ): Future<DrugCatalogMaterial?> =
            materialService.findMaterialById(client, materialId).map { material ->
                material?.let {
                    DrugCatalogMaterial(
                        id = it.id,
                        code = it.code,
                        name = it.name,
                        spec = it.spec,
                        baseUnit = it.baseUnit,
                        status = it.status,
                        category = it.category,
                    )
                }
            }
    }

private fun pharmacyDrugCatalogPort(materialService: MaterialService): PharmacyDrugCatalogPort =
    object : PharmacyDrugCatalogPort {
        override fun findDrugMaterial(
            client: SqlClient,
            materialId: String,
        ): Future<PharmacyDrugCatalogMaterial?> =
            materialService.findMaterialById(client, materialId).map { material ->
                material?.let {
                    PharmacyDrugCatalogMaterial(
                        id = it.id,
                        code = it.code,
                        name = it.name,
                        spec = it.spec,
                        baseUnit = it.baseUnit,
                        status = it.status,
                        category = it.category,
                    )
                }
            }
    }

// ========================================================================
//  011 药房同连接内部端口适配器
//  Pharmacy 不直接读写 Healthcare/Inventory 表；所有端口调用复用 Pharmacy
//  外层事务连接，禁止在端口内部重新从 Pool 开启新事务。
// ========================================================================

private fun medicalOrderReader(healthcareService: HealthcareService): MedicalOrderReader =
    object : MedicalOrderReader {
        override fun listMedicationOrders(
            client: SqlClient,
            encounterId: String?,
            search: String?,
            limit: Int,
            offset: Int,
        ): Future<JsonObject> =
            healthcareService.listMedicationOrdersForPharmacy(client, encounterId, search, limit, offset)

        override fun lockMedicationOrder(client: SqlClient, medicalOrderId: String): Future<MedicationOrderSnapshot> =
            mapPharmacyPortFailure(
                healthcareService.lockMedicationOrderForPharmacy(client, medicalOrderId)
                    .map(::toMedicationOrderSnapshot),
            )

        override fun bindDrugMaterial(
            client: SqlClient,
            medicalOrderId: String,
            materialId: String,
            materialCode: String?,
            materialName: String?,
            operator: String,
        ): Future<Void?> =
            healthcareService.bindDrugMaterial(
                client,
                medicalOrderId,
                materialId,
                materialCode,
                materialName,
                operator,
            ).recover { error ->
                when (error) {
                    // 医嘱已绑定（重复补绑）由 Healthcare 侧以 nursing ConflictException 抛出，
                    // 在药房边界映射为 409，避免退化为 500
                    is NursingConflictException -> Future.failedFuture(
                        PharmacyConflictException(error.message ?: "medical order conflict"),
                    )
                    else -> mapPharmacyPortFailure(Future.failedFuture(error))
                }
            }
    }

private fun toMedicationOrderSnapshot(snapshot: MedicationOrderLockSnapshot): MedicationOrderSnapshot =
    MedicationOrderSnapshot(
        orderId = snapshot.orderId,
        encounterId = snapshot.encounterId,
        patientId = snapshot.patientId,
        patientName = snapshot.patientName,
        encounterNo = snapshot.encounterNo,
        encounterType = snapshot.encounterType,
        encounterStatus = snapshot.encounterStatus,
        orderType = snapshot.orderType,
        orderClass = snapshot.orderClass,
        orderStatus = snapshot.orderStatus,
        orderContent = snapshot.orderContent,
        doctor = snapshot.doctor,
        startTime = snapshot.startTime,
        endTime = snapshot.endTime,
        orderDetails = snapshot.orderDetails,
        nurseCheckedBy = snapshot.nurseCheckedBy,
        nurseCheckedAt = snapshot.nurseCheckedAt,
        materialId = snapshot.materialId,
        materialCode = snapshot.materialCode,
        materialName = snapshot.materialName,
    )

private fun inventoryOutboundPort(stockService: StockService): InventoryOutboundPort =
    object : InventoryOutboundPort {
        override fun validateOutbound(
            client: SqlClient,
            command: OutboundCommand,
        ): Future<Void?> =
            mapPharmacyPortFailure(
                stockService.validateOutbound(
                    client,
                    StockService.OutboundCommand(
                        warehouse = command.warehouse,
                        materialId = command.materialId,
                        lotId = command.lotId,
                        quantity = command.quantity,
                        note = command.note,
                    ),
                ),
            )

        override fun confirmOutbound(
            client: SqlClient,
            command: OutboundCommand,
        ): Future<OutboundResult> =
            mapPharmacyPortFailure(
                stockService.confirmOutbound(
                    client,
                    StockService.OutboundCommand(
                        warehouse = command.warehouse,
                        materialId = command.materialId,
                        lotId = command.lotId,
                        quantity = command.quantity,
                        note = command.note,
                    ),
                ),
            ).map { result ->
                OutboundResult(
                    stockOperationDetailId = result.stockOperationDetailId,
                    lotId = result.lotId,
                    unitCost = result.unitCost,
                )
            }
    }

private fun inventoryInboundPort(stockService: StockService): InventoryInboundPort =
    object : InventoryInboundPort {
        override fun confirmInbound(
            client: SqlClient,
            command: InboundCommand,
        ): Future<InboundResult> =
            mapPharmacyPortFailure(
                stockService.confirmReturnInbound(
                    client,
                    StockService.ReturnInboundCommand(
                        warehouse = command.warehouse,
                        materialId = command.materialId,
                        lotId = command.lotId,
                        quantity = command.quantity,
                        unitCost = command.unitCost,
                        note = command.note,
                    ),
                ),
            ).map { result ->
                InboundResult(
                    stockOperationDetailId = result.stockOperationDetailId,
                    lotId = result.lotId,
                    unitCost = result.unitCost,
                )
            }
    }

private fun <T> mapPharmacyPortFailure(future: Future<T>): Future<T> =
    future.recover { error ->
        when (error) {
            is HealthcareNotFoundException -> Future.failedFuture(PharmacyNotFoundException(error.message ?: "medical order not found"))
            is InventoryNotFoundException -> Future.failedFuture(PharmacyNotFoundException(error.message ?: "inventory resource not found"))
            is InventoryConflictException -> Future.failedFuture(PharmacyConflictException(error.message ?: "inventory conflict"))
            else -> Future.failedFuture(error)
        }
    }

// ========================================================================
//  013 护理站申领：同连接预留/释放/整单调拨端口适配器
//  与 011/012 相同，StockService 适配器复用 Pharmacy 外层事务连接。
// ========================================================================

private fun inventoryRequisitionTransferPort(stockService: StockService): InventoryRequisitionTransferPort =
    object : InventoryRequisitionTransferPort {
        override fun validateRequisitionMaterials(
            client: SqlClient,
            materialIds: List<String>,
        ): Future<Void?> =
            mapPharmacyPortFailure(stockService.validateRequisitionMaterials(client, materialIds))

        override fun reserveStock(
            client: SqlClient,
            command: RequisitionReserveCommand,
        ): Future<Void?> =
            mapPharmacyPortFailure(
                stockService.reserveStock(
                    client,
                    StockService.RequisitionReserveCommand(
                        warehouse = command.warehouse,
                        items = command.items.map { item ->
                            StockService.RequisitionReserveItem(
                                materialId = item.materialId,
                                lotId = item.lotId,
                                quantity = item.quantity,
                            )
                        },
                    ),
                ),
            )

        override fun releaseReservation(
            client: SqlClient,
            command: RequisitionReleaseCommand,
        ): Future<Void?> =
            mapPharmacyPortFailure(
                stockService.releaseReservation(
                    client,
                    StockService.RequisitionReleaseCommand(
                        warehouse = command.warehouse,
                        items = command.items.map { item ->
                            StockService.RequisitionReleaseItem(
                                materialId = item.materialId,
                                lotId = item.lotId,
                                quantity = item.quantity,
                            )
                        },
                    ),
                ),
            )

        override fun confirmReservedTransfer(
            client: SqlClient,
            command: RequisitionTransferCommand,
        ): Future<RequisitionTransferResult> =
            mapPharmacyPortFailure(
                stockService.confirmReservedTransfer(
                    client,
                    StockService.RequisitionTransferCommand(
                        sourceWarehouse = command.sourceWarehouse,
                        destinationWarehouse = command.destinationWarehouse,
                        requisitionId = command.requisitionId,
                        requisitionNo = command.requisitionNo,
                        dispensedBy = command.dispensedBy,
                        items = command.items.map { item ->
                            StockService.RequisitionTransferItem(
                                materialId = item.materialId,
                                lotId = item.lotId,
                                quantity = item.quantity,
                            )
                        },
                    ),
                ),
            ).map { result ->
                RequisitionTransferResult(
                    outboundOperationId = result.outboundOperationId,
                    inboundOperationId = result.inboundOperationId,
                    items = result.items.map { item ->
                        RequisitionTransferItemResult(
                            materialId = item.materialId,
                            lotId = item.lotId,
                            outboundStockOperationDetailId = item.outboundStockOperationDetailId,
                            inboundStockOperationDetailId = item.inboundStockOperationDetailId,
                            unitCost = item.unitCost,
                        )
                    },
                )
            }
    }

// ========================================================================
//  014 药房采购收货：同连接批量入库端口适配器
//  与 011/012/013 相同，StockService 适配器复用 Pharmacy 外层事务连接；
//  库存错误映射为 Pharmacy 异常（404/409）。
// ========================================================================

private fun inventoryPurchaseReceiptPort(stockService: StockService): InventoryPurchaseReceiptPort =
    object : InventoryPurchaseReceiptPort {
        override fun validatePurchaseMaterials(
            client: SqlClient,
            materialIds: List<String>,
        ): Future<Void?> =
            mapPharmacyPortFailure(stockService.validatePurchaseMaterials(client, materialIds))

        override fun confirmPurchaseReceipt(
            client: SqlClient,
            command: PurchaseReceiptCommand,
        ): Future<PurchaseReceiptResult> =
            mapPharmacyPortFailure(
                stockService.confirmPurchaseReceipt(
                    client,
                    StockService.PurchaseReceiptCommand(
                        warehouse = command.warehouse,
                        supplierName = command.supplierName,
                        purchaseOrderId = command.purchaseOrderId,
                        purchaseOrderNo = command.purchaseOrderNo,
                        purchaseReceiptId = command.purchaseReceiptId,
                        receiptNo = command.receiptNo,
                        receivedBy = command.receivedBy,
                        items = command.items.map { item ->
                            StockService.PurchaseReceiptItem(
                                receiptItemId = item.receiptItemId,
                                materialId = item.materialId,
                                batchNo = item.batchNo,
                                productionDate = item.productionDate,
                                expiryDate = item.expiryDate,
                                manufacturer = item.manufacturer,
                                quantity = item.quantity,
                                unitCost = item.unitCost,
                            )
                        },
                    ),
                ),
            ).map { result ->
                PurchaseReceiptResult(
                    stockOperationId = result.stockOperationId,
                    items = result.items.map { item ->
                        PurchaseReceiptItemResult(
                            receiptItemId = item.receiptItemId,
                            materialId = item.materialId,
                            batchNo = item.batchNo,
                            lotId = item.lotId,
                            stockOperationDetailId = item.stockOperationDetailId,
                            unitCost = item.unitCost,
                            totalCost = item.totalCost,
                        )
                    },
                )
            }
    }

// ========================================================================
//  013 认证挂载：IDP opaque session 验证
//  IDP 使用 HttpOnly cookie `identityd_session`，不签发 JWT。写路由要求有效
//  会话：转发请求 Cookie 到 IDP `GET /crate-api/identity/v1/session` 换取
//  `subject_id` 作为操作人（userId），与 Aceso 前端既有 cookie 工作流一致。
// ========================================================================

private const val IDP_SESSION_COOKIE = "identityd_session"

/**
 * IdP 会话校验的上游超时（连接 + 整体，含读响应体）默认值，单位毫秒。
 *
 * 存在的理由：前端只对 `401` 跳登录（`ui-astro/packages/shared/src/aceso.ts`），
 * 因此「IdP 抖动」必须既**有界返回**（不能永久挂起）又**不进 401**（不能假掉线）。
 */
internal const val DEFAULT_IDP_SESSION_TIMEOUT_MS = 3000L

/** 依赖不可用时回给前端的重试提示秒数。 */
private const val IDP_UNAVAILABLE_RETRY_AFTER_SECONDS = "5"

/**
 * 从 `identity` 配置节解析会话校验超时：缺失（或非长整数字面量以外的一切缺省场景）用
 * [DEFAULT_IDP_SESSION_TIMEOUT_MS]。非法值（非正数/超出 Int 范围）由
 * [idpSessionAuthHandler] 的 `require` 在**启动装配时**拒绝，而不是等请求到达才抛。
 */
internal fun resolveIdpSessionTimeoutMs(identityConfig: JsonObject): Long =
    identityConfig.getLong("session-timeout-ms") ?: DEFAULT_IDP_SESSION_TIMEOUT_MS

/**
 * IdP 会话校验 handler。
 *
 * ## 失败分类（两类失败必须分开，否则依赖抖动会被前端当成会话失效）
 *
 * - 上游 `401` / `403` → 本地 `401 {"error":"authentication required"}`（会话无效，前端登出，行为不变）；
 * - 上游 `200` 但取不到可用 `subject_id`（键缺失，或响应体不是含该键的 JSON 对象）→ 本地 `401`
 *   （响应形状异常，按会话无效处理，与改动前一致）；
 * - 上游 `5xx` / 连接失败 / 超时 / 无响应 → 本地 `503` + `Retry-After`
 *   （依赖不可用，前端不登出，只报错）。
 *
 * `internal` 是为了让无数据库测试（`IdpSessionAuthHandlerTest`）能对**真实实现**断言，
 * 而不是对着手抄的镜像实现断言。挂载方式与顺序仍由 [buildApiRouter] 决定，未变。
 */
internal fun idpSessionAuthHandler(
    vertx: Vertx,
    idpBaseUrl: String,
    timeoutMs: Long = DEFAULT_IDP_SESSION_TIMEOUT_MS,
): Handler<RoutingContext> {
    require(timeoutMs in 1..Int.MAX_VALUE.toLong()) {
        "identity.session-timeout-ms must be within 1..${Int.MAX_VALUE}, but was $timeoutMs"
    }
    val client = vertx.createHttpClient(HttpClientOptions().setConnectTimeout(timeoutMs.toInt()))
    val upstream = URI(idpBaseUrl)
    val host = requireNotNull(upstream.host) { "identity.base-url must include a host" }
    val port = if (upstream.port == -1) 80 else upstream.port
    val basePath = upstream.rawPath.orEmpty().removeSuffix("/")
    val target = "$basePath/crate-api/identity/v1/session"
    return Handler { ctx ->
        val cookie = ctx.request().getHeader("Cookie")
        if (cookie == null || !cookie.contains("$IDP_SESSION_COOKIE=")) {
            respondUnauthorized(ctx); return@Handler
        }
        client.request(HttpMethod.GET, port, host, target)
            .compose { req: HttpClientRequest ->
                req.putHeader("Cookie", cookie)
                req.send().compose { resp: HttpClientResponse ->
                    // 一律读完响应体：只按状态码分类也要避免半读连接，且整体超时必须覆盖读体阶段。
                    resp.body().map { body: Buffer -> Pair(resp.statusCode(), body) }
                }
            }
            // 连接 + 请求（含读响应体）的整体超时：上游挂起时在这里有界结束，绝不无限挂起。
            .timeout(timeoutMs, TimeUnit.MILLISECONDS)
            .onSuccess { pair: Pair<Int, Buffer> ->
                when (pair.first) {
                    200 -> {
                        val subjectId = subjectIdOrNull(pair.second)
                        if (subjectId == null) {
                            respondUnauthorized(ctx)
                        } else {
                            ctx.put("userId", subjectId)
                            ctx.next()
                        }
                    }
                    401, 403 -> respondUnauthorized(ctx)
                    else -> {
                        // 3xx 与其它 4xx（404 路径配错、429 限流…）仍归「依赖不可用」回 503：
                        // 若按 401 处理会假掉线，是刻意选择。但排障必须能区分「目标配错」与「真实抖动」，
                        // 故记录状态码与目标 URL —— **只记状态码与 URL，绝不记 cookie / 会话值**。
                        log.warn(
                            "idp session check upstream {}{} returned unexpected status {}; degrading to 503",
                            idpBaseUrl.removeSuffix("/"),
                            target,
                            pair.first,
                        )
                        respondIdentityUnavailable(ctx)
                    }
                }
            }
            .onFailure { respondIdentityUnavailable(ctx) }
    }
}

/** 上游 `200` 响应里的 `subject_id`；缺失或响应体不是含该键的 JSON 对象时返回 `null`。 */
private fun subjectIdOrNull(body: Buffer): String? =
    runCatching { body.toJsonObject().getString("subject_id") }.getOrNull()?.takeIf { it.isNotBlank() }

private fun respondUnauthorized(ctx: RoutingContext) {
    if (!ctx.response().ended()) {
        ctx.response().setStatusCode(401).end(JsonObject().put("error", "authentication required").encode())
    }
}

/** 依赖不可用（上游 5xx / 连接失败 / 超时 / 无响应）：`503` + `Retry-After`，前端不登出、只报错。 */
private fun respondIdentityUnavailable(ctx: RoutingContext) {
    if (!ctx.response().ended()) {
        ctx.response()
            .setStatusCode(503)
            .putHeader("Retry-After", IDP_UNAVAILABLE_RETRY_AFTER_SECONDS)
            .end(JsonObject().put("error", "identity service unavailable").encode())
    }
}
