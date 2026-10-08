package com.ovaphlow.crate.aceso

import com.ovaphlow.crate.dining.DiningRoutes
import com.ovaphlow.crate.healthcare.HealthcareRoutes
import com.ovaphlow.crate.inventories.InventoriesRoutes
import com.ovaphlow.crate.inventories.LotRoutes
import com.ovaphlow.crate.inventories.MaterialRoutes
import com.ovaphlow.crate.nursing.AssessmentRoutes
import com.ovaphlow.crate.nursing.NursingRoutes
import com.ovaphlow.crate.nursing.PlanRoutes
import com.ovaphlow.crate.nursing.ServicePeriodRoutes
import com.ovaphlow.crate.nursing.TaskExecutionRoutes
import com.ovaphlow.crate.nursing.TaskRoutes
import com.ovaphlow.crate.nursing.VisitScheduleRoutes
import com.ovaphlow.crate.pharmacy.DispenseRoutes
import com.ovaphlow.crate.pharmacy.InventoryInboundPort
import com.ovaphlow.crate.pharmacy.InventoryOutboundPort
import com.ovaphlow.crate.pharmacy.InventoryPurchaseReceiptPort
import com.ovaphlow.crate.pharmacy.InventoryRequisitionTransferPort
import com.ovaphlow.crate.pharmacy.MedicalOrderReader
import com.ovaphlow.crate.pharmacy.PharmacyRoutes
import com.ovaphlow.crate.pharmacy.PurchaseOrderRoutes
import com.ovaphlow.crate.pharmacy.RequisitionRoutes
import com.ovaphlow.crate.pharmacy.ReturnRoutes
import io.mockk.mockk
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpClient
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonObject
import io.vertx.junit5.VertxExtension
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.sqlclient.Pool
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * 权限自省端点护栏（**不访问数据库、不连真实服务**）。
 *
 * 锁三件事：
 * 1. 清单与真实接线共用同一个权限码常量——已接线的码必须在列（防止漏登记变成"死配置"）；
 * 2. 打卡路由的资源级选码映射（045）：按 task_type 取码，不外溢到其它任务类型；
 * 3. `GET /access/v1/permissions` 的形状：`permission_codes` 去重、`routes` 逐条可读；
 * 4. 046 §6.8：构造**真实**模块 Router 并枚举写路由，锁「声明 = 接线」不漂移。
 */
@ExtendWith(VertxExtension::class)
class DeclaredPermissionsTest {

    private lateinit var vertx: Vertx
    private lateinit var client: HttpClient
    private var serverPort = 0

    @BeforeEach
    fun setUp(vertx: Vertx) {
        this.vertx = vertx
        client = vertx.createHttpClient(HttpClientOptions().setKeepAlive(false))
        val server = vertx.createHttpServer()
            .requestHandler(AccessRoutes.create(vertx))
            .listen(0)
            .toCompletionStage()
            .toCompletableFuture()
            .get(5, TimeUnit.SECONDS)
        serverPort = server.actualPort()
    }

    @AfterEach
    fun tearDown() {
        client.close()
    }

