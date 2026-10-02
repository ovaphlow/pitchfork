package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router

/**
 * 一条"路由需要哪个权限码"的声明。
 *
 * 权限码的权威是 Nexus 角色目录；这里声明的是**产品侧把它们接到了哪些路由上**。
 */
internal data class DeclaredPermission(
    val method: String,
    val path: String,
    val permissionCode: String,
)

/**
 * Aceso 已真实接线的权限码清单（fail-closed 判定）。
 *
 * 角色管理页用它把"还没有任何接口在用"的权限码标出来，避免继续堆积死配置。
 * **新增受保护路由时必须同时加到这里**：接线与清单共用同一个权限码常量，
 * 漂移由 `DeclaredPermissionsTest` 兜底。
 */
internal val DECLARED_PERMISSIONS: List<DeclaredPermission> = listOf(
    DeclaredPermission("POST", "/nursing/v1/executions/:id/administration", NURSING_EXECUTE_PERMISSION),
    DeclaredPermission("PATCH", "/nursing/v1/executions/:id/status", NURSING_EXECUTE_PERMISSION),
)

/** 040：产品声明的权限码目录条目。 */
internal data class PermissionCatalogEntry(
    val code: String,
    val description: String,
)

internal const val PHARMACY_DISPENSE_PERMISSION = "pharmacy:dispense"
internal const val BILLING_WRITE_PERMISSION = "billing:write"

/**
 * 产品权限目录：Aceso 声明的全部权限码，含**尚未接线**的码。
 *
 * 放量协议要求"先建角色再接线"，所以角色管理页必须能选到还没接线的码；
 * 它同时是"只能从清单里选"的唯一来源，从源头杜绝自由文本权限码。
 * 每个已接线的码都必须出现在这里，漂移由 `DeclaredPermissionsTest` 兜住。
 */
internal val PERMISSION_CATALOG: List<PermissionCatalogEntry> = listOf(
    PermissionCatalogEntry(NURSING_EXECUTE_PERMISSION, "护理执行（记录给药、更新执行状态）"),
    PermissionCatalogEntry(PHARMACY_DISPENSE_PERMISSION, "发药确认"),
    PermissionCatalogEntry(BILLING_WRITE_PERMISSION, "账单写操作（生成、加项、红冲、结算）"),
)

/**
 * 只读自省端点：`GET /crate-api/access/v1/permissions`。
 *
 * 不属于任何业务域，只回答"这套部署里哪些权限码真的接上了"；受默认拒绝总闸保护（需登录）。
 */
internal object AccessRoutes {

    fun create(vertx: Vertx): Router {
        val router = Router.router(vertx)
        router.get("/permissions").handler { ctx ->
            val routes = JsonArray()
            DECLARED_PERMISSIONS.forEach { declared ->
                routes.add(
                    JsonObject()
                        .put("method", declared.method)
                        .put("path", declared.path)
                        .put("permission_code", declared.permissionCode),
                )
            }
            val wired = DECLARED_PERMISSIONS.map { it.permissionCode }.toSet()
            val catalog = JsonArray()
            PERMISSION_CATALOG.forEach { entry ->
                val entryRoutes = JsonArray()
                DECLARED_PERMISSIONS
                    .filter { it.permissionCode == entry.code }
                    .forEach { declared ->
                        entryRoutes.add(
                            JsonObject().put("method", declared.method).put("path", declared.path),
                        )
                    }
                catalog.add(
                    JsonObject()
                        .put("code", entry.code)
                        .put("description", entry.description)
                        .put("wired", wired.contains(entry.code))
                        .put("routes", entryRoutes),
                )
            }
            ctx.json(
                JsonObject()
                    .put("permission_codes", JsonArray(DECLARED_PERMISSIONS.map { it.permissionCode }.distinct()))
                    .put("catalog", catalog)
                    .put("routes", routes),
            )
        }
        return router
    }
}
