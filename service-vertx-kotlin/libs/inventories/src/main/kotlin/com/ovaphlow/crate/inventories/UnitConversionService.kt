package com.ovaphlow.crate.inventories

import com.ovaphlow.crate.database.DatabaseConfig
import com.ovaphlow.crate.database.gen.inventories.public_.tables.MaterialUnitSpecs
import com.ovaphlow.crate.database.gen.inventories.public_.tables.Materials
import io.vertx.core.Future
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.SqlClient
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 从 Vert.x 数值以原始十进制文本构造 [BigDecimal]。
 *
 * 禁止先取 Double 再经 BigDecimal.valueOf 转换：value.toString() 是数值的十进制最短
 * 文本，由该文本构造 BigDecimal 不引入二进制尾差；而 BigDecimal.valueOf(double) 会带入
 * double 的二进制精确值尾差。计划 015 要求所有金额/数量只经此入口进入换算。
 */
fun jsonDecimal(value: Any?): BigDecimal? = when (value) {
    null -> null
    is BigDecimal -> value
    else -> value.toString().toBigDecimalOrNull()
}

/**
 * 一条包装规格的只读快照（UnitConversionService 加载后供调用方使用）。
 */
data class UnitSpecSnapshot(
    val id: String,
    val inputUnit: String,
    val baseRatio: BigDecimal,
    val isDefault: Boolean,
    val status: String,
)

/**
 * 基础数量写入命令：仅由 [UnitConversionService] 构造，客户端不得提交任何这些字段。
 * 它是 StockService / InventoryConsumptionService 写入库存操作明细与基础结存的唯一事实来源。
 */
data class BaseQuantityCommand(
    val materialId: String,
    val unitSpecId: String,
    val inputQuantity: BigDecimal,
    val inputUnit: String,
    val baseQuantity: BigDecimal,
    val baseUnit: String,
    val conversionRatio: BigDecimal,
    val inputUnitCost: BigDecimal,
    val baseUnitCost: BigDecimal,
    val totalCost: BigDecimal,
    /** 该写入是否走当前默认规格（用于旧列 PACKAGE/SPLIT 投影） */
    val isDefaultSpec: Boolean,
)

/**
 * 统一计量换算服务 —— 计划 015 唯一允许把录入数量转换为基础数量的入口。
 *
 * 职责：
 *  1. 以 FOR UPDATE 锁定物资行与规格行（调用方按 material_id、unit_spec_id 稳定排序
 *     逐项调用，避免死锁）；
 *  2. 校验物资状态 / 计量模型状态（ACTIVE）、规格状态（ACTIVE）、输入数量为正、成本非负；
 *  3. 精确乘法换算并验证基础精度：结果小数位不得超过物资 base_quantity_scale（等价于
 *     RoundingMode.UNNECESSARY，绝不静默四舍五入或进位）；
 *  4. 生成不可变快照：input_quantity / input_unit / conversion_ratio / base_quantity /
 *     base_unit / input_unit_cost / base_unit_cost / total_cost。
 *
 * 本服务不开启事务，所有查询在调用方外层事务连接上执行；任一步失败由调用方整体回滚。
 */