    @Test
    fun `the declared list covers every wired permission code`() {
        val codes = DECLARED_PERMISSIONS.map { it.permissionCode }.toSet()

        assertTrue(codes.contains(NURSING_EXECUTE_PERMISSION), "护理执行码必须登记在清单里，否则角色页会把它标成未接线")
        assertTrue(codes.contains(REHAB_EXECUTE_PERMISSION), "康复活动码必须登记在清单里，否则角色页会把它标成未接线")
        assertEquals(
            setOf(
                NURSING_EXECUTE_PERMISSION,
                REHAB_EXECUTE_PERMISSION,
                NURSING_DOCUMENT_PERMISSION,
                HEALTH_MANAGE_PERMISSION,
                CLINICAL_WRITE_PERMISSION,
                ADMISSION_WRITE_PERMISSION,
                BED_MANAGE_PERMISSION,
                FEE_ITEM_MANAGE_PERMISSION,
                DINING_MANAGE_PERMISSION,
                INVENTORY_WRITE_PERMISSION,
                PHARMACY_DISPENSE_PERMISSION,
                BILLING_WRITE_PERMISSION,
                BASE_DATA_MANAGE_PERMISSION,
                ACCESS_MANAGE_PERMISSION,
            ),
            codes,
            "Aceso 路由接线必须与清单一致（新增接线时同步更新本断言）",
        )
        // 046：护理 3 条注入式 + 5 个域的 52 条集中式声明 = 55
        assertEquals(
            3,
            DECLARED_PERMISSIONS.count { it.wiring == PermissionWiring.INJECTED },
            "护理打卡/记录给药由 TaskExecutionRoutes 注入式门禁承载，必须标 INJECTED 且不重复注册",
        )
        assertEquals(
            setOf(
                Triple("POST", "/nursing/v1/executions/:id/administration", NURSING_EXECUTE_PERMISSION),
                Triple("PATCH", "/nursing/v1/executions/:id/status", NURSING_EXECUTE_PERMISSION),
                Triple("PATCH", "/nursing/v1/executions/:id/status", REHAB_EXECUTE_PERMISSION),
            ),
            DECLARED_PERMISSIONS.filter { it.wiring == PermissionWiring.INJECTED }
                .map { Triple(it.method, it.path, it.permissionCode) }
                .toSet(),
            "INJECTED 项必须与护理 3 条一一对应：集中式注册不得把它们重复挂一遍",
        )
        assertEquals(
            169,
            DECLARED_PERMISSIONS.count { it.wiring == PermissionWiring.CENTRAL },
            "046 52 + 047 的 27 + 048 的 17 + 049 的 16 + 050 的 pharmacy:dispense 4 + 052 的儿保 9 + 053 A2 筛查 1 + 053 B2 转诊 2 + 053 B1 诊断 1 + 053 A3 筛查判读 1 + 053 B3 疾病防控指导 1 + 054 A1 膳食摄入 1 + 053 D1 过敏 3 + 054 A4 过敏原/特殊膳食 3 + 054 A5 周报生成 1 + 054 B1 儿童康复 3 + 054 A4b 儿保过敏原 1 + 054 B2 训练执行 1 + 054 B3 里程碑 1 + 054 D2 儿科用药规则 2 + 054 B4 手册分发 2 + 054 C1 保育文书 2 + 054 E1 儿保套餐 4 + 054 C2 保育日程与巡查 5 + 054 E2 儿保结算 2 + 054 E3 儿保发票 2 + 054 F2 基础数据 4 + 054 F3 审计查询（**首条受门禁读路由**）1 = 169",
        )
        assertEquals(
            29,
            DECLARED_PERMISSIONS.count { it.permissionCode == NURSING_DOCUMENT_PERMISSION },
            "047 §4.1：nursing:document 29 条（护理文书 13 条 + 054 C1 保育文书 2 条 + 054 C2 保育日程与巡查 5 条 + healthcare 侧护理类 9 条）",
        )
        assertEquals(
            25,
            DECLARED_PERMISSIONS.count { it.permissionCode == HEALTH_MANAGE_PERMISSION },
            "048 §4.1：health:manage 15 条（体征 3 + 体检/结果 7 + 随访 3 + 慢病 2）+ 052 儿保 9 条 + 053 A2 筛查 1 = 25",
        )
        assertEquals(
            8,
            DECLARED_PERMISSIONS.count { it.permissionCode == ADMISSION_WRITE_PERMISSION },
            "049 §4.1：admission:write 8 条（患者 2 + 入住/离院/去世/养老 6）",
        )
        assertEquals(4, DECLARED_PERMISSIONS.count { it.permissionCode == BED_MANAGE_PERMISSION }, "049：bed:manage 4 条")
        assertEquals(
            4,
            DECLARED_PERMISSIONS.count { it.permissionCode == FEE_ITEM_MANAGE_PERMISSION },
            "049：fee_item:manage 4 条",
        )
        assertEquals(
            17,
            DECLARED_PERMISSIONS.count { it.permissionCode == PHARMACY_DISPENSE_PERMISSION },
            "046 发药/退药 11 + 050 领用申领/调拨 4 + 054 D2 儿科用药规则 2 = 17 条",
        )
        // 054 F2（Lead 裁定）：`base_data:manage` 由「仅 Nexus 承载」扩展为「Nexus + Aceso 双承载」——
        // F2 新增的 `/healthcare/v1/base-vaccination-types`、`/base-developmental-scales` 写路由是
        // **Aceso 侧路由**，Nexus 门禁覆盖不到，必须在 Aceso 集中声明（否则 4 条写路由裸奔）。
        // 049 D1 的原断言「Aceso 不得声明」据此**反转**；Nexus 侧仍保留自身门禁（`NEXUS_ENFORCED_PERMISSIONS` 不变）。
        assertEquals(
            4,
            DECLARED_PERMISSIONS.count { it.permissionCode == BASE_DATA_MANAGE_PERMISSION },
            "054 F2：Aceso 侧基础数据写路由 4 条（疫苗类型 POST/PUT + 发育量表 POST/PUT）",
        )
        // 054 F3：`access:manage` 此前只由 Nexus 承载（`/shared/v1/departments` 3 条）；
        // 054 新增 `GET /healthcare/v1/audit-logs` 后，Aceso 侧出现**第一条**受该码门禁的路由。
        assertEquals(
            1,
            DECLARED_PERMISSIONS.count { it.permissionCode == ACCESS_MANAGE_PERMISSION },
            "054 F3：Aceso 侧仅审计查询 1 条受 access:manage（Nexus 侧 3 条不在此清单内）",
        )
        // 054 F3：**全系统第一条被声明的读路由**。写路由全等断言只比对写方法（见 `writeMethodsForGate`），
        // 读路由**不在任何全等断言覆盖内**，故必须在此单列断言（method + path + permissionCode）。
        assertTrue(
            DECLARED_PERMISSIONS.any {
                it.method == "GET" &&
                    it.path == "/healthcare/v1/audit-logs" &&
                    it.permissionCode == ACCESS_MANAGE_PERMISSION
            },
            "054 F3：审计查询读路由必须被显式声明为 GET + /healthcare/v1/audit-logs + access:manage（046 D7 读路由门禁唯一例外）",
        )
        assertEquals(
            1,
            DECLARED_PERMISSIONS.count { it.method == "GET" },
            "054 F3：全系统目前只允许一条受门禁的读路由（审计查询）；新增受门禁读路由时须同步本断言与请求级 403 断言",
        )
    }

