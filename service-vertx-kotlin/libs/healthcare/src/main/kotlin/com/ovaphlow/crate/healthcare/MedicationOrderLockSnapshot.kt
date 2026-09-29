package com.ovaphlow.crate.healthcare

import io.vertx.core.json.JsonObject
import java.time.OffsetDateTime

/** 011 药房端口锁读快照：只含受控字段，由 App 编排层转换为 Pharmacy 端口契约类型 */
data class MedicationOrderLockSnapshot(
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
    /** 护士核对审计：未核对为 null，已核对为认证主体 userId */
    val nurseCheckedBy: String?,
    /** 护士核对时间：与 nurseCheckedBy 成对出现 */
    val nurseCheckedAt: OffsetDateTime?,
    /**
     * 025 医嘱-药品目录绑定快照（`order_details.material_id` 的受控投影）：
     * 存量自由文本医嘱为 null，由药房创建发药单时一次性补绑。
     */
    val materialId: String? = null,
    /** 目录药品编码快照（`order_details.material_code`），未绑定为 null */
    val materialCode: String? = null,
    /** 目录药品名快照（`order_details.material_name`），未绑定为 null */
    val materialName: String? = null,
)
