package com.ovaphlow.crate.pharmacy

import io.vertx.core.Future
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.SqlClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * 011 同连接内部端口：医嘱只读/锁读。
 *
 * 端口调用禁止重新从 Pool 开启新事务，所有读写必须复用 Pharmacy 外层事务的连接；
 * 由 Aceso `Main.kt` 注入 HealthcareService 适配器。
 */
interface MedicalOrderReader {

    /**
     * 只读列出可接方用药医嘱：只返回活动养老入住（ELDERLY_CARE + ACTIVE）下的
     * `MEDICATION` + `ACTIVE` 医嘱。只读场景调用方传 Pool 即可，不开启事务。
     */
    fun listMedicationOrders(
        client: SqlClient,
        encounterId: String?,
        search: String?,
        limit: Int,
        offset: Int,
    ): Future<JsonObject>

    /**
     * 在外层事务内精确锁定一条医嘱（FOR UPDATE OF medical_orders），返回受控快照。
     * 患者、入住、医生和医嘱内容全部来自该快照，不接受客户端覆盖。
     */
    fun lockMedicationOrder(client: SqlClient, medicalOrderId: String): Future<MedicationOrderSnapshot>
}

/** 011 接方/发药所需的医嘱受控快照（由 Healthcare 侧生成，字段固定，不含 SQL/堆栈细节） */
data class MedicationOrderSnapshot(
    val orderId: String,
    val encounterId: String,
    val patientId: String,
    val patientName: String,
    val encounterNo: String?,
    val encounterType: String,
    val encounterStatus: String,
    val orderType: String,
    val orderClass: String?,
    val orderStatus: String,
    val orderContent: String,
    val doctor: String,
    val startTime: OffsetDateTime?,
    val endTime: OffsetDateTime?,
    val orderDetails: JsonObject,
)

/** 011 包装单位出库命令（单位固定 PACKAGE，不支持拆零） */
data class PackageOutboundCommand(
    val warehouse: String,
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
    val note: String?,
)

/** 011 包装单位出库结果：库存操作明细 ID、实际批次和单位成本 */
data class PackageOutboundResult(
    val stockOperationDetailId: String,
    val lotId: String?,
    val unitCost: BigDecimal,
)

/** 012 退药回库命令：物资、批次、成本均由原发药明细推导。 */
data class PackageInboundCommand(
    val warehouse: String,
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val note: String?,
)

data class PackageInboundResult(
    val stockOperationDetailId: String,
    val lotId: String?,
    val unitCost: BigDecimal,
)

/**
 * 011 同连接内部端口：包装单位库存出库。
 *
 * 由 Aceso `Main.kt` 注入 StockService 适配器；必须在 Pharmacy 外层事务连接内调用，
 * 与药房单状态变更同事务提交或回滚。
 */
interface InventoryOutboundPort {
    /**
     * 在创建发药单前只读校验包装单位出库条件。不得扣减库存或写库存操作。
     */
    fun validatePackageOutbound(client: SqlClient, command: PackageOutboundCommand): Future<Void?>

    fun confirmPackageOutbound(client: SqlClient, command: PackageOutboundCommand): Future<PackageOutboundResult>
}

/**
 * 012 同连接内部端口：退药包装单位回库。
 * 使用库存 INBOUND 操作并通过 metadata.source 标记 PHARMACY_RETURN。
 */
interface InventoryInboundPort {
    fun confirmPackageInbound(client: SqlClient, command: PackageInboundCommand): Future<PackageInboundResult>
}

// ========================================================================
//  013 护理站申领：预留、释放与整单双仓调拨端口
// ========================================================================

/** 013 预留命令：审批时把批准数量等额预留到药房源库存的 locked_quantity。 */
data class RequisitionReserveCommand(
    val warehouse: String,
    val items: List<RequisitionReserveItem>,
)

data class RequisitionReserveItem(
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
)

/** 013 释放命令：取消已审批单据时释放此前预留的 locked_quantity，不产生库存操作。 */
data class RequisitionReleaseCommand(
    val warehouse: String,
    val items: List<RequisitionReleaseItem>,
)

data class RequisitionReleaseItem(
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
)

/** 013 整单调拨命令：由服务端从锁定申领明细构造，不接受客户端库存行/成本/预留量。 */
data class RequisitionTransferItem(
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
)

data class RequisitionTransferCommand(
    val sourceWarehouse: String,
    val destinationWarehouse: String,
    val requisitionId: String,
    val requisitionNo: String,
    val dispensedBy: String,
    val items: List<RequisitionTransferItem>,
)

/** 013 整单调拨结果：每项的双向库存操作明细 ID 与守恒单位成本。 */
data class RequisitionTransferItemResult(
    val materialId: String,
    val lotId: String?,
    val outboundStockOperationDetailId: String,
    val inboundStockOperationDetailId: String,
    val unitCost: BigDecimal,
)

data class RequisitionTransferResult(
    val outboundOperationId: String,
    val inboundOperationId: String,
    val items: List<RequisitionTransferItemResult>,
)

