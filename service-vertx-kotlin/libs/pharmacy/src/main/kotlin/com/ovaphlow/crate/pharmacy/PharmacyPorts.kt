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

    /**
     * 025 存量自由文本医嘱一次性补绑目录药品：在调用方已锁定的医嘱上合并写入
     * `order_details.material_id/material_code/material_name` 与审计
     * `material_bound_by/material_bound_at`（成对，操作人必须非空）。
     *
     * 必须复用 Pharmacy 外层事务连接；医嘱不存在返回 404，已绑定返回 409，
     * 任何失败由外层事务整体回滚（发药单与库存出库一并撤销）。
     *
     * 默认实现 fail-closed：未接线（例如未改造的集成测试装配）时显式失败，
     * 绝不静默跳过补绑——否则会产出无绑定发药单，重回本计划要修的根因。
     */
    fun bindDrugMaterial(
        client: SqlClient,
        medicalOrderId: String,
        materialId: String,
        materialCode: String?,
        materialName: String?,
        operator: String,
    ): Future<Void?> =
        Future.failedFuture(
            DrugCatalogUnavailableException("MedicalOrderReader.bindDrugMaterial is not configured"),
        )
}

/**
 * 025 药品目录同连接只读端口（Pharmacy 侧）。
 *
 * Pharmacy 不依赖 healthcare/inventories 库：端口在本库内定义、由 Aceso `Main.kt`
 * 注入适配器，校验用法与 healthcare 侧一致（药品 = `category = '药品'` 且 ACTIVE）。
 */
interface DrugCatalogPort {

    /** 按 id 读取目录物资快照；不存在返回 null，由调用方映射 404。必须复用外层事务连接。 */
    fun findDrugMaterial(client: SqlClient, materialId: String): Future<DrugCatalogMaterial?>
}

/** 025 目录药品快照（Pharmacy 侧） */
data class DrugCatalogMaterial(
    val id: String,
    val code: String?,
    val name: String?,
    val spec: String?,
    val baseUnit: String?,
    val status: String?,
    val category: String?,
)

/**
 * 药品目录端口未注入 / 未实现：部署或装配错误，必须 fail-closed。
 * 路由映射为 **503**，不得降级为「跳过目录校验 / 跳过补绑」。
 */
class DrugCatalogUnavailableException(message: String) : Exception(message)

/** 025 补绑路径缺少可信操作人身份：路由映射为 **401**，不得写 null 审计 */
class UnauthorizedException(message: String) : Exception(message)

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
    /** 护士核对审计：发药事务校验要求已核对，未核对为 null */
    val nurseCheckedBy: String?,
    /** 护士核对时间：与 nurseCheckedBy 成对出现 */
    val nurseCheckedAt: OffsetDateTime?,
    /**
     * 025 医嘱绑定的目录药品（`order_details.material_id` 的受控投影）：
     * 存量自由文本医嘱为 null，表示需要药房在创建发药单时一次性补绑。
     */
    val materialId: String? = null,
    /** 目录药品编码快照（`order_details.material_code`），未绑定为 null */
    val materialCode: String? = null,
    /** 目录药品名快照（`order_details.material_name`），未绑定为 null */
    val materialName: String? = null,
)

/** 016 基础数量出库命令：quantity 是物资基础数量，单位由服务端写入库存明细快照。 */
data class OutboundCommand(
    val warehouse: String,
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
    val note: String?,
)

/** 016 出库结果：库存操作明细 ID、实际批次和每基础单位成本。 */
data class OutboundResult(
    val stockOperationDetailId: String,
    val lotId: String?,
    val unitCost: BigDecimal,
)

/** 012 退药回库命令：物资、批次、成本均由原发药明细推导。 */
data class InboundCommand(
    val warehouse: String,
    val materialId: String,
    val lotId: String?,
    val quantity: BigDecimal,
    val unitCost: BigDecimal,
    val note: String?,
)

data class InboundResult(
    val stockOperationDetailId: String,
    val lotId: String?,
    val unitCost: BigDecimal,
)

/**
 * 011 同连接内部端口：基础数量库存出库。
 *
 * 由 Aceso `Main.kt` 注入 StockService 适配器；必须在 Pharmacy 外层事务连接内调用，
 * 与药房单状态变更同事务提交或回滚。
 */
interface InventoryOutboundPort {
    /** 在创建发药单前只读校验基础数量出库条件。不得扣减库存或写库存操作。 */
    fun validateOutbound(client: SqlClient, command: OutboundCommand): Future<Void?>

    fun confirmOutbound(client: SqlClient, command: OutboundCommand): Future<OutboundResult>
}

/**
 * 012 同连接内部端口：退药基础数量回库。
 * 使用库存 INBOUND 操作并通过 metadata.source 标记 PHARMACY_RETURN。
 */
interface InventoryInboundPort {
    fun confirmInbound(client: SqlClient, command: InboundCommand): Future<InboundResult>
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
 * 013 同连接内部端口：申领预留、释放与整单双仓调拨。
 *
 * 由 Aceso `Main.kt` 注入 StockService 适配器；所有方法必须复用 Pharmacy 外层
 * 事务连接，自身不开启新事务。确认调拨写一张源仓库 OUTBOUND 与一张目标仓库
 * INBOUND 操作，并为每个正批准项分别写出、入两条明细，保持成本守恒；任一写入
 * 失败由外层事务整体回滚。数量全部为基础数量。
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
    fun reserveStock(client: SqlClient, command: RequisitionReserveCommand): Future<Void?>

    /**
     * 取消已审批单据时释放预留：锁定源库存并原子减少 `locked_quantity`，不产生
     * 任何库存操作。预留被异常破坏（locked_quantity 不足）时返回 ConflictException。
     */
    fun releaseReservation(client: SqlClient, command: RequisitionReleaseCommand): Future<Void?>

    /**
     * 确认调拨：锁全部源库存与目标库存（稳定顺序），写一张源 OUTBOUND 与一张目标
     * INBOUND 操作及逐项双向明细，扣减源库存并增加目标库存。目标库存行不存在时
     * 以并发安全 upsert/冲突重读创建。元数据标记 `source = PHARMACY_REQUISITION_TRANSFER`。
     */
    fun confirmReservedTransfer(client: SqlClient, command: RequisitionTransferCommand): Future<RequisitionTransferResult>
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
 * 后重读并比对事实），数量与成本均为基础口径。任一步失败由外层事务整体回滚。
 */
interface InventoryPurchaseReceiptPort {

    /**
     * 创建/编辑采购订单时的只读校验：全部物资必须存在且为 ACTIVE。不锁库存、不写
     * 任何表；任一物资缺失或未启用返回 ConflictException。
     */
    fun validatePurchaseMaterials(client: SqlClient, materialIds: List<String>): Future<Void?>

    fun confirmPurchaseReceipt(client: SqlClient, command: PurchaseReceiptCommand): Future<PurchaseReceiptResult>
}
