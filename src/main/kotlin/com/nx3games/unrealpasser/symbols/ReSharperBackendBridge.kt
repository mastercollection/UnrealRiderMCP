package com.nx3games.unrealpasser.symbols

interface ReSharperBackendBridge {
    fun getSymbolsOverview(relativePath: String, depth: Int): List<Map<String, Any?>>
    fun findSymbol(namePath: String, relativePath: String?, includeBody: Boolean, depth: Int): List<Map<String, Any?>>
    fun findDeclaration(relativePath: String, position: PositionDto, includeBody: Boolean): List<Map<String, Any?>>
    fun findReferences(namePath: String, relativePath: String): List<Map<String, Any?>>
    fun findImplementations(namePath: String, relativePath: String): List<Map<String, Any?>>
    fun getSupertypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>>
    fun getSubtypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>>
    fun runInspectionsOnFile(relativePath: String): List<Map<String, Any?>>
    fun listInspections(): List<Map<String, Any?>>
}
