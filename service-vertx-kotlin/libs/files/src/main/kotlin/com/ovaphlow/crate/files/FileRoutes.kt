package com.ovaphlow.crate.files

import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import org.slf4j.LoggerFactory

object FileRoutes {

    private val log = LoggerFactory.getLogger(FileRoutes::class.java)

    fun create(vertx: Vertx): Router {
        val router = Router.router(vertx)

        router.get("/health").handler { ctx ->
            ctx.json(JsonObject().put("status", "ok").put("service", "files"))
        }

        router.post("/upload").handler { ctx ->
            val file = ctx.fileUploads().iterator().next()
            log.info("file uploaded: {}", file.fileName())
            ctx.json(
                JsonObject()
                    .put("fileId", "file-${System.currentTimeMillis()}")
                    .put("fileName", file.fileName())
                    .put("size", file.size())
                    .put("status", "uploaded")
            )
        }

        router.get("/:fileId").handler { ctx ->
            val fileId = ctx.pathParam("fileId")
            ctx.json(
                JsonObject()
                    .put("fileId", fileId)
                    .put("fileName", "example.txt")
                    .put("size", 1024)
                    .put("mimeType", "text/plain")
            )
        }

        router.delete("/:fileId").handler { ctx ->
            val fileId = ctx.pathParam("fileId")
            log.info("delete file: {}", fileId)
            ctx.json(JsonObject().put("fileId", fileId).put("status", "deleted"))
        }

        return router
    }
}
