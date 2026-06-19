package com.ovaphlow.crate.inventories

import io.vertx.core.Vertx
import io.vertx.ext.web.Router
import io.vertx.sqlclient.Pool

object MaterialRoutes {
    fun create(vertx: Vertx, pool: Pool): Router {
        val router = Router.router(vertx)
        return router
    }
}