class UnitConversionService(
    private val ctx: DSLContext = DatabaseConfig.createDSL(),
) {
    private val log = LoggerFactory.getLogger(UnitConversionService::class.java)

    /** 成本金额允许的小数位（NUMERIC(24,8)） */
    private val costScale = 8

    /**
     * 新写入路径：以调用方指定的活动规格换算。
     *
     * @throws NotFoundException 物资或规格不存在
     * @throws ConflictException 物资停用 / 迁移阻断 / 无计量模型 / 规格已停用
     * @throws IllegalArgumentException 输入数量或成本非法、换算结果不符合基础精度
     */
    fun convert(
        client: SqlClient,
        materialId: String,
        unitSpecId: String,
        inputQuantity: BigDecimal,
        inputUnitCost: BigDecimal,
    ): Future<BaseQuantityCommand> =
        loadMaterial(client, materialId).compose { material ->
            loadSpec(client, materialId, unitSpecId).compose { spec ->
                Future.succeededFuture(toCommand(material, spec, inputQuantity, inputUnitCost))
            }
        }

    /**
     * 旧 PACKAGE 端口路径：显式解析该物资当前默认包装规格（不接收客户端比率），
     * 换算输入数量为默认规格。没有默认规格 / 规格停用 / 物资阻断时返回业务错误，
     * 绝不回退为小数包装扣减。
     */
    fun resolvePackagePort(
        client: SqlClient,
        materialId: String,
        packageQuantity: BigDecimal,
        packageUnitCost: BigDecimal,
    ): Future<BaseQuantityCommand> =
        loadMaterial(client, materialId).compose { material ->
            loadDefaultSpec(client, materialId).compose { spec ->
                Future.succeededFuture(toCommand(material, spec, packageQuantity, packageUnitCost))
            }
        }

    /**
     * 旧 SPLIT 输入路径：显式解析该物资基础单位规格（is_base_unit、ACTIVE，
     * base_ratio = 1），把基础单位数量换算为基础数量，不再向上取整包装数。
     * 成本语义保持旧算法：每基础单位成本 = 包装平均成本 / 默认包装规格 base_ratio，
     * 使 total_cost 与旧 splitQuantity/splitRatio 扣减一致。没有基础规格或默认规格
     * 时返回业务错误（旧输入无法精确映射即拒绝）。
     */
    fun resolveSplitPort(
        client: SqlClient,
        materialId: String,
        baseQuantity: BigDecimal,
        packageAvgUnitCost: BigDecimal,
    ): Future<BaseQuantityCommand> =
        loadMaterial(client, materialId).compose { material ->
            loadBaseSpec(client, materialId).compose { baseSpec ->
                loadDefaultSpec(client, materialId).compose { defaultSpec ->
                    val ratio = defaultSpec.baseRatio
                    if (ratio <= BigDecimal.ZERO) {
                        Future.failedFuture(
                            IllegalArgumentException("material $materialId has invalid default spec ratio"),
                        )
                    } else {
                        val inputUnitCost = packageAvgUnitCost.divide(ratio, costScale, RoundingMode.HALF_UP)
                        Future.succeededFuture(toCommand(material, baseSpec, baseQuantity, inputUnitCost))
                    }
                }
            }
        }

    /** 加载当前默认活动规格；不存在时返回 ConflictException（旧端口不允许无默认规格写入） */
    private fun loadDefaultSpec(client: SqlClient, materialId: String): Future<UnitSpecSnapshot> {
        val query = ctx.select(
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.ID.`as`("spec_id"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.INPUT_UNIT.`as`("spec_input_unit"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.BASE_RATIO.`as`("spec_base_ratio"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.IS_DEFAULT.`as`("spec_is_default"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.STATUS.`as`("spec_status"),
        )
            .from(MaterialUnitSpecs.MATERIAL_UNIT_SPECS)
            .where(
                MaterialUnitSpecs.MATERIAL_UNIT_SPECS.MATERIAL_ID.eq(materialId)
                    .and(MaterialUnitSpecs.MATERIAL_UNIT_SPECS.IS_DEFAULT.eq(true))
                    .and(MaterialUnitSpecs.MATERIAL_UNIT_SPECS.STATUS.eq("ACTIVE")),
            )
            .forUpdate()
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(
                        ConflictException("material $materialId has no active default unit spec"),
                    )
                } else {
                    Future.succeededFuture(toSpecSnapshot(rows.iterator().next()))
                }
            }
    }

    /** 锁定并加载物资行，校验 ACTIVE 与计量模型状态 */
    private fun loadMaterial(client: SqlClient, materialId: String): Future<UnitMaterial> {
        val query = ctx.select(
            Materials.MATERIALS.STATUS.`as`("material_status"),
            Materials.MATERIALS.UNIT_MODEL_STATUS.`as`("material_unit_model_status"),
            Materials.MATERIALS.BASE_UNIT.`as`("material_base_unit"),
            Materials.MATERIALS.BASE_QUANTITY_SCALE.`as`("material_base_quantity_scale"),
        )
            .from(Materials.MATERIALS)
            .where(Materials.MATERIALS.ID.eq(materialId))
            .forUpdate()
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(NotFoundException("material not found: $materialId"))
                }
                val row = rows.iterator().next()
                val materialStatus = row.getValue(0)?.toString()
                val modelStatus = row.getValue(1)?.toString() ?: "LEGACY"
                if (materialStatus != "ACTIVE") {
                    return@compose Future.failedFuture(
                        ConflictException("material $materialId is not ACTIVE"),
                    )
                }
                when (modelStatus) {
                    "MIGRATION_BLOCKED" -> return@compose Future.failedFuture(
                        ConflictException("material $materialId is migration blocked; no writes allowed"),
                    )
                    "ACTIVE" -> {}
                    else -> return@compose Future.failedFuture(
                        ConflictException("material $materialId has no active unit model"),
                    )
                }
                val baseUnit = row.getValue(2)?.toString()
                if (baseUnit.isNullOrBlank()) {
                    return@compose Future.failedFuture(
                        ConflictException("material $materialId has no base unit"),
                    )
                }
                val scale = (row.getValue(3) as? Number)?.toInt() ?: 0
                Future.succeededFuture(
                    UnitMaterial(
                        id = materialId,
                        status = materialStatus,
                        baseUnit = baseUnit,
                        baseQuantityScale = scale,
                    ),
                )
            }
    }

    /** 锁定并加载规格行，校验归属与状态 */
    private fun loadSpec(client: SqlClient, materialId: String, unitSpecId: String): Future<UnitSpecSnapshot> {
        val query = ctx.select(
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.ID.`as`("spec_id"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.INPUT_UNIT.`as`("spec_input_unit"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.BASE_RATIO.`as`("spec_base_ratio"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.IS_DEFAULT.`as`("spec_is_default"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.STATUS.`as`("spec_status"),
        )
            .from(MaterialUnitSpecs.MATERIAL_UNIT_SPECS)
            .where(
                MaterialUnitSpecs.MATERIAL_UNIT_SPECS.ID.eq(unitSpecId)
                    .and(MaterialUnitSpecs.MATERIAL_UNIT_SPECS.MATERIAL_ID.eq(materialId)),
            )
            .forUpdate()
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    return@compose Future.failedFuture(
                        NotFoundException("unit spec not found: $unitSpecId for material $materialId"),
                    )
                }
                val spec = toSpecSnapshot(rows.iterator().next())
                if (spec.status != "ACTIVE") {
                    return@compose Future.failedFuture(
                        ConflictException("unit spec $unitSpecId is not ACTIVE"),
                    )
                }
                Future.succeededFuture(spec)
            }
    }

    /** 加载基础单位规格（ratio=1）；不存在时返回 ConflictException */
    private fun loadBaseSpec(client: SqlClient, materialId: String): Future<UnitSpecSnapshot> {
        val query = ctx.select(
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.ID.`as`("spec_id"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.INPUT_UNIT.`as`("spec_input_unit"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.BASE_RATIO.`as`("spec_base_ratio"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.IS_DEFAULT.`as`("spec_is_default"),
            MaterialUnitSpecs.MATERIAL_UNIT_SPECS.STATUS.`as`("spec_status"),
        )
            .from(MaterialUnitSpecs.MATERIAL_UNIT_SPECS)
            .where(
                MaterialUnitSpecs.MATERIAL_UNIT_SPECS.MATERIAL_ID.eq(materialId)
                    .and(MaterialUnitSpecs.MATERIAL_UNIT_SPECS.IS_BASE_UNIT.eq(true))
                    .and(MaterialUnitSpecs.MATERIAL_UNIT_SPECS.STATUS.eq("ACTIVE")),
            )
            .forUpdate()
        return client.preparedQuery(DatabaseConfig.sql(query))
            .execute(DatabaseConfig.tuple(query))
            .compose { rows: RowSet<Row> ->
                if (rows.size() == 0) {
                    Future.failedFuture(
                        ConflictException("material $materialId has no active base unit spec"),
                    )
                } else {
                    Future.succeededFuture(toSpecSnapshot(rows.iterator().next()))
                }
            }
    }

    private fun toSpecSnapshot(row: Row): UnitSpecSnapshot =
        UnitSpecSnapshot(
            id = row.getValue(0)?.toString() ?: "",
            inputUnit = row.getValue(1)?.toString() ?: "",
            baseRatio = jsonDecimal(row.getValue(2)) ?: BigDecimal.ZERO,
            isDefault = row.getValue(3) as? Boolean ?: false,
            status = row.getValue(4)?.toString() ?: "",
        )

    private fun toCommand(
        material: UnitMaterial,
        spec: UnitSpecSnapshot,
        inputQuantity: BigDecimal,
        inputUnitCost: BigDecimal,
    ): BaseQuantityCommand {
        if (inputQuantity <= BigDecimal.ZERO)
            throw IllegalArgumentException("input quantity must be positive")
        if (inputUnitCost < BigDecimal.ZERO)
            throw IllegalArgumentException("input unit cost must not be negative")
        if (inputUnitCost.stripTrailingZeros().scale() > costScale)
            throw IllegalArgumentException("input unit cost exceeds precision of $costScale decimals")
        val ratio = spec.baseRatio
        if (ratio <= BigDecimal.ZERO)
            throw IllegalArgumentException("unit spec ${spec.id} has invalid base ratio")

        // 精确乘法：BigDecimal.multiply 不产生精度损失
        val base = inputQuantity.multiply(ratio)
        if (base <= BigDecimal.ZERO)
            throw IllegalArgumentException("converted base quantity must be positive")

        // 基础精度校验：等价于 RoundingMode.UNNECESSARY —— 结果小数位不得超过物资允许精度，
        // 不允许静默四舍五入或进位。如片/粒类（scale=0）输入 0.5 片即拒绝。
        val effectiveScale = base.stripTrailingZeros().scale().coerceAtLeast(0)
        if (effectiveScale > material.baseQuantityScale)
            throw IllegalArgumentException(
                "converted base quantity exceeds material precision of ${material.baseQuantityScale} decimals",
            )

        val totalCost = inputUnitCost.multiply(inputQuantity)
        if (totalCost.stripTrailingZeros().scale() > costScale)
            throw IllegalArgumentException("total cost exceeds precision of $costScale decimals")
        // base_unit_cost 只用于计算/展示，按约定成本精度明确量化（NUMERIC(24,8)）
        val baseUnitCost = inputUnitCost.divide(ratio, costScale, RoundingMode.HALF_UP)

        return BaseQuantityCommand(
            materialId = material.id,
            unitSpecId = spec.id,
            inputQuantity = inputQuantity,
            inputUnit = spec.inputUnit,
            baseQuantity = base,
            baseUnit = material.baseUnit,
            conversionRatio = ratio,
            inputUnitCost = inputUnitCost,
            baseUnitCost = baseUnitCost,
            totalCost = totalCost,
            isDefaultSpec = spec.isDefault,
        )
    }

    private data class UnitMaterial(
        val id: String,
        val status: String,
        val baseUnit: String,
        val baseQuantityScale: Int,
    )
}
