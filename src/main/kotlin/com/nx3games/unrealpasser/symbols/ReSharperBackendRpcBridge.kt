package com.nx3games.unrealpasser.symbols

import com.nx3games.unrealpasser.core.ApiError
import com.nx3games.unrealpasser.core.ProviderFailureException
import com.nx3games.unrealpasser.http.JsonValueParser
import com.nx3games.unrealpasser.http.JsonWriter
import com.nx3games.unrealpasser.protocol.UnrealPasserBackendConnection
import com.intellij.openapi.project.Project

class ReSharperBackendRpcBridge(
    private val project: Project,
) : ReSharperBackendBridge {
    override fun getSymbolsOverview(relativePath: String, depth: Int): List<Map<String, Any?>> {
        return executeList("getSymbolsOverview", mapOf("relativePath" to relativePath, "depth" to depth))
    }

    override fun findSymbol(namePath: String, relativePath: String?, includeBody: Boolean, depth: Int): List<Map<String, Any?>> {
        return executeList(
            "findSymbol",
            mapOf("namePath" to namePath, "relativePath" to relativePath, "includeBody" to includeBody, "depth" to depth),
        )
    }

    override fun findDeclaration(relativePath: String, position: PositionDto, includeBody: Boolean): List<Map<String, Any?>> {
        return executeList(
            "findDeclaration",
            mapOf(
                "relativePath" to relativePath,
                "position" to mapOf("line" to position.line, "col" to position.col),
                "includeBody" to includeBody,
            ),
        )
    }

    override fun findReferences(namePath: String, relativePath: String): List<Map<String, Any?>> {
        return executeList("findReferences", mapOf("namePath" to namePath, "relativePath" to relativePath))
    }

    override fun findImplementations(namePath: String, relativePath: String): List<Map<String, Any?>> {
        return executeList("findImplementations", mapOf("namePath" to namePath, "relativePath" to relativePath))
    }

    override fun getSupertypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>> {
        return executeList("getSupertypes", mapOf("namePath" to namePath, "relativePath" to relativePath, "depth" to depth))
    }

    override fun getSubtypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>> {
        return executeList("getSubtypes", mapOf("namePath" to namePath, "relativePath" to relativePath, "depth" to depth))
    }

    override fun runInspectionsOnFile(relativePath: String): List<Map<String, Any?>> {
        return executeList("runInspectionsOnFile", mapOf("relativePath" to relativePath))
    }

    override fun listInspections(): List<Map<String, Any?>> {
        return executeList("listInspections", emptyMap())
    }

    fun backendStatus(): String {
        return executeRaw("status", emptyMap())
    }

    fun executeMap(operation: String, payload: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val response = executeRaw(operation, payload)
        @Suppress("UNCHECKED_CAST")
        return JsonValueParser.parse(response) as? Map<String, Any?>
            ?: throw backendResponseError(operation, response)
    }

    private fun executeList(operation: String, payload: Map<String, Any?>): List<Map<String, Any?>> {
        val response = executeRaw(operation, payload)
        return response.toListPayload(operation)
    }

    private fun String.toListPayload(operation: String): List<Map<String, Any?>> {
        val parsed = JsonValueParser.parse(this) as? Map<*, *>
            ?: throw backendResponseError(operation, this)
        val key = when (operation) {
            "getSupertypes", "getSubtypes" -> "hierarchy"
            "runInspectionsOnFile", "listInspections" -> "inspections"
            else -> "symbols"
        }
        val values = parsed[key] as? List<*>
            ?: throw backendResponseError(operation, this)
        @Suppress("UNCHECKED_CAST")
        return values.map { value ->
            value as? Map<String, Any?> ?: throw backendResponseError(operation, this)
        }
    }

    private fun backendResponseError(operation: String, response: String): ProviderFailureException {
        return ProviderFailureException(
            ApiError(
                errorCode = "RESHARPER_BACKEND_RESPONSE_UNSUPPORTED",
                message = "ReSharper backend returned an unexpected payload shape for '$operation': $response",
                source = "resharper",
                recoverable = false,
            ),
            httpStatus = 500,
        )
    }

    private fun executeRaw(operation: String, payload: Map<String, Any?>): String {
        val model = UnrealPasserBackendConnection.current(project)
            ?: throw ProviderFailureException(
                ApiError(
                    errorCode = "RESHARPER_BACKEND_BRIDGE_UNAVAILABLE",
                    message = "UnrealPasser ReSharper backend RD model is not connected.",
                    source = "resharper",
                    recoverable = true,
                )
        )
        val request = JsonWriter.write(mapOf("operation" to operation, "payload" to payload))
        val response = model.execute.sync(request, infiniteRpcTimeouts())
        throwIfBackendError(response)
        return response
    }

    private fun throwIfBackendError(response: String) {
        if (!response.contains(""""errorCode"""")) return
        val errorCode = response.stringField("errorCode") ?: "RESHARPER_BACKEND_ERROR"
        throw ProviderFailureException(
            ApiError(
                errorCode = errorCode,
                message = response.stringField("message") ?: response,
                source = response.stringField("source") ?: "resharper",
                recoverable = response.booleanField("recoverable") ?: true,
            ),
            httpStatus = if (errorCode == "UNREALPASSER_BACKEND_BAD_REQUEST") 400 else 503,
        )
    }

    private fun String.stringField(key: String): String? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*"((?:\\.|[^"\\])*)"""").find(this)
            ?.groupValues
            ?.get(1)
            ?.replace("\\\"", "\"")
            ?.replace("\\\\", "\\")
    }

    private fun String.booleanField(key: String): Boolean? {
        return Regex(""""${Regex.escape(key)}"\s*:\s*(true|false)""").find(this)
            ?.groupValues
            ?.get(1)
            ?.toBooleanStrictOrNull()
    }

    private fun infiniteRpcTimeouts(): com.jetbrains.rd.framework.impl.RpcTimeouts {
        val timeoutsClass = Class.forName("com.jetbrains.rd.framework.impl.RpcTimeouts")
        val companion = timeoutsClass.getField("Companion").get(null)
        @Suppress("UNCHECKED_CAST")
        return companion.javaClass.getMethod("getInfinite").invoke(companion) as com.jetbrains.rd.framework.impl.RpcTimeouts
    }
}
