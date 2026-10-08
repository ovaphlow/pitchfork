package com.ovaphlow.crate.dining

import io.mockk.every
import io.mockk.mockk
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.http.HttpMethod
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.junit5.VertxExtension
import io.vertx.junit5.VertxTestContext
import io.vertx.sqlclient.Pool
import io.vertx.sqlclient.PreparedQuery
import io.vertx.sqlclient.Row
import io.vertx.sqlclient.RowIterator
import io.vertx.sqlclient.RowSet
import io.vertx.sqlclient.Tuple
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.util.Collections

/**
 * 054 A1：`dining_dishes.nutrition` / `allergens`（V601 新增列）的**不连数据库**读写护栏。
 *
 * 验收 §8.1 第 2 项要求「`dining_dishes.nutrition/allergens` 可写可读」；菜品库原无营养列，
 * 若 `DishService` 的白名单/`toJson` 不带上新列，接口层就既写不进也读不出。
 * 本测试用 mockk 桩 `Pool` 锁定：
 *  1. `PUT /dishes/:id` 的 UPDATE 语句包含 `nutrition` 与 `allergens` 两列，
 *     且绑定参数里带上了营养成分 JSONB 与过敏原数组；
 *  2. `POST /dishes` 的 INSERT 同样带上两列；
 *  3. 读路径 `toJson` 把两列透出（`nutrition` 对象 / `allergens` 数组，缺省空数组）。
 */
@ExtendWith(VertxExtension::class)
class DishNutritionRoutesTest {

    private lateinit var pool: Pool
    private lateinit var prepared: PreparedQuery<RowSet<Row>>

    private val executed: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val updateTuples: MutableList<Tuple> = Collections.synchronizedList(mutableListOf())