/**
 * 013 同连接内部端口：申领预留、释放与整单 PACKAGE 双仓调拨。
 *
 * 由 Aceso `Main.kt` 注入 StockService 适配器；所有方法必须复用 Pharmacy 外层
 * 事务连接，自身不开启新事务。确认调拨写一张源仓库 OUTBOUND 与一张目标仓库
 * INBOUND 操作，并为每个正批准项分别写出、入两条明细，保持成本守恒；任一写入
 * 失败由外层事务整体回滚。
 */
interface InventoryRequisitionTransferPort {

    /**
     * 创建申领单时的只读校验：全部物资必须存在且为 ACTIVE。不锁库存、不写任何表；
     * 任一物资缺失或未启用返回 ConflictException。
     */
    fun validateRequisitionMaterials(client: SqlClient, materialIds: List<String>): Future<Void?>

    /**
     * 审批时按稳定键 `(warehouse, material_id, lot_id)` 锁定源库存并原子增加
     * `locked_quantity`；源 `quantity` 不变。物资非 ACTIVE、批次不归属/已过期、
     * 可用量不足时返回 ConflictException。
     */
    fun reservePackageStock(client: SqlClient, command: RequisitionReserveCommand): Future<Void?>

    /**
     * 取消已审批单据时释放预留：锁定源库存并原子减少 `locked_quantity`，不产生
     * 任何库存操作。预留被异常破坏（locked_quantity 不足）时返回 ConflictException。
     */
    fun releasePackageReservation(client: SqlClient, command: RequisitionReleaseCommand): Future<Void?>

    /**
     * 确认调拨：锁全部源库存与目标库存（稳定顺序），写一张源 OUTBOUND 与一张目标
     * INBOUND 操作及逐项双向明细，扣减源库存并增加目标库存。目标库存行不存在时
     * 以并发安全 upsert/冲突重读创建。元数据标记 `source = PHARMACY_REQUISITION_TRANSFER`。
     */
    fun confirmReservedPackageTransfer(client: SqlClient, command: RequisitionTransferCommand): Future<RequisitionTransferResult>
}

// ========================================================================
//  014 药房采购：供应商收货批量入库端口
// ========================================================================

/**
 * 014 采购收货明细命令：每一条对应一条服务端生成的收货明细 ID，由已锁定的采购
 * 订单与收货请求构造。客户端不能传 lot_id、库存行、库存操作 ID 或订单状态；
 * 批次事实（批号/生产日期/有效期/生产企业）由库存端口验证并解析为权威 `lots`。
 */
data class PurchaseReceiptItemCommand(
    val receiptItemId: String,
    val materialId: String,
    val batchNo: String?,
    val productionDate: LocalDate?,
    val expiryDate: LocalDate?,
    val manufacturer: String?,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
)

/** 014 采购收货命令：仓库与供应商快照取自已锁定订单，审计收货人取自认证 principal。 */
data class PurchaseReceiptCommand(
    val warehouse: String,
    val supplierName: String,
    val purchaseOrderId: String,
    val purchaseOrderNo: String,
    val purchaseReceiptId: String,
    val receiptNo: String,
    val receivedBy: String,
    val items: List<PurchaseReceiptItemCommand>,
)

/** 014 采购收货明细结果：端口返回每条明细对应的批次、库存操作明细 ID 与成本。 */
data class PurchaseReceiptItemResult(
    val receiptItemId: String,
    val materialId: String,
    val batchNo: String?,
    val lotId: String?,
    val stockOperationDetailId: String,
    val unitCost: BigDecimal,
    val totalCost: BigDecimal,
)

data class PurchaseReceiptResult(
    val stockOperationId: String,
    val items: List<PurchaseReceiptItemResult>,
)

/**
 * 014 同连接内部端口：采购收货批量入库。
 *
 * 由 Aceso `Main.kt` 注入 StockService 适配器；必须复用 Pharmacy 外层事务连接，
 * 自身不开启新事务。一次收货只写一张 `INBOUND`、`CONFIRMED` 的 `stock_operations`
 * 及逐条 `stock_operation_details`，按稳定键解析/创建批次与目标库存行（唯一冲突
 * 后重读并比对事实），`quantity`/`total_cost` 累加且不改变 `locked_quantity`；
 * 元数据标记 `source = PHARMACY_PURCHASE_RECEIPT`。任一步失败由外层事务整体回滚。
 */
interface InventoryPurchaseReceiptPort {

    /**
     * 创建/编辑采购订单时的只读校验：全部物资必须存在且为 ACTIVE。不锁库存、不写
     * 任何表；任一物资缺失或未启用返回 ConflictException。
     */
    fun validatePurchaseMaterials(client: SqlClient, materialIds: List<String>): Future<Void?>

    fun confirmPackagePurchaseReceipt(client: SqlClient, command: PurchaseReceiptCommand): Future<PurchaseReceiptResult>
}
