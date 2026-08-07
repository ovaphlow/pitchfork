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
)
