package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.healthcare.HealthcareRoutes
import com.ovaphlow.crate.healthcare.HealthcareService
import com.ovaphlow.crate.healthcare.HealthcareNotFoundException
import com.ovaphlow.crate.healthcare.MedicationOrderLockSnapshot
import com.ovaphlow.crate.inventories.ConflictException as InventoryConflictException
import com.ovaphlow.crate.inventories.InventoriesRoutes
import com.ovaphlow.crate.inventories.NotFoundException as InventoryNotFoundException
import com.ovaphlow.crate.inventories.StockService
import com.ovaphlow.crate.nursing.NursingRoutes
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
        val auth = fakeAuth()

        val router = Router.router(vertx)
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
            ),
        )
        router.route("/nursing/v1/*").subRouter(NursingRoutes.create(vertx, pool, auth))
        router.route("/pharmacy/v1/*").subRouter(
            PharmacyRoutes.create(
                vertx,
                pool,
                medicalOrderReader(healthcareService),
                inventoryOutboundPort(stockService),
                inventoryInboundPort(stockService),
                inventoryRequisitionTransferPort(stockService),
                inventoryPurchaseReceiptPort(stockService),
                auth,
            ),
        )
        return router
    }

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
        )

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
