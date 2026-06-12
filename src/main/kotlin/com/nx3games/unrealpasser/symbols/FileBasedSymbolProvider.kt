package com.nx3games.unrealpasser.symbols

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.io.path.relativeTo
import kotlin.io.path.readText

class FileBasedSymbolProvider(projectRoot: String) : SymbolProvider {
    override val providerName: String = "fallback"

    private val root: Path = Paths.get(projectRoot).toAbsolutePath().normalize()

    override fun getSymbolsOverview(relativePath: String, depth: Int): List<Map<String, Any?>> {
        val path = resolveProjectPath(relativePath)
        val files = if (path.isRegularFile()) listOf(path) else candidateFiles(relativePath)
        return files.flatMap { symbolsForFile(it, includeBody = false, depth = depth) }.map { it.toJsonMap() }
    }

    override fun findSymbol(namePath: String, relativePath: String?, includeBody: Boolean, depth: Int): List<Map<String, Any?>> {
        val matcher = NamePathMatcher(namePath)
        val files = candidateFiles(relativePath)
        return files.flatMap { symbolsForFile(it, includeBody, depth) }
            .filter { matcher.matches(it.namePath) }
            .map { it.toJsonMap() }
    }

    override fun findDeclaration(relativePath: String, position: PositionDto, includeBody: Boolean): List<Map<String, Any?>> {
        val file = resolveProjectPath(relativePath)
        val content = file.readText()
        val offset = content.offsetAt(position.line, position.col)
        val ident = identifierAt(content, offset) ?: return emptyList()
        return findSymbol(ident, null, includeBody, 0)
    }

