package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.healthcare.DrugCatalogMaterial as HealthcareDrugCatalogMaterial
import com.ovaphlow.crate.healthcare.DrugCatalogPort as HealthcareDrugCatalogPort
import com.ovaphlow.crate.healthcare.HealthcareRoutes
import com.ovaphlow.crate.healthcare.HealthcareService
import com.ovaphlow.crate.healthcare.HealthcareNotFoundException
import com.ovaphlow.crate.healthcare.MedicationOrderLockSnapshot
import com.ovaphlow.crate.inventories.ConflictException as InventoryConflictException
import com.ovaphlow.crate.inventories.InventoriesRoutes
import com.ovaphlow.crate.inventories.MaterialService
import com.ovaphlow.crate.inventories.NotFoundException as InventoryNotFoundException
import com.ovaphlow.crate.inventories.StockService
import com.ovaphlow.crate.nursing.ConflictException as NursingConflictException
import com.ovaphlow.crate.nursing.NursingRoutes
import com.ovaphlow.crate.pharmacy.DrugCatalogMaterial as PharmacyDrugCatalogMaterial
import com.ovaphlow.crate.pharmacy.DrugCatalogPort as PharmacyDrugCatalogPort
import com.ovaphlow.crate.pharmacy.InboundCommand
import com.ovaphlow.crate.pharmacy.InboundResult
import com.ovaphlow.crate.pharmacy.InventoryInboundPort
import com.ovaphlow.crate.pharmacy.InventoryOutboundPort
import com.ovaphlow.crate.pharmacy.InventoryPurchaseReceiptPort
import com.ovaphlow.crate.pharmacy.InventoryRequisitionTransferPort
import com.ovaphlow.crate.pharmacy.MedicalOrderReader
import com.ovaphlow.crate.pharmacy.MedicationOrderSnapshot
import com.ovaphlow.crate.pharmacy.OutboundCommand
import com.ovaphlow.crate.pharmacy.OutboundResult
import com.ovaphlow.crate.pharmacy.PharmacyRoutes
import com.ovaphlow.crate.pharmacy.PurchaseReceiptCommand
import com.ovaphlow.crate.pharmacy.PurchaseReceiptItemCommand
import com.ovaphlow.crate.pharmacy.PurchaseReceiptItemResult
import com.ovaphlow.crate.pharmacy.PurchaseReceiptResult
import com.ovaphlow.crate.pharmacy.RequisitionReleaseCommand
import com.ovaphlow.crate.pharmacy.RequisitionReleaseItem
import com.ovaphlow.crate.pharmacy.RequisitionReserveCommand
import com.ovaphlow.crate.pharmacy.RequisitionReserveItem
import com.ovaphlow.crate.pharmacy.RequisitionTransferCommand
import com.ovaphlow.crate.pharmacy.RequisitionTransferItem
import com.ovaphlow.crate.pharmacy.RequisitionTransferItemResult
import com.ovaphlow.crate.pharmacy.RequisitionTransferResult
import com.ovaphlow.crate.pharmacy.ConflictException as PharmacyConflictException
import com.ovaphlow.crate.pharmacy.NotFoundException as PharmacyNotFoundException
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.SqlClient
import java.math.BigDecimal

/**
 * 共享的 Aceso 集成测试支撑：在测试 JVM 内挂载与生产 Main.kt 等效的路由，
 * 使用假认证（把 userId 放入上下文），并注入与 Main.kt 相同的同连接端口适配器。
 * 仅用于隔离 aceso_test 数据库测试。
 */
object AcesoIntegrationTestSupport {

    fun fakeAuth(): Handler<RoutingContext> = Handler { ctx ->
        ctx.put("userId", "integration-tester")
        ctx.next()
    }