    @Test
    fun `the check-in route picks its code by task type`() {
        // 045：康复活动由康复岗或护理岗完成，其余任务类型只认护理执行码。
        assertEquals(
            listOf(REHAB_EXECUTE_PERMISSION, NURSING_EXECUTE_PERMISSION),
            executionPermissionCodesFor(REHABILITATION_TASK_TYPE),
            "康复活动必须允许康复执行码与护理执行码任一（严格一码会把现有 nursing:execute 持有者当场降权）",
        )
        listOf("NURSING", "MEDICATION", "TREATMENT", "LIVING_CARE", "HEALTH_EDUCATION", "OTHER").forEach { taskType ->
            assertEquals(
                DEFAULT_EXECUTION_PERMISSION_CODES,
                executionPermissionCodesFor(taskType),
                "$taskType 不是康复活动，只认护理执行码",
            )
        }
        assertEquals(
            DEFAULT_EXECUTION_PERMISSION_CODES,
            executionPermissionCodesFor(null),
            "读不到 task_type（记录不存在/查询失败）必须按最严处理，不借 403/404 差异泄露存在性",
        )
        assertTrue(
            executionPermissionCodesFor("NURSING").none { it == REHAB_EXECUTE_PERMISSION },
            "康复码不得外溢到其它任务类型：否则康复岗能直接打卡医嘱执行",
        )
    }

