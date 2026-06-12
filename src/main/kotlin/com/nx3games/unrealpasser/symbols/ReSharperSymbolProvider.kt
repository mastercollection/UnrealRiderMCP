package com.nx3games.unrealpasser.symbols

import com.intellij.openapi.project.Project

class ReSharperSymbolProvider(
    @Suppress("unused") private val project: Project,
    private val bridge: ReSharperBackendBridge,
) : SymbolProvider {
    override val providerName: String = "resharper"

    override fun getSymbolsOverview(relativePath: String, depth: Int): List<Map<String, Any?>> {
        return bridge.getSymbolsOverview(relativePath, depth)
    }

    override fun findSymbol(namePath: String, relativePath: String?, includeBody: Boolean, depth: Int): List<Map<String, Any?>> {
        return bridge.findSymbol(namePath, relativePath, includeBody, depth)
    }

    override fun findDeclaration(relativePath: String, position: PositionDto, includeBody: Boolean): List<Map<String, Any?>> {
        return bridge.findDeclaration(relativePath, position, includeBody)
    }

    override fun findReferences(namePath: String, relativePath: String): List<Map<String, Any?>> {
        return bridge.findReferences(namePath, relativePath)
    }

    override fun findImplementations(namePath: String, relativePath: String): List<Map<String, Any?>> {
        return bridge.findImplementations(namePath, relativePath)
    }

    override fun getSupertypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>> {
        return bridge.getSupertypes(namePath, relativePath, depth)
    }

    override fun getSubtypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>> {
        return bridge.getSubtypes(namePath, relativePath, depth)
    }

    fun runInspectionsOnFile(relativePath: String): List<Map<String, Any?>> {
        return bridge.runInspectionsOnFile(relativePath)
    }

    fun listInspections(): List<Map<String, Any?>> {
        return bridge.listInspections()
    }
}