    @BeforeEach
    fun setUp() {
        executed.clear()
        updateTuples.clear()

        pool = mockk()
        prepared = mockk()
        every { pool.preparedQuery(any<String>()) } answers { record(firstArg()); prepared }
        every { pool.preparedQuery(any<String>(), any()) } answers { record(firstArg()); prepared }
        every { prepared.execute(any<Tuple>()) } answers {
            val sql = executed.last()
            when {
                sql.contains("update dining.dining_dishes") -> {
                    updateTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("insert into dining.dining_dishes") -> {
                    updateTuples.add(firstArg())
                    Future.succeededFuture(rowSet(rowCount = 1))
                }
                sql.contains("from dining.dining_dishes") -> Future.succeededFuture(rowSet(dishRow()))
                else -> Future.succeededFuture(rowSet())
            }
        }
    }

    @Test
    fun `PUT菜品写入nutrition与allergens并可从读路径取回`(vertx: Vertx, ctx: VertxTestContext) {
        put(vertx, dishBody()) { status, body ->
            ctx.verify {
                assertEquals(200, status, "更新必须 200，实际: ${body.encode()}")

                val updateSql = executed.first { it.contains("update dining.dining_dishes") }
                assertTrue(updateSql.contains("nutrition"), "UPDATE 必须写 nutrition，实际: $updateSql")
                assertTrue(updateSql.contains("allergens"), "UPDATE 必须写 allergens，实际: $updateSql")

                val tuple = updateTuples.first()
                val values = (0 until tuple.size()).mapNotNull { tuple.getValue(it) }
                val boundNutrition = values.filterIsInstance<JsonObject>().firstOrNull()
                val boundAllergens = values.filterIsInstance<JsonArray>().firstOrNull()
                assertEquals(200.0, boundNutrition?.getDouble("energy_kcal") ?: -1.0, "营养必须绑定为 JSONB")
                assertEquals(listOf("花生", "牛奶"), boundAllergens?.map { it.toString() }, "过敏原必须绑定为字符串数组")

                // 读路径：toJson 透出两列
                assertEquals(200.0, body.getJsonObject("nutrition")?.getDouble("energy_kcal") ?: -1.0, body.encode())
                assertEquals(listOf("花生", "牛奶"), body.getJsonArray("allergens")?.map { it.toString() }, body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `POST菜品创建同样带上nutrition与allergens`(vertx: Vertx, ctx: VertxTestContext) {
        post(vertx, dishBody().put("name", "小米粥").put("category", "主食")) { status, body ->
            ctx.verify {
                assertEquals(201, status, "创建必须 201，实际: ${body.encode()}")
                val insertSql = executed.first { it.contains("insert into dining.dining_dishes") }
                assertTrue(insertSql.contains("nutrition"), "INSERT 必须写 nutrition，实际: $insertSql")
                assertTrue(insertSql.contains("allergens"), "INSERT 必须写 allergens，实际: $insertSql")
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法nutrition类型返回400而非500`(vertx: Vertx, ctx: VertxTestContext) {
        put(vertx, JsonObject().put("nutrition", "高蛋白")) { status, body ->
            ctx.verify {
                assertEquals(400, status, "nutrition 非对象必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("nutrition"), body.encode())
                ctx.completeNow()
            }
        }
    }

    @Test
    fun `非法allergens元素返回400而非500`(vertx: Vertx, ctx: VertxTestContext) {
        put(vertx, JsonObject().put("allergens", JsonArray().add(JsonObject().put("allergen", "花生")))) { status, body ->
            ctx.verify {
                assertEquals(400, status, "allergens 元素非字符串必须 400，实际: ${body.encode()}")
                assertTrue(body.getString("error")!!.contains("allergens"), body.encode())
                ctx.completeNow()
            }
        }
    }

    // ========================================================================
    //  辅助
    // ========================================================================

    private fun dishBody(): JsonObject = JsonObject()
        .put("nutrition", JsonObject()
            .put("basis_g", 100)
            .put("energy_kcal", 200)
            .put("protein_g", 10)
            .put("fat_g", 5)
            .put("carb_g", 20)
            .put("sodium_mg", 100))
        .put("allergens", JsonArray().add("花生").add("牛奶"))

    private fun dishRow(): Row = mockk(relaxed = true) {
        every { getValue("id") } returns DISH_ID
        every { getValue("name") } returns "米饭"
        every { getValue("category") } returns "主食"
        every { getValue("meal_times") } returns JsonArray().add("午餐")
        every { getValue("diet_tags") } returns JsonArray()
        every { getValue("status") } returns "启用"
        every { getValue("nutrition") } returns JsonObject()
            .put("basis_g", 100.0)
            .put("energy_kcal", 200.0)
        every { getValue("allergens") } returns JsonArray().add("花生").add("牛奶")
    }

    private fun put(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.PUT, "/dining/v1/dishes/$DISH_ID", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun post(vertx: Vertx, body: JsonObject, handler: (Int, JsonObject) -> Unit) {
        withServer(vertx) { port ->
            request(vertx, port, HttpMethod.POST, "/dining/v1/dishes", body)
                .map { (status, response) -> handler(status, response) }
        }.onFailure { /* 断言已在 ctx 内登记 */ }
    }

    private fun request(
        vertx: Vertx,
        port: Int,
        method: HttpMethod,
        path: String,
        body: JsonObject,
    ): Future<Pair<Int, JsonObject>> {
        val client = vertx.createHttpClient()
        return client.request(method, port, "127.0.0.1", path)
            .compose { req -> req.putHeader("Content-Type", "application/json").send(body.encode()) }
            .compose { resp ->
                resp.body().map { buffer ->
                    val json = try {
                        JsonObject(buffer.toString())
                    } catch (_: Exception) {
                        JsonObject()
                    }
                    Pair(resp.statusCode(), json)
                }
            }
            .onComplete { client.close() }
    }

    private fun withServer(vertx: Vertx, block: (Int) -> Future<Unit>): Future<Unit> {
        val authHandler = Handler<RoutingContext> { rctx ->
            rctx.put("userId", "dietitian-1")
            rctx.next()
        }
        val root = Router.router(vertx)
        root.route("/dining/v1/*").subRouter(DiningRoutes.create(vertx, pool, authHandler))
        return vertx.createHttpServer().requestHandler(root).listen(0).compose { server ->
            block(server.actualPort()).compose { server.close().map { Unit } }
        }
    }

    private fun record(sql: String) {
        executed.add(normalized(sql))
    }

    private fun normalized(sql: String): String = sql.lowercase().replace("\"", "")

    private fun rowSet(vararg rows: Row, rowCount: Int = rows.size): RowSet<Row> =
        mockk(relaxed = true) {
            every { size() } returns rows.size
            every { rowCount() } returns rowCount
            every { iterator() } answers {
                val delegate = rows.iterator()
                mockk<RowIterator<Row>>(relaxed = true) {
                    every { hasNext() } answers { delegate.hasNext() }
                    every { next() } answers { delegate.next() }
                }
            }
        }

    private companion object {
        const val DISH_ID = "01J8ZQ9K7M3N5P7R9T1V3X5Z71"
    }
}