    @Test
    fun `the nexus gate list covers the authorization codes declared as wired by nexus`() {
        val nexusCodes = NEXUS_ENFORCED_PERMISSIONS.map { it.permissionCode }.toSet()

        assertEquals(
            setOf(ACCESS_MANAGE_PERMISSION, BASE_DATA_MANAGE_PERMISSION),
            nexusCodes,
            "Nexus 门禁码必须与清单一致（043 §4.1 冻结矩阵）",
        )
        assertEquals(
            nexusCodes,
            PERMISSION_CATALOG.filter { it.enforcedBy == "nexus" }.map { it.code }.toSet(),
            "目录里标 enforced_by=nexus 的条目必须真的在 Nexus 侧接线，否则角色页会展示一个假的已接线码",
        )
        assertTrue(
            NEXUS_ENFORCED_PERMISSIONS.all { it.path.startsWith("/shared/v1/") },
            "Nexus 门禁路由都在 /shared/v1/ 下：${NEXUS_ENFORCED_PERMISSIONS.map { it.path }}",
        )
    }

    @Test
    fun `the access endpoint exposes the declared permissions`() {
        val payload = get("/permissions")

        assertEquals(
            setOf(
                NURSING_EXECUTE_PERMISSION,
                REHAB_EXECUTE_PERMISSION,
                NURSING_DOCUMENT_PERMISSION,
                HEALTH_MANAGE_PERMISSION,
                CLINICAL_WRITE_PERMISSION,
                ADMISSION_WRITE_PERMISSION,
                BED_MANAGE_PERMISSION,
                FEE_ITEM_MANAGE_PERMISSION,
                DINING_MANAGE_PERMISSION,
                INVENTORY_WRITE_PERMISSION,
                PHARMACY_DISPENSE_PERMISSION,
                BILLING_WRITE_PERMISSION,
                BASE_DATA_MANAGE_PERMISSION,
                ACCESS_MANAGE_PERMISSION,
            ),
            payload.getJsonArray("permission_codes").map { it as String }.toSet(),
            "自省端点的 permission_codes 必须是清单里真实接线的全部码（去重）",
        )
        val routes = payload.getJsonArray("routes")
        assertEquals(172, routes.size(), "DECLARED_PERMISSIONS 172 条逐条列出：$routes")
        val first = routes.getJsonObject(0)
        assertEquals("POST", first.getString("method"))
        assertEquals("/nursing/v1/executions/:id/administration", first.getString("path"))
        assertEquals(NURSING_EXECUTE_PERMISSION, first.getString("permission_code"))
    }

    @Test
    fun `the catalog marks exactly the wired codes as wired`() {
        val catalogCodes = PERMISSION_CATALOG.map { it.code }.toSet()
        // 043："已接线"含两层——Aceso 路由（DECLARED_PERMISSIONS）与 Nexus 写门禁（NEXUS_ENFORCED_PERMISSIONS）
        val wiredCodes = DECLARED_PERMISSIONS.map { it.permissionCode }.toSet() +
            NEXUS_ENFORCED_PERMISSIONS.map { it.permissionCode }.toSet()
        assertTrue(
            catalogCodes.containsAll(wiredCodes),
            "每个已接线的码都必须在产品权限目录里，否则角色页选不到它：wired=$wiredCodes catalog=$catalogCodes",
        )

        val catalog = get("/permissions").getJsonArray("catalog")
        val entries = (0 until catalog.size()).map { catalog.getJsonObject(it) }
        assertEquals(PERMISSION_CATALOG.size, entries.size, "目录条目数与 PERMISSION_CATALOG 必须一致")
        val byCode = entries.associateBy { it.getString("code") }
        // 046：五个域接线后目录里所有码都已接线（Aceso 或 Nexus）；断言 wired 与真实接线集合**逐条**一致，
        // 比"至少留一个未接线码"更强，且不会因为"全部接线"而变成空断言。
        PERMISSION_CATALOG.forEach { entry ->
            assertEquals(
                wiredCodes.contains(entry.code),
                byCode[entry.code]?.getBoolean("wired"),
                "${entry.code} 的 wired 必须等于『真的接线了吗』（aceso/nexus 门禁），角色页据此显示已接线/未接线",
            )
        }
        assertTrue(
            entries.all { !it.getString("description").isNullOrBlank() },
            "每条目录都要有说明，角色页照着它选：$entries",
        )
        // 043：新增的 enforced_by 必须与 PERMISSION_CATALOG 一一对应（空串表示未接线）
        PERMISSION_CATALOG.forEach { entry ->
            assertEquals(
                entry.enforcedBy ?: "",
                byCode[entry.code]?.getString("enforced_by"),
                "${entry.code} 的 enforced_by 必须与清单一致",
            )
        }
        // 已接线的 Nexus 码必须能在 catalog.routes 里看到它拦的路由
        assertTrue(
            byCode[ACCESS_MANAGE_PERMISSION]!!.getJsonArray("routes").size() >= 5,
            "access:manage 必须列出它拦住的写路由，实际 ${byCode[ACCESS_MANAGE_PERMISSION]}",
        )
    }