    override fun findReferences(namePath: String, relativePath: String): List<Map<String, Any?>> {
        val name = namePath.substringAfterLast('/').substringBefore('[')
        return candidateFiles(null).flatMap { file ->
            val lines = file.readText().lines()
            lines.mapIndexedNotNull { index, line ->
                if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(line)) {
                    SymbolDto(
                        namePath = name,
                        relativePath = toRelative(file),
                        type = "reference",
                        referenceLineNo = index,
                        quickInfo = line.trim(),
                    ).toJsonMap()
                } else {
                    null
                }
            }
        }
    }

    override fun findImplementations(namePath: String, relativePath: String): List<Map<String, Any?>> {
        val symbol = findSymbol(namePath, relativePath, includeBody = false, depth = 0).firstOrNull() ?: return emptyList()
        val name = (symbol["namePath"] as String).substringAfterLast('/')
        return candidateFiles(null)
            .flatMap { symbolsForFile(it, includeBody = false, depth = 1) }
            .filter { (it.quickInfo?.contains(": $name") == true) || (it.quickInfo?.contains("override") == true && it.namePath.endsWith("/$name")) }
            .map { it.toJsonMap() }
    }

    override fun getSupertypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>> {
        val symbol = symbolsForFile(resolveProjectPath(relativePath), includeBody = false, depth = 0)
            .firstOrNull { it.namePath == namePath }
            ?: return emptyList()
        val bases = Regex("""\b(class|struct)\s+\w+\s*:\s*([^{]+)""").find(symbol.quickInfo ?: "")?.groupValues?.get(2)
            ?.split(',')
            ?.map { it.trim().removePrefix("public ").removePrefix("protected ").removePrefix("private ") }
            ?.filter { it.isNotBlank() }
            ?: return emptyList()
        return bases.flatMap { base ->
            findSymbol(base, null, includeBody = false, depth = depth).mapNotNull { map ->
                mapToSymbol(map)?.let { TypeHierarchyNodeDto(it).toJsonMap() }
            }
        }
    }

    override fun getSubtypes(namePath: String, relativePath: String, depth: Int): List<Map<String, Any?>> {
        val name = namePath.substringAfterLast('/')
        return candidateFiles(null)
            .flatMap { symbolsForFile(it, includeBody = false, depth = 0) }
            .filter { Regex(""":\s*[^{};]*\b${Regex.escape(name)}\b""").containsMatchIn(it.quickInfo ?: "") }
            .map { TypeHierarchyNodeDto(it).toJsonMap() }
    }

    private fun symbolsForFile(file: Path, includeBody: Boolean, depth: Int): List<SymbolDto> {
        if (!file.isRegularFile()) return emptyList()
        val content = file.readText()
        val lines = content.lines()
        val topLevel = mutableListOf<SymbolDto>()

        var pendingUeMacro: String? = null
        lines.forEachIndexed { lineIndex, line ->
            val trimmed = line.trim()
            if (UE_MACRO.matches(trimmed)) {
                pendingUeMacro = trimmed
                return@forEachIndexed
            }

            CLASS_OR_STRUCT.find(line)?.let { match ->
                val kind = match.groupValues[1]
                val name = match.groupValues[2]
                topLevel += createSymbol(
                    namePath = name,
                    relativePath = toRelative(file),
                    type = kind,
                    quickInfo = buildQuickInfo(line.trim(), pendingUeMacro),
                    documentation = pendingUeMacro,
                    lines = lines,
                    lineIndex = lineIndex,
                    includeBody = includeBody,
                    children = if (depth > 0) childrenForClass(name, file, lines, lineIndex, includeBody) else emptyList(),
                )
                pendingUeMacro = null
                return@forEachIndexed
            }

            FUNCTION.find(line)?.let { match ->
                val name = match.groupValues[2]
                topLevel += createSymbol(
                    namePath = name,
                    relativePath = toRelative(file),
                    type = "function",
                    quickInfo = buildQuickInfo(line.trim(), pendingUeMacro),
                    documentation = pendingUeMacro,
                    lines = lines,
                    lineIndex = lineIndex,
                    includeBody = includeBody,
                )
                pendingUeMacro = null
                return@forEachIndexed
            }

            pendingUeMacro = null
        }

        return topLevel
    }

    private fun childrenForClass(
        className: String,
        file: Path,
        lines: List<String>,
        classLine: Int,
        includeBody: Boolean,
    ): List<SymbolDto> {
        val children = mutableListOf<SymbolDto>()
        var braceDepth = 0
        var inside = false
        var pendingUeMacro: String? = null

        for (i in classLine until lines.size) {
            val line = lines[i]
            if (i == classLine && !line.contains('{')) continue
            braceDepth += line.count { it == '{' }
            braceDepth -= line.count { it == '}' }
            if (!inside && braceDepth > 0) inside = true
            if (!inside) continue
            if (inside && braceDepth <= 0) break

            val trimmed = line.trim()
            if (UE_MACRO.matches(trimmed)) {
                pendingUeMacro = trimmed
                continue
            }

            val functionMatch = FUNCTION.find(line)
            if (functionMatch != null) {
                val childName = functionMatch.groupValues[2]
                children += createSymbol(
                    namePath = "$className/$childName",
                    relativePath = toRelative(file),
                    type = "method",
                    quickInfo = buildQuickInfo(trimmed, pendingUeMacro),
                    documentation = pendingUeMacro,
                    lines = lines,
                    lineIndex = i,
                    includeBody = includeBody,
                )
                pendingUeMacro = null
                continue
            }

            val propertyMatch = PROPERTY.find(line)
            if (propertyMatch != null) {
                val childName = propertyMatch.groupValues[2]
                children += createSymbol(
                    namePath = "$className/$childName",
                    relativePath = toRelative(file),
                    type = "property",
                    quickInfo = buildQuickInfo(trimmed, pendingUeMacro),
                    documentation = pendingUeMacro,
                    lines = lines,
                    lineIndex = i,
                    includeBody = false,
                )
                pendingUeMacro = null
            }
        }
        return children
    }

    private fun mapToSymbol(map: Map<String, Any?>): SymbolDto? {
        val namePath = map["namePath"] as? String ?: return null
        val relativePath = map["relativePath"] as? String ?: return null
        val type = map["type"] as? String ?: return null
        return SymbolDto(
            namePath = namePath,
            relativePath = relativePath,
            type = type,
            body = map["body"] as? String,
            quickInfo = map["quickInfo"] as? String,
            documentation = map["documentation"] as? String,
        )
    }

    private fun createSymbol(
        namePath: String,
        relativePath: String,
        type: String,
        quickInfo: String?,
        documentation: String?,
        lines: List<String>,
        lineIndex: Int,
        includeBody: Boolean,
        children: List<SymbolDto> = emptyList(),
    ): SymbolDto {
        val endLine = findBodyEnd(lines, lineIndex)
        return SymbolDto(
            namePath = namePath,
            relativePath = relativePath,
            type = type,
            quickInfo = quickInfo,
            documentation = documentation,
            textRange = TextRangeDto(PositionDto(lineIndex, 0), PositionDto(endLine, lines.getOrNull(endLine)?.length ?: 0)),
            body = if (includeBody) lines.subList(lineIndex, endLine + 1).joinToString("\n") else null,
            children = children,
        )
    }

    private fun findBodyEnd(lines: List<String>, start: Int): Int {
        var depth = 0
        var seenBrace = false
        for (i in start until lines.size) {
            val line = lines[i]
            depth += line.count { it == '{' }
            if (line.contains('{')) seenBrace = true
            depth -= line.count { it == '}' }
            if (seenBrace && depth <= 0) return i
            if (!seenBrace && line.trimEnd().endsWith(';')) return i
        }
        return start
    }

    private fun buildQuickInfo(declaration: String, ueMacro: String?): String {
        return if (ueMacro == null) declaration else "$ueMacro $declaration"
    }

    private fun candidateFiles(relativePath: String?): List<Path> {
        val base = relativePath?.takeIf { it.isNotBlank() }?.let(::resolveProjectPath) ?: root
        if (base.isRegularFile()) return listOf(base)
        return Files.walk(base).use { stream ->
            stream.filter { it.isRegularFile() && it.isCppLike() && !it.isIgnored() }
                .toList()
        }
    }

    private fun resolveProjectPath(relativePath: String): Path {
        val resolved = root.resolve(relativePath.replace('\\', '/')).normalize()
        require(resolved.startsWith(root)) { "Path escapes project root: $relativePath" }
        return resolved
    }

    private fun toRelative(path: Path): String = path.toAbsolutePath().normalize().relativeTo(root).toString().replace('\\', '/')

    private fun Path.isCppLike(): Boolean = extension.lowercase() in setOf("h", "hpp", "hh", "hxx", "cpp", "cc", "cxx", "inl")

    private fun Path.isIgnored(): Boolean {
        val rel = toRelative(this)
        return IGNORED_DIRS.any { rel == it || rel.startsWith("$it/") || rel.contains("/$it/") }
    }

    private fun String.offsetAt(line: Int, col: Int): Int {
        var currentLine = 0
        var offset = 0
        while (offset < length && currentLine < line) {
            if (this[offset] == '\n') currentLine++
            offset++
        }
        return (offset + col).coerceIn(0, length)
    }

    private fun identifierAt(content: String, offset: Int): String? {
        if (content.isEmpty()) return null
        var start = offset.coerceIn(0, content.lastIndex)
        while (start > 0 && content[start].isIdentifierPart()) start--
        if (!content[start].isIdentifierPart()) start++
        var end = start
        while (end < content.length && content[end].isIdentifierPart()) end++
        return content.substring(start, end).takeIf { it.isNotBlank() }
    }

    private fun Char.isIdentifierPart(): Boolean = isLetterOrDigit() || this == '_'

    private class NamePathMatcher(pattern: String) {
        private val raw = pattern.trim('/')
        fun matches(candidate: String): Boolean {
            val normalized = candidate.trim('/')
            return raw == normalized ||
                normalized.endsWith("/$raw") ||
                normalized.substringAfterLast('/') == raw ||
                normalized.contains(raw)
        }
    }

    companion object {
        private val IGNORED_DIRS = setOf(".git", ".idea", ".gradle", "build", "Binaries", "DerivedDataCache", "Intermediate", "Saved")
        private val UE_MACRO = Regex("""\s*U(CLASS|STRUCT|ENUM|INTERFACE|FUNCTION|PROPERTY|DELEGATE)\s*\(.*""")
        private val CLASS_OR_STRUCT = Regex("""\b(class|struct)\s+(?:\w+_API\s+)?(\w+)(?:\s*:\s*[^ {]+(?:\s*,\s*[^ {]+)*)?""")
        private val FUNCTION = Regex("""(?:virtual\s+|static\s+|inline\s+|FORCEINLINE\s+|constexpr\s+)?(?:[\w:<>,*&\s]+)\s+(\w+::)?(~?\w+|operator\s*[^\s(]+)\s*\([^;{}]*\)\s*(?:const\s*)?(?:override\s*)?(?:final\s*)?(?:[;{]|$)""")
        private val PROPERTY = Regex("""(?:[\w:<>,*&\s]+)\s+(\w+::)?(\w+)\s*(?:[=;{])""")
    }
}
