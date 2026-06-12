package com.nx3games.unrealpasser.symbols

data class PositionDto(val line: Int, val col: Int)

data class SymbolDto(
    val namePath: String,
    val relativePath: String,
    val type: String,
    val body: String? = null,
    val quickInfo: String? = null,
    val documentation: String? = null,
    val textRange: TextRangeDto? = null,
    val children: List<SymbolDto> = emptyList(),
    val referenceLineNo: Int? = null,
) {
    fun toJsonMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "namePath" to namePath,
            "relativePath" to relativePath,
            "type" to type,
        )
        if (body != null) result["body"] = body
        if (quickInfo != null) result["quickInfo"] = quickInfo
        if (documentation != null) result["documentation"] = documentation
        if (textRange != null) result["textRange"] = textRange.toJsonMap()
        if (children.isNotEmpty()) result["children"] = children.map { it.toJsonMap() }
        if (referenceLineNo != null) result["referenceLineNo"] = referenceLineNo
        return result
    }
}

data class TextRangeDto(val startPos: PositionDto, val endPos: PositionDto) {
    fun toJsonMap(): Map<String, Any?> = mapOf(
        "startPos" to mapOf("line" to startPos.line, "col" to startPos.col),
        "endPos" to mapOf("line" to endPos.line, "col" to endPos.col),
    )
}

data class TypeHierarchyNodeDto(
    val symbol: SymbolDto,
    val children: List<TypeHierarchyNodeDto> = emptyList(),
) {
    fun toJsonMap(): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>("symbol" to symbol.toJsonMap())
        if (children.isNotEmpty()) result["children"] = children.map { it.toJsonMap() }
        return result
    }
}
