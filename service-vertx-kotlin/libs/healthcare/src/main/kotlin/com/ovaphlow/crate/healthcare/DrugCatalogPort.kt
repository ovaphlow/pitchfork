package com.ovaphlow.crate.healthcare

import io.vertx.core.Future
import io.vertx.sqlclient.SqlClient

/**
 * 025 药品目录同连接只读端口。
 *
 * 药品目录即 `inventories.materials` 中 `category = '药品'` 的记录（方案 A），
 * 不新建第二套药品主表。端口由 Aceso `Main.kt` 用 `MaterialService` 适配注入；
 * 调用必须复用外层事务连接，禁止端口内部重新取 Pool 或开启新事务。
 */
interface DrugCatalogPort {

    /**
     * 按 id 读取目录物资快照；物资不存在返回 null，由调用方映射 404。
     * 只读不锁行、不写任何表，不判定 `category`/`status`（由调用方判定业务错误码）。
     */
    fun findDrugMaterial(client: SqlClient, materialId: String): Future<DrugCatalogMaterial?>
}

/** 025 目录药品快照：字段固定，不含库存、批次或成本细节 */
data class DrugCatalogMaterial(
    val id: String,
    /** 目录编码（`materials.code`，唯一），作为稳定键的展示快照 */
    val code: String?,
    /** 目录药品名（`materials.name`），服务端以此覆写 `drug_name`/`material_name` */
    val name: String?,
    val spec: String?,
    /** 基础单位快照（015/016 单一基础单位契约） */
    val baseUnit: String?,
    val status: String?,
    val category: String?,
)

/**
 * 药品目录端口未注入 / 不可用：属于部署配置错误，必须 fail-closed。
 *
 * 路由映射为 **503**；绝不能降级为「跳过目录校验」——那会把本计划要修的根因
 * （医生可开立药房不存在的药）重新放回来。
 */
class DrugCatalogUnavailableException(message: String) : Exception(message)
