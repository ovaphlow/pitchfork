package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.healthcare.DrugCatalogPort
import com.ovaphlow.crate.healthcare.DrugCatalogUnavailableException
import com.ovaphlow.crate.healthcare.HealthcareNotFoundException
import com.ovaphlow.crate.healthcare.MedicalOrderService
import com.ovaphlow.crate.nursing.ConflictException
import com.ovaphlow.crate.nursing.TaskService
import com.ovaphlow.crate.pharmacy.AllergyWarning
import com.ovaphlow.crate.pharmacy.DrugAllergyCheckPort
import com.ovaphlow.crate.pharmacy.DrugAllergyCheckResult
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.sqlclient.Pool

/**
 * 054 D3：把 053 既有药物过敏检查（`MedicalOrderService.checkDrugAllergy` →
 * `DrugAllergyChecker` 引擎）适配成 Pharmacy 侧端口，供**发药确认前**调用。
 *
 * 装配（`apps:aceso/Main.kt` 的 pharmacy 路由挂载处，一行）：
 * ```kotlin
 * drugAllergyCheckPort = pharmacyDrugAllergyCheckPort(pool, healthcareDrugCatalogPort(materialService)),
 * ```
 * 放在 `PharmacyRoutes.create(...)` 现有命名参数之后即可；不接线时发药确认不做检查、
 * 响应不追加 `allergy_warnings`（行为与接线前逐字一致）。
 *
 * ## 语义映射（054 L16：N18 向后兼容）
 *
 *  - 检查成功 → [DrugAllergyCheckResult]（`warnings` 为空即零误报）；
 *  - **域判定**（药品不在目录 `HealthcareNotFoundException` / 非药品或已停用 `ConflictException`）
 *    → 返回 `null`（**无法判定 → 不阻断发药**）。存量发药单可能绑定非药品物资，
 *    若把域判定当作失败会让新检查变成新的阻断点，违反 L16；
 *  - **基础设施故障**（目录端口未接线 `DrugCatalogUnavailableException`、连接/超时等）
 *    → **向上传播**（fail-closed）：检查跑不起来时绝不静默当作「无过敏」。
 */
internal fun pharmacyDrugAllergyCheckPort(
    pool: Pool,
    drugCatalogPort: DrugCatalogPort,
): DrugAllergyCheckPort {
    val medicalOrderService = MedicalOrderService(
        pool,
        TaskService(pool),
        drugCatalogPort = drugCatalogPort,
    )
    return object : DrugAllergyCheckPort {
        override fun check(patientId: String, materialId: String): Future<DrugAllergyCheckResult?> =
            medicalOrderService.checkDrugAllergy(patientId, materialId)
                .map { json -> toResult(json) }
                .recover { error ->
                    when (error) {
                        // 域判定：无可提示 → 不阻断（N18 向后兼容）
                        is HealthcareNotFoundException, is ConflictException -> Future.succeededFuture(null)
                        // 基础设施故障：fail-closed，不静默跳过安全提示
                        else -> Future.failedFuture(error)
                    }
                }
    }
}

/** healthcare 检查响应 → Pharmacy 侧投影（字段同名同义，仅做类型转换）。 */
private fun toResult(json: JsonObject): DrugAllergyCheckResult {
    val warnings = json.getJsonArray("warnings") ?: JsonArray()
    return DrugAllergyCheckResult(
        drugName = json.getString("drug_name"),
        drugCode = json.getString("drug_code"),
        warnings = warnings.map { element ->
            val warning = element as JsonObject
            AllergyWarning(
                severity = warning.getString("severity") ?: "",
                reason = warning.getString("reason") ?: "",
                source = warning.getString("source") ?: "",
                matched = warning.getString("matched") ?: "",
            )
        },
    )
}
