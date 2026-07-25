package com.ovaphlow.crate.aceso

import io.vertx.core.Vertx
import io.vertx.core.http.HttpClientOptions
import io.vertx.core.json.JsonObject
import io.vertx.ext.web.Router
import io.vertx.ext.web.handler.BodyHandler
import org.slf4j.LoggerFactory
import java.net.URI

/**
 * Forwards Nexus requests without interpreting the caller's IDP cookie.
 * Authentication remains the responsibility of service-idp-go and Nexus.
 */
object NexusProxyRoutes {
    private val log = LoggerFactory.getLogger(NexusProxyRoutes::class.java)
    private val hopByHopHeaders = setOf(
        "connection",
        "content-length",
        "keep-alive",
        "proxy-authenticate",
        "proxy-authorization",
        "te",
        "trailer",
        "transfer-encoding",
        "upgrade",
    )

    fun create(vertx: Vertx, baseUrl: String): Router {
        val upstream = URI(baseUrl.removeSuffix("/"))
        require(upstream.scheme == "http" || upstream.scheme == "https") {
            "nexus.base-url must use http or https"
        }
        val host = requireNotNull(upstream.host) { "nexus.base-url must include a host" }
        val port = if (upstream.port == -1) {
            if (upstream.scheme == "https") 443 else 80
        } else {
            upstream.port
        }
        val client = vertx.createHttpClient(
            HttpClientOptions().setSsl(upstream.scheme == "https"),
        )
        val router = Router.router(vertx)
        router.route().handler(BodyHandler.create().setBodyLimit(20L * 1024 * 1024))
        router.route("/*").handler { ctx ->
            val path: String = requireNotNull(ctx.request().path()) { "Nexus request path is required" }
            val rawQuery: String? = ctx.request().query()
            val query = if (rawQuery.isNullOrEmpty()) "" else "?$rawQuery"
            val target = path + query
            client.request(ctx.request().method(), port, host, target)
                .onSuccess { upstreamRequest ->
                    ctx.request().headers().forEach { header ->
                        if (header.key.lowercase() !in hopByHopHeaders) {
                            upstreamRequest.putHeader(header.key, header.value)
                        }
                    }
                    upstreamRequest.send(ctx.body().buffer())
                        .onSuccess { upstreamResponse ->
                            ctx.response().setStatusCode(upstreamResponse.statusCode())
                            upstreamResponse.headers().forEach { header ->
                                if (header.key.lowercase() !in hopByHopHeaders) {
                                    ctx.response().putHeader(header.key, header.value)
                                }
                            }
                            upstreamResponse.pipeTo(ctx.response())
                                .onFailure { error ->
                                    log.error("Nexus response stream failed", error)
                                    if (!ctx.response().ended()) ctx.response().end()
                                }
                        }
                        .onFailure { error -> respondUnavailable(ctx, error) }
                }
                .onFailure { error -> respondUnavailable(ctx, error) }
        }
        return router
    }

    private fun respondUnavailable(ctx: io.vertx.ext.web.RoutingContext, error: Throwable) {
        log.warn("Nexus request failed: {}", error.message)
        if (!ctx.response().ended()) {
            val problem = JsonObject()
                .put("type", "/crate-api/shared/v1/problems/nexus-unavailable")
                .put("title", "Bad Gateway")
                .put("status", 502)
                .put("detail", "nexus unavailable")
                .put("instance", ctx.request().uri())
            ctx.response()
                .setStatusCode(502)
                .putHeader("Content-Type", "application/problem+json")
                .end(problem.encode())
        }
    }
}
