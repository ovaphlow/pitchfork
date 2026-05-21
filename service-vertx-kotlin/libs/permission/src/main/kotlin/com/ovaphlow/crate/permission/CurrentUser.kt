package com.ovaphlow.crate.permission

import io.vertx.core.json.JsonObject
import io.vertx.ext.web.RoutingContext

fun RoutingContext.currentUserId(): String? = get("userId")

fun RoutingContext.currentToken(): String? = get("token")

fun RoutingContext.currentPrincipal(): JsonObject? = get("principal")
