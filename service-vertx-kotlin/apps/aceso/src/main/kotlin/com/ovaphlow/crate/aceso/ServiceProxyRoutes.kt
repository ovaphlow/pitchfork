package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Transparently forwards requests to a shared upstream service.
 *
 * The browser's IDP Cookie is deliberately copied as an opaque header. Aceso
 * does not inspect authentication state; IDP and Nexus remain its authorities.
 */
object ServiceProxyRoutes {
    private val log = LoggerFactory.getLogger(ServiceProxyRoutes::class.java)
    private val excludedHeaders = setOf(
        "connection",
        "content-length",
        "host",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )

    fun create(
        vertx: Vertx,
        baseUrl: String,
        serviceName: String,
        unavailableProblemType: String,
    ): Router {
        val upstream = URI(baseUrl.removeSuffix("/"))
        require(upstream.scheme == "http" || upstream.scheme == "https") {
            "$serviceName.base-url must use http or https"
        }
        require(upstream.query == null && upstream.fragment == null) {
            "$serviceName.base-url must not include a query or fragment"
        }
        val host = requireNotNull(upstream.host) { "$serviceName.base-url must include a host" }
        val port = if (upstream.port == -1) {
            if (upstream.scheme == "https") 443 else 80
        } else {
            upstream.port
        }
        val basePath = upstream.rawPath.orEmpty().removeSuffix("/")
        val client = vertx.createHttpClient(HttpClientOptions().setSsl(upstream.scheme == "https"))
        val router = Router.router(vertx)

        router.route().handler(BodyHandler.create().setBodyLimit(20L * 1024 * 1024))
        router.route("/*").handler { ctx ->
            val path = requireNotNull(ctx.request().path()) { "$serviceName request path is required" }
            val rawQuery = ctx.request().query()
            val query = if (rawQuery.isNullOrEmpty()) "" else "?$rawQuery"
            val target = basePath + path + query

            client.request(ctx.request().method(), port, host, target)
                .onSuccess { upstreamRequest ->
                    ctx.request().headers().forEach { header ->
                        if (header.key.lowercase() !in excludedHeaders) {
                            upstreamRequest.headers().add(header.key, header.value)
                        }
                    }
                    upstreamRequest.send(ctx.body().buffer() ?: Buffer.buffer())
                        .onSuccess { upstreamResponse ->
                            upstreamResponse.body()
                                .onSuccess { body ->
                                    ctx.response().setStatusCode(upstreamResponse.statusCode())
                                    upstreamResponse.headers().forEach { header ->
                                        if (header.key.lowercase() !in excludedHeaders) {
                                            // Set-Cookie may occur more than once and must be preserved verbatim.
                                            ctx.response().headers().add(header.key, header.value)
                                        }
                                    }
                                    ctx.response().end(body)
                                }
                                .onFailure { error -> respondUnavailable(ctx, serviceName, unavailableProblemType, error) }
                        }
                        .onFailure { error -> respondUnavailable(ctx, serviceName, unavailableProblemType, error) }
                }
                .onFailure { error -> respondUnavailable(ctx, serviceName, unavailableProblemType, error) }
        }
        return router
    }

    private fun respondUnavailable(
        ctx: RoutingContext,
        serviceName: String,
        unavailableProblemType: String,
        error: Throwable,
    ) {
        log.warn("{} request failed: {}", serviceName, error.message)
        if (!ctx.response().ended()) {
            val problem = JsonObject()
                .put("type", unavailableProblemType)
                .put("title", "Bad Gateway")
                .put("status", 502)
                .put("detail", "$serviceName unavailable")
                .put("instance", ctx.request().uri())
            ctx.response()
                .setStatusCode(502)
                .putHeader("Content-Type", "application/problem+json")
                .end(problem.encode())
        }
    }
}