    /**
     * 046 §6.8：构造**真实**模块 Router（mockk Pool + 空端口桩，不起服务、不连库），
     * 枚举写方法路由，锁「声明 = 接线」——新加一条写路由却忘了登记即测试失败。
     *
     * `Router.getRoutes()` 只返回**本路由器**注册的路由；Vert.x 不暴露挂载进来的子路由，
     * 所以对模块与它挂载的每个子路由分别枚举，再用各自的挂载前缀拼出外部完整路径。
     */
    @Test
    fun `the wired write routes match the real module routers`() {
        val pool = mockk<Pool>()
        val auth: Handler<RoutingContext> = Handler { it.next() }
        val declaredCentral = DECLARED_PERMISSIONS.filter { it.wiring == PermissionWiring.CENTRAL }

        // 054 前瞻修复（架构评审 §9 建议）：`writeRoutes` 只枚举写方法，而声明表**允许**含受门禁的读路由
        // （F3 的 `GET /healthcare/v1/audit-logs` 是 046 D7 读路由门禁的唯一例外）。若此处不过滤方法，
        // 一旦声明了读路由，写路由全等断言会在「声明完全正确」时假红。故写路由比对只取写方法。
        //
        // ⚠️ 过滤后**读路由不在任何全等断言覆盖内**：自省端点测试目前只校验 `routes.size()` 计数与
        // `permission_codes` 集合，**不校验 method/path**。因此 F3 落地时**必须**补单列断言
        // （`GET /healthcare/v1/audit-logs` + `access:manage`）与请求级 403 断言，并把 `routes.size()` +1。
        val writeMethodsForGate = setOf("POST", "PUT", "PATCH", "DELETE")

        fun declaredUnder(prefix: String): Set<Pair<String, String>> =
            declaredCentral
                .filter { it.path.startsWith(prefix) && it.method in writeMethodsForGate }
                .map { it.method to it.path }
                .toSet()

        // —— 膳食：本轮写路由全量接线 → 真实路由表与声明**完全一致** ——
        assertEquals(
            declaredUnder("/dining/v1"),
            writeRoutes(DiningRoutes.create(vertx, pool, null), "/dining/v1"),
            "膳食真实写路由必须与 DECLARED_PERMISSIONS 完全一致（漏登记或新裸奔写路由都会失败）",
        )

        // —— 物资：materials / lots / operations 全量接线（含三个挂载子路由）——
        val inventoryReal = buildSet {
            addAll(writeRoutes(InventoriesRoutes.create(vertx, pool), "/inventories/v1"))
            addAll(writeRoutes(MaterialRoutes.create(vertx, pool), "/inventories/v1/materials"))
            addAll(writeRoutes(LotRoutes.create(vertx, pool), "/inventories/v1/lots"))
        }
        assertEquals(
            declaredUnder("/inventories/v1"),
            inventoryReal,
            "物资真实写路由必须与 DECLARED_PERMISSIONS 完全一致",
        )

        // —— 药房：发药 / 退药 / 采购全量接线；护理站申领（§7 残项）按清单豁免 ——
        val pharmacyReal = buildSet {
            // 聚合路由自身的写路由（当前为 0 条；子路由以 `*` 通配挂载，会被 writeRoutes 排除）。
            // 054 BL2：新增直接挂在 PharmacyRoutes 聚合上的写段会自动进入本集合；若新增的是
            // **子路由**（如 `/xxx/*` 挂载），必须同时在此处枚举该子路由，否则门禁失效。
            addAll(
                writeRoutes(
                    PharmacyRoutes.create(
                        vertx,
                        pool,
                        mockk<MedicalOrderReader>(relaxed = true),
                        mockk<InventoryOutboundPort>(relaxed = true),
                        mockk<InventoryInboundPort>(relaxed = true),
                        mockk<InventoryRequisitionTransferPort>(relaxed = true),
                        mockk<InventoryPurchaseReceiptPort>(relaxed = true),
                        auth,
                    ),
                    "/pharmacy/v1",
                ),
            )
            addAll(
                writeRoutes(
                    DispenseRoutes.create(
                        vertx,
                        pool,
                        mockk<MedicalOrderReader>(relaxed = true),
                        mockk<InventoryOutboundPort>(relaxed = true),
                    ),
                    "/pharmacy/v1/dispenses",
                ),
            )
            addAll(
                writeRoutes(
                    ReturnRoutes.create(vertx, pool, mockk<InventoryInboundPort>(relaxed = true)),
                    "/pharmacy/v1/returns",
                ),
            )
            val (orderRouter, _) =
                PurchaseOrderRoutes.create(vertx, pool, mockk<InventoryPurchaseReceiptPort>(relaxed = true), auth)
            addAll(writeRoutes(orderRouter, "/pharmacy/v1/purchase-orders"))
            addAll(
                writeRoutes(
                    RequisitionRoutes.create(vertx, pool, mockk<InventoryRequisitionTransferPort>(relaxed = true), auth),
                    "/pharmacy/v1/requisitions",
                ),
            )
        }
        val declaredPharmacy = declaredUnder("/pharmacy/v1/")
        assertEquals(
            declaredPharmacy,
            pharmacyReal,
            "药房真实写路由必须与声明完全一致（050 起领用申领/调拨也接线，无残项豁免）",
        )

        // —— 护理：`/nursing/v1/*` 写路由全量接线（045 的 2 条 INJECTED + 047 的集中式）——
        // 护理子路由以 `/xxx/*` 挂载 + `post("/")`，实际请求**带尾斜杠**，故 keepTrailingSlash 保留。
        val nursingReal = buildSet {
            // 聚合路由自身的写路由（当前为 0 条；子路由以 `*` 通配挂载，会被 writeRoutes 排除）。
            // 054 BL2：054 B/C 链新增的 nursing 写段直接挂在 NursingRoutes 聚合上，故必须枚举聚合，
            // 否则「声明有、真实集合无」会让断言在声明完全正确时也失败。若新增的是**子路由**
            // （如 `/child-rehab/*` 挂载），必须同时在此处枚举该子路由。
            addAll(writeRoutes(NursingRoutes.create(vertx, pool), "/nursing/v1"))
            addAll(writeRoutes(ServicePeriodRoutes.create(vertx, pool), "/nursing/v1/periods", keepTrailingSlash = true))
            addAll(writeRoutes(AssessmentRoutes.create(vertx, pool), "/nursing/v1/assessments", keepTrailingSlash = true))
            addAll(writeRoutes(PlanRoutes.create(vertx, pool), "/nursing/v1/plans", keepTrailingSlash = true))
            addAll(writeRoutes(TaskRoutes.create(vertx, pool), "/nursing/v1/tasks", keepTrailingSlash = true))
            addAll(writeRoutes(VisitScheduleRoutes.create(vertx, pool), "/nursing/v1/visit-schedules", keepTrailingSlash = true))
            addAll(writeRoutes(TaskExecutionRoutes.create(vertx, pool), "/nursing/v1/executions", keepTrailingSlash = true))
        }
        val declaredNursing = DECLARED_PERMISSIONS
            .filter { it.path.startsWith("/nursing/v1") && it.method in writeMethodsForGate }
            .map { it.method to it.path }
            .toSet()
        assertEquals(
            declaredNursing,
            nursingReal,
            "护理域写路由必须与声明完全一致（含 045 的 2 条 INJECTED）：漏登记或新裸奔写路由都会失败",
        )

        // —— 临床 / 收费：本轮只接主写路由（healthcare 存量写路由多，§7 残项不逐一豁免），
        //    只锁『声明的必须真实存在』：接线当刻就暴露，且反向全集不在本轮范围 ——
        val healthcareReal = writeRoutes(HealthcareRoutes.create(vertx, pool), "/healthcare/v1")
        assertTrue(
            declaredUnder("/healthcare/v1").all { it in healthcareReal },
            "声明的临床/收费写路由必须真实存在（路径/方法改名会失败）：缺 ${declaredUnder("/healthcare/v1") - healthcareReal}",
        )

        // —— healthcare 侧护理类（护理记录/不良事件/交接班/复评）整组必须与声明一致 ——
        val nursingScopeMarkers =
            listOf("care-plan-revisions", "nursing-records", "nursing-incidents", "nursing-shift-handovers")
        fun inNursingScope(route: Pair<String, String>): Boolean =
            nursingScopeMarkers.any { marker -> route.second.contains(marker) }
        assertEquals(
            declaredUnder("/healthcare/v1").filter(::inNursingScope).toSet(),
            healthcareReal.filter(::inNursingScope).toSet(),
            "healthcare 侧护理类写路由整组必须与 nursing:document 声明一致（含 corrections/actions/close/items/receive）",
        )

        // —— healthcare 侧健康管理（体征/体检/随访/慢病）整组必须与声明一致 ——
        // 体征的 review/refer 归 `clinical:write`，其余 15 条归 `health:manage`；两码互相独立。
        val healthScopeMarkers = listOf("vital-signs", "health-checkup", "followup", "chronic-disease")
        fun inHealthScope(route: Pair<String, String>): Boolean =
            healthScopeMarkers.any { marker -> route.second.contains(marker) }
        assertEquals(
            declaredUnder("/healthcare/v1").filter(::inHealthScope).toSet(),
            healthcareReal.filter(::inHealthScope).toSet(),
            "healthcare 侧健康管理写路由整组必须与声明一致（15 条 health:manage + 3 条 clinical:write）",
        )

        // —— healthcare 侧主数据与登记（患者/床位/费用项/养老入住 + 入住类 encounter）整组一致 ——
        // 前四类路径前缀唯一，可整组全等；encounters 前缀过宽（含医嘱/护理/收费等），只精确锁 4 条入住类。
        val encounterAdmissionWrites = setOf(
            "POST" to "/healthcare/v1/encounters",
            "PUT" to "/healthcare/v1/encounters/:id",
            "PATCH" to "/healthcare/v1/encounters/:id/discharge",
            "PATCH" to "/healthcare/v1/encounters/:id/death",
        )
        fun inMasterDataScope(route: Pair<String, String>): Boolean =
            route.second.startsWith("/healthcare/v1/patients") ||
                route.second.startsWith("/healthcare/v1/beds") ||
                route.second.startsWith("/healthcare/v1/fee-items") ||
                route.second.startsWith("/healthcare/v1/elderly-admissions") ||
                route in encounterAdmissionWrites
        assertEquals(
            declaredUnder("/healthcare/v1").filter(::inMasterDataScope).toSet(),
            healthcareReal.filter(::inMasterDataScope).toSet(),
            "healthcare 侧患者/入住/床位/费用项写路由整组必须与声明一致（8 admission:write + 4 bed:manage + 4 fee_item:manage）",
        )

        // —— 054 E 链（儿保收费）：套餐 / 订阅 / 结算 / 发票 全等 ——
        // 054 M2（架构评审）：此前 `/healthcare/v1` 只有单向 `declared ⊆ real`，漏登记不会被抓；
        // 这里为 054 新增前缀补上整组全等断言，与 `/dining/v1`、`/nursing/v1` 口径一致。
        val childServiceScopePrefixes = listOf(
            "/healthcare/v1/child-service-packages",
            "/healthcare/v1/child-package-subscriptions",
            "/healthcare/v1/child-service-settlements",
            "/healthcare/v1/invoices",
        )
        fun inChildServiceScope(route: Pair<String, String>): Boolean =
            childServiceScopePrefixes.any { route.second.startsWith(it) }
        assertEquals(
            declaredUnder("/healthcare/v1").filter(::inChildServiceScope).toSet(),
            healthcareReal.filter(::inChildServiceScope).toSet(),
            "healthcare 侧儿保收费写路由整组必须与声明一致（套餐/订阅/结算/发票；漏登记或新裸奔写路由都会失败）",
        )

        // —— 054 F 链（管理员基础设施）：基础数据写路由全等 ——
        // 说明：`audit-logs` 的**读**路由（F3，046 D7 读路由门禁唯一例外）**不在此 scope 内**——
        // 本 scope 语义是「写路由整组全等」，混入读路由会让口径含糊。该读路由**只能**由 F3 落地时
        // 新增的**单列断言**把关（自省端点测试目前只校验 `routes.size()` 计数与 `permission_codes`
        // 集合，**不校验 method/path**）。F3 落地时同时需要：① 单列断言；② `routes.size()` 计数 +1。
        val baseDataScopePrefixes = listOf(
            "/healthcare/v1/base-vaccination-types",
            "/healthcare/v1/base-developmental-scales",
        )
        fun inBaseDataScope(route: Pair<String, String>): Boolean =
            baseDataScopePrefixes.any { route.second.startsWith(it) }
        assertEquals(
            declaredUnder("/healthcare/v1").filter(::inBaseDataScope).toSet(),
            healthcareReal.filter(::inBaseDataScope).toSet(),
            "healthcare 侧基础数据写路由整组必须与声明一致（F2；审计读路由不在本 scope，由 F3 单列断言覆盖；漏登记或新裸奔写路由都会失败）",
        )
    }

