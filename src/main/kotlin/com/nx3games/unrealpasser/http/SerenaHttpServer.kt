package com.nx3games.unrealpasser.http

import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.nx3games.unrealpasser.core.ApiError
import com.nx3games.unrealpasser.core.IndexState
import com.nx3games.unrealpasser.core.ProviderFailureException
import com.nx3games.unrealpasser.core.UnrealPasserStatus
import com.nx3games.unrealpasser.protocol.UnrealPasserBackendConnection
import com.nx3games.unrealpasser.symbols.PositionDto
import com.nx3games.unrealpasser.symbols.ReSharperSymbolProvider
import com.nx3games.unrealpasser.symbols.SymbolProvider
import com.nx3games.unrealpasser.unreal.UnrealProjectIndex
import com.nx3games.unrealpasser.unreal.UnrealQuery
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

class SerenaHttpServer(
    private val project: Project,
    private val projectRoot: String,
    private val symbols: SymbolProvider,
    private val unreal: UnrealProjectIndex,
    private val backendStatus: (() -> String)? = null,
    private val backendOperation: ((String, Map<String, Any?>) -> Map<String, Any?>)? = null,
) {
    var port: Int = -1
        private set

    private var server: HttpServer? = null
    private val status = UnrealPasserStatus(project, projectRoot, PLUGIN_VERSION)

    fun start() {
        val bound = bind()
        port = bound.address.port
        server = bound
        registerRoutes(bound)
        bound.executor = Executors.newCachedThreadPool { task ->
            Thread(task, "UnrealPasser-HTTP").apply { isDaemon = true }
        }
        bound.start()
        thisLogger().info("UnrealPasser listening on 127.0.0.1:$port")
    }

    fun stop() {
        server?.stop(0)
        server = null
    }

    private fun bind(): HttpServer {
        for (candidate in BASE_PORT until BASE_PORT + PORT_SCAN_COUNT) {
            try {
                return HttpServer.create(InetSocketAddress("127.0.0.1", candidate), 0)
            } catch (_: Exception) {
            }
        }
        error("No available UnrealPasser port in $BASE_PORT..${BASE_PORT + PORT_SCAN_COUNT - 1}")
    }

    private fun registerRoutes(server: HttpServer) {
        server.createContext("/") { exchange ->
            try {
                route(exchange)
            } catch (t: ProviderFailureException) {
                exchange.writeJson(t.httpStatus, errorResponse(t.apiError, indexStateFor(t.apiError)))
            } catch (t: UnsupportedOperationException) {
                exchange.writeJson(
                    501,
                    errorResponse(
                        ApiError(
                            errorCode = "UNSUPPORTED_OPERATION",
                            message = t.message ?: "Unsupported operation",
                            source = "unrealpasser",
                            recoverable = false,
                        )
                    )
                )
            } catch (t: IllegalArgumentException) {
                exchange.writeJson(
                    400,
                    errorResponse(
                        ApiError(
                            errorCode = "BAD_REQUEST",
                            message = t.message ?: "Bad request",
                            source = "unrealpasser",
                            recoverable = true,
                        )
                    )
                )
            } catch (t: Throwable) {
                thisLogger().warn("UnrealPasser request failed", t)
                exchange.writeJson(
                    500,
                    errorResponse(
                        ApiError(
                            errorCode = "INTERNAL_ERROR",
                            message = t.message ?: "Internal server error",
                            source = "unrealpasser",
                            recoverable = false,
                        ),
                        indexState = IndexState.ERROR,
                    )
                )
            }
        }
    }

    private fun route(exchange: HttpExchange) {
        val path = exchange.requestURI.path
        val body = if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
            exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
        } else {
            ""
        }
        val req = JsonRequest(body)

        val response: Any? = when (path) {
            "/status" -> withMetadata(
                mapOf(
                    "projectRoot" to projectRoot,
                    "pluginVersion" to PLUGIN_VERSION,
                    "readiness" to status.currentReadiness().toJsonMap(),
                    "capabilities" to mapOf(
                        "serenaCompatible" to true,
                        "productApi" to true,
                        "readOnly" to true,
                        "fallbackEnabled" to (symbols.providerName == "fallback"),
                        "backendBridge" to UnrealPasserBackendConnection.isConnected(project),
                    ),
                )
            )

            "/backend/status" -> withMetadata(
                mapOf(
                    "backend" to (backendStatus ?: throw UnsupportedOperationException("ReSharper backend bridge is not configured.")).invoke(),
                ),
                provider = "resharper",
            )

            "/unreal/capabilities" -> withMetadata(unrealBackendOrFallback("unrealCapabilities", emptyMap()) { unreal.capabilities() }, provider = "resharper")
            "/unreal/project/summary", "/mcp/unreal_project_status" -> withMetadata(unrealBackendOrFallback("unrealProjectStatus", emptyMap()) { unreal.projectSummary() }, provider = "resharper")
            "/unreal/reflection/search", "/mcp/unreal_reflection_search" -> withMetadata(unrealBackendOrFallback("unrealReflectionSearch", req.unrealPayload()) { unreal.searchReflection(req.unrealQuery()) }, provider = "resharper")
            "/unreal/reflection/get" -> withMetadata(unrealBackendOrFallback("unrealReflectionGet", req.unrealPayload()) { unreal.getReflection(req.unrealQuery()) }, provider = "resharper")
            "/unreal/assets/search" -> withMetadata(unrealBackendOrFallback("unrealAssetSearch", req.unrealPayload()) { unreal.searchAssets(req.unrealQuery()) }, provider = "resharper")
            "/unreal/assets/references", "/mcp/unreal_asset_references" -> {
                val payload = req.unrealAssetReferencesPayload()
                withMetadata(unrealBackendOrFallback("unrealAssetReferences", payload) { unreal.assetReferences(req.unrealQuery()) }, provider = "resharper")
            }
            "/mcp/rider_inspections" -> withMetadata(mapOf("inspections" to inspections(req)), provider = "resharper")

            "/getSymbolsOverview" -> withMetadata(mapOf(
                "symbols" to symbols.getSymbolsOverview(
                    relativePath = req.string("relativePath", required = true)!!,
                    depth = req.int("depth") ?: 0,
                )
            ))

            "/findSymbol" -> withMetadata(mapOf(
                "symbols" to symbols.findSymbol(
                    namePath = req.string("namePath", required = true)!!,
                    relativePath = req.string("relativePath"),
                    includeBody = req.boolean("includeBody") ?: false,
                    depth = req.int("depth") ?: 0,
                )
            ))

            "/findDeclaration" -> {
                val relativePath = req.string("relativePath", required = true)!!
                val line = req.int("line") ?: 0
                val col = req.int("col") ?: 0
                withMetadata(mapOf("symbols" to symbols.findDeclaration(relativePath, PositionDto(line, col), req.boolean("includeBody") ?: false)))
            }

            "/findReferences" -> withMetadata(mapOf(
                "symbols" to symbols.findReferences(
                    namePath = req.string("namePath", required = true)!!,
                    relativePath = req.string("relativePath", required = true)!!,
                )
            ))

            "/findImplementations" -> withMetadata(mapOf(
                "symbols" to symbols.findImplementations(
                    namePath = req.string("namePath", required = true)!!,
                    relativePath = req.string("relativePath", required = true)!!,
                )
            ))

            "/getSupertypes" -> withMetadata(mapOf(
                "hierarchy" to symbols.getSupertypes(
                    namePath = req.string("namePath", required = true)!!,
                    relativePath = req.string("relativePath", required = true)!!,
                    depth = req.int("depth") ?: 1,
                )
            ))

            "/getSubtypes" -> withMetadata(mapOf(
                "hierarchy" to symbols.getSubtypes(
                    namePath = req.string("namePath", required = true)!!,
                    relativePath = req.string("relativePath", required = true)!!,
                    depth = req.int("depth") ?: 1,
                )
            ))

            "/runInspectionsOnFile" -> withMetadata(mapOf("inspections" to inspections(req)), provider = "resharper")
            "/listInspections" -> withMetadata(listInspectionsPayload(), provider = "resharper")
            "/renameSymbol", "/moveSymbol", "/safeDelete", "/inlineSymbol",
            "/debugReplEval", "/debugReplClose", "/refreshFile" ->
                throw readOnlyOperation(path)

            else -> throw IllegalArgumentException("Unknown endpoint: $path")
        }

        exchange.writeJson(200, response)
    }

    private fun inspections(req: JsonRequest): List<Map<String, Any?>> {
        val relativePath = req.string("relativePath", required = true)!!
        return (symbols as? ReSharperSymbolProvider)?.runInspectionsOnFile(relativePath)
            ?: emptyList()
    }

    private fun listInspections(): List<Map<String, Any?>> {
        return (symbols as? ReSharperSymbolProvider)?.listInspections()
            ?: emptyList()
    }

    private fun listInspectionsPayload(): Map<String, Any?> {
        return backendOperation?.invoke("listInspections", emptyMap())
            ?: mapOf("inspections" to listInspections())
    }

    private fun readOnlyOperation(path: String): ProviderFailureException {
        return ProviderFailureException(
            ApiError(
                errorCode = "READ_ONLY_OPERATION",
                message = "UnrealPasser V1 is read-only; mutation endpoint '$path' is not supported.",
                source = "unrealpasser",
                recoverable = false,
            ),
            httpStatus = 405,
        )
    }

    private fun unrealBackendOrFallback(
        operation: String,
        payload: Map<String, Any?>,
        fallback: () -> Map<String, Any?>,
    ): Map<String, Any?> {
        return backendOperation?.invoke(operation, payload) ?: fallback()
    }

    private fun withMetadata(payload: Map<String, Any?>, provider: String = symbols.providerName): Map<String, Any?> {
        return linkedMapOf<String, Any?>("metadata" to status.metadata(provider)) + payload
    }

    private fun errorResponse(error: ApiError, indexState: IndexState? = null): Map<String, Any?> {
        return mapOf(
            "metadata" to status.metadata(error.source, indexState),
            "error" to error.toJsonMap(),
        )
    }

    private fun indexStateFor(error: ApiError): IndexState? {
        return when {
            error.errorCode == "PSI_UNAVAILABLE" -> IndexState.ERROR
            error.errorCode.startsWith("RESHARPER_") &&
                (error.errorCode.endsWith("_UNAVAILABLE") || error.errorCode.endsWith("_FAILED")) -> IndexState.ERROR
            else -> null
        }
    }

    private fun JsonRequest.unrealQuery(): UnrealQuery = UnrealQuery(
        query = string("query"),
        kind = string("kind"),
        pathPrefix = string("pathPrefix"),
        module = string("module"),
        limit = int("limit") ?: 50,
        offset = int("offset") ?: 0,
        includeChildren = boolean("includeChildren") ?: false,
        includeGenerated = boolean("includeGenerated") ?: false,
    )

    private fun JsonRequest.unrealPayload(): Map<String, Any?> = mapOf(
        "query" to string("query"),
        "namePath" to string("namePath"),
        "relativePath" to string("relativePath"),
        "kind" to string("kind"),
        "pathPrefix" to string("pathPrefix"),
        "module" to string("module"),
        "limit" to (int("limit") ?: 50),
        "offset" to (int("offset") ?: 0),
        "includeChildren" to (boolean("includeChildren") ?: false),
        "includeGenerated" to (boolean("includeGenerated") ?: false),
    )

    private fun JsonRequest.unrealAssetReferencesPayload(): Map<String, Any?> = unrealPayload() + mapOf(
        "namePath" to requiredNonBlankString("namePath"),
        "relativePath" to requiredNonBlankString("relativePath"),
        "includeChildren" to (boolean("includeChildren") ?: true),
    )

    private fun JsonRequest.requiredNonBlankString(key: String): String {
        val value = string(key)
        require(!value.isNullOrBlank()) { "Missing required string field '$key'." }
        return value
    }

    private fun HttpExchange.writeJson(status: Int, value: Any?) {
        val payload = JsonWriter.write(value).toByteArray(StandardCharsets.UTF_8)
        responseHeaders.set("Content-Type", "application/json; charset=utf-8")
        sendResponseHeaders(status, payload.size.toLong())
        responseBody.use { it.write(payload) }
    }

    companion object {
        private const val BASE_PORT = 0x5EA2
        private const val PORT_SCAN_COUNT = 20
        private const val PLUGIN_VERSION = "0.1.0"
    }
}