    fun createRouter(vertx: Vertx, pool: Pool): Router {
        val healthcareService = HealthcareService(pool)
        val stockService = StockService(pool)
        // 025 药品目录适配器：与 apps/aceso/Main.kt 相同，两个端口都复用调用方连接
        // 走 MaterialService.findMaterialById，禁止端口内部重新取 Pool。
        val materialService = MaterialService(pool)
        val auth = fakeAuth()

        val router = Router.router(vertx)

        // ── 025 §4.5 fail-closed 负向挂载（必须在全局身份门之前注册）──────────
        // 路由按注册顺序匹配，且匹配到的 handler 不调用 next() 时不再继续；
        // `/pharmacy-noauth/v1/*` 用来证明「补绑路径缺可信身份 → 401」而不是伪造 null 审计。
        router.route("/pharmacy-noauth/v1/*").subRouter(
            pharmacyRouter(vertx, pool, healthcareService, stockService, materialService, drugCatalogConfigured = true),
        )

        // 与生产 Main.kt 的 `apiRouter.route().handler(apiAuthenticationGate(sessionAuth))` 等价：
        // 生产端在该门里 ctx.put("userId", subjectId)，补绑路径的 material_bound_by 依赖它。
        // 测试端必须同样先注入身份，否则补绑路径会因缺身份返回 401（fail-closed 生效）。
        router.route().handler(auth)
        router.route("/inventories/v1/*").subRouter(InventoriesRoutes.create(vertx, pool))
        router.route("/healthcare/v1/*").subRouter(
            HealthcareRoutes.create(
                vertx,
                pool,
                auth,
                auth,
                auth,
                auth,
                auth,
                auth,
                auth,
                auth,
                auth,
                auth,
                auth,
                drugCatalogPort = healthcareDrugCatalogPort(materialService),
            ),
        )
        router.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool, auth))
        router.route("/pharmacy/v1/*").subRouter(
            pharmacyRouter(vertx, pool, healthcareService, stockService, materialService, drugCatalogConfigured = true),
        )
        // `/pharmacy-noport/v1/*` 放在身份门之后：有可信身份、但缺药品目录端口，
        // 用来证明「端口漏注入 → 503」而不是 401，也不是静默跳过补绑。
        router.route("/pharmacy-noport/v1/*").subRouter(
            pharmacyRouter(vertx, pool, healthcareService, stockService, materialService, drugCatalogConfigured = false),
        )
        return router
    }

    /**
     * 025 药房子路由装配（与 Main.kt 相同）：`drugCatalogConfigured = false` 模拟
     * 部署漏注入药品目录端口，用于验证补绑路径 fail-closed 而不是静默跳过。
     */
    private fun pharmacyRouter(
        vertx: Vertx,
        pool: Pool,
        healthcareService: HealthcareService,
        stockService: StockService,
        materialService: MaterialService,
        drugCatalogConfigured: Boolean,
    ): Router =
        PharmacyRoutes.create(
            vertx,
            pool,
            medicalOrderReader(healthcareService),
            inventoryOutboundPort(stockService),
            inventoryInboundPort(stockService),
            inventoryRequisitionTransferPort(stockService),
            inventoryPurchaseReceiptPort(stockService),
            fakeAuth(),
            drugCatalogPort = if (drugCatalogConfigured) pharmacyDrugCatalogPort(materialService) else null,
        )

    fun migrate(poolConfig: JsonObject) {
        DatabaseConfig.migrate(poolConfig)
    }

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

            /** 025 与 Main.kt 相同：把 Healthcare 侧绑定的 nursing ConflictException 映射为药房 409。 */
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
            // 025 绑定投影必须一起映射：漏掉会让已绑定医嘱被误判为存量自由文本医嘱
            materialId = snapshot.materialId,
            materialCode = snapshot.materialCode,
            materialName = snapshot.materialName,
        )

    /** 025 Healthcare 侧药品目录端口：适配 MaterialService.findMaterialById（同连接只读）。 */
    private fun healthcareDrugCatalogPort(materialService: MaterialService): HealthcareDrugCatalogPort =
        object : HealthcareDrugCatalogPort {
            override fun findDrugMaterial(
                client: SqlClient,
                materialId: String,
            ): Future<HealthcareDrugCatalogMaterial?> =
                materialService.findMaterialById(client, materialId).map { material ->
                    material?.let {
                        HealthcareDrugCatalogMaterial(
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

    /** 025 Pharmacy 侧药品目录端口：与 Healthcare 侧同一适配，保持两侧判定一致。 */
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

    private fun inventoryOutboundPort(stockService: StockService): InventoryOutboundPort =
        object : InventoryOutboundPort {
            override fun validateOutbound(client: SqlClient, command: OutboundCommand): Future<Void?> =
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

            override fun confirmOutbound(client: SqlClient, command: OutboundCommand): Future<OutboundResult> =
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
            override fun confirmInbound(client: SqlClient, command: InboundCommand): Future<InboundResult> =
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

    private fun inventoryRequisitionTransferPort(stockService: StockService): InventoryRequisitionTransferPort =
        object : InventoryRequisitionTransferPort {
            override fun validateRequisitionMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> =
                mapPharmacyPortFailure(stockService.validateRequisitionMaterials(client, materialIds))

            override fun reserveStock(client: SqlClient, command: RequisitionReserveCommand): Future<Void?> =
                mapPharmacyPortFailure(
                    stockService.reserveStock(
                        client,
                        StockService.RequisitionReserveCommand(
                            warehouse = command.warehouse,
                            items = command.items.map { item ->
                                StockService.RequisitionReserveItem(item.materialId, item.lotId, item.quantity)
                            },
                        ),
                    ),
                )

            override fun releaseReservation(client: SqlClient, command: RequisitionReleaseCommand): Future<Void?> =
                mapPharmacyPortFailure(
                    stockService.releaseReservation(
                        client,
                        StockService.RequisitionReleaseCommand(
                            warehouse = command.warehouse,
                            items = command.items.map { item ->
                                StockService.RequisitionReleaseItem(item.materialId, item.lotId, item.quantity)
                            },
                        ),
                    ),
                )

            override fun confirmReservedTransfer(client: SqlClient, command: RequisitionTransferCommand): Future<RequisitionTransferResult> =
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
                                StockService.RequisitionTransferItem(item.materialId, item.lotId, item.quantity)
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

    private fun inventoryPurchaseReceiptPort(stockService: StockService): InventoryPurchaseReceiptPort =
        object : InventoryPurchaseReceiptPort {
            override fun validatePurchaseMaterials(client: SqlClient, materialIds: List<String>): Future<Void?> =
                mapPharmacyPortFailure(stockService.validatePurchaseMaterials(client, materialIds))

            override fun confirmPurchaseReceipt(client: SqlClient, command: PurchaseReceiptCommand): Future<PurchaseReceiptResult> =
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

    private fun <T> mapPharmacyPortFailure(future: Future<T>): Future<T> =
        future.recover { error ->
            when (error) {
                is HealthcareNotFoundException -> Future.failedFuture(PharmacyNotFoundException(error.message ?: "medical order not found"))
                is InventoryNotFoundException -> Future.failedFuture(PharmacyNotFoundException(error.message ?: "inventory resource not found"))
                is InventoryConflictException -> Future.failedFuture(PharmacyConflictException(error.message ?: "inventory conflict"))
                else -> Future.failedFuture(error)
            }
        }
}