    /**
     * 枚举一个（子）路由上真实注册的写路由，并拼回外部完整路径。
     *
     * 通配挂载路由（path 含 `*`）与顶层 `BodyHandler`（path 为 null）不是业务写路由，排除。
     *
     * [keepTrailingSlash] 反映挂载方式：以星号紧贴前缀挂载（如 materials）的集合路径**不带**尾斜杠
     * （`/materials`），而以「前缀 + 斜杠 + 星号」挂载（如 periods）的集合路径**带**尾斜杠
     * （`/periods/`）。二者由实测确定，见 047 §6.6。
     */
    private fun writeRoutes(
        router: Router,
        mountPrefix: String,
        keepTrailingSlash: Boolean = false,
    ): Set<Pair<String, String>> {
        val writeMethods = setOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)
        return router.routes
            .asSequence()
            .flatMap { route ->
                val path = route.path
                if (path == null || path.contains("*")) {
                    emptySequence()
                } else {
                    val joined = mountPrefix + path
                    val full = if (keepTrailingSlash) joined else joined.removeSuffix("/")
                    // `methods()` 为 null 表示该路由匹配所有方法（如通配挂载/BodyHandler）——
                    // 它们不是具体业务写路由，排除。
                    val methods = route.methods() ?: emptySet()
                    methods.asSequence().mapNotNull { method ->
                        if (method in writeMethods) method.name() to full else null
                    }
                }
            }
            .toSet()
    }

    private fun get(path: String): JsonObject {
        val result = CompletableFuture<JsonObject>()
        client.request(HttpMethod.GET, serverPort, "127.0.0.1", path).onSuccess { request ->
            request.send().onSuccess { response ->
                response.body().onSuccess { body ->
                    assertEquals(200, response.statusCode(), "body=${body}")
                    result.complete(body.toJsonObject())
                }.onFailure(result::completeExceptionally)
            }.onFailure(result::completeExceptionally)
        }.onFailure(result::completeExceptionally)
        return result.get(10, TimeUnit.SECONDS)
    }
}
