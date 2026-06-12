package com.nx3games.unrealpasser.unreal

import com.nx3games.unrealpasser.core.ApiError
import com.nx3games.unrealpasser.core.ProviderFailureException
import com.nx3games.unrealpasser.core.RiderUnrealReadiness

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.nameWithoutExtension
import kotlin.io.path.relativeTo
import kotlin.io.path.readText

class UnrealProjectIndex(
    projectRoot: String,
    private val diagnosticFallback: Boolean = false,
    private val readiness: (() -> RiderUnrealReadiness)? = null,
) {
    private val root: Path = Paths.get(projectRoot).toAbsolutePath().normalize()
    private val reflection by lazy { buildReflectionIndex() }
    private val assets by lazy { buildAssetIndex() }
    private val assetReferences by lazy { buildAssetReferenceIndex() }

    fun capabilities(): Map<String, Any?> = mapOf(
        "source" to if (diagnosticFallback) "fallback" else "resharper",
        "projectStatus" to true,
        "reflection" to diagnosticFallback,
        "assetSearch" to diagnosticFallback,
        "assetReferences" to diagnosticFallback,
        "assetPackageGraph" to false,
        "diagnosticFallback" to diagnosticFallback,
        "warnings" to if (diagnosticFallback) listOf(
            "Diagnostic fallback is enabled; Unreal results are produced by source/config scanning, not Rider Unreal asset cache.",
            ASSET_GRAPH_WARNING,
        ) else emptyList(),
    )

    fun projectSummary(): Map<String, Any?> {
        if (!diagnosticFallback) {
            val current = readiness?.invoke() ?: throw ProviderFailureException(
                ApiError(
                    errorCode = "RESHARPER_UNREAL_STATUS_UNAVAILABLE",
                    message = "Rider UnrealModel status bridge is unavailable.",
                    source = "resharper-unreal",
                    recoverable = true,
                )
            )
            return mapOf(
                "projectRoot" to root.toString().replace('\\', '/'),
                "source" to "resharper",
                "indexState" to current.indexState.wireName,
                "readiness" to current.toJsonMap(),
            )
        }
        val uproject = Files.list(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension.equals("uproject", ignoreCase = true) }.findFirst().orElse(null)
        }
        val text = uproject?.readText().orEmpty()
        return mapOf(
            "projectRoot" to root.toString().replace('\\', '/'),
            "uproject" to uproject?.let(::toRelative),
            "engineAssociation" to Regex(""""EngineAssociation"\s*:\s*"([^"]+)"""").find(text)?.groupValues?.get(1),
            "modules" to Regex(""""Name"\s*:\s*"([^"]+)"\s*,\s*"Type"\s*:\s*"([^"]+)"""")
                .findAll(text)
                .map { mapOf("name" to it.groupValues[1], "type" to it.groupValues[2]) }
                .toList(),
            "enabledPlugins" to Regex(""""Name"\s*:\s*"([^"]+)"\s*,\s*"Enabled"\s*:\s*true""")
                .findAll(text)
                .map { it.groupValues[1] }
                .distinct()
                .toList(),
            "source" to "fallback",
        )
    }

    fun searchReflection(request: UnrealQuery): Map<String, Any?> {
        requireDiagnosticFallback("unreal_reflection_search")
        val filtered = reflection.asSequence()
            .filter { request.query.isNullOrBlank() || it.matches(request.query) }
            .filter { request.kind.isNullOrBlank() || it.kind.equals(request.kind, ignoreCase = true) }
            .filter { request.module.isNullOrBlank() || it.module.equals(request.module, ignoreCase = true) }
            .filter { request.pathPrefix.isNullOrBlank() || it.source.startsWith(request.pathPrefix.replace('\\', '/')) }
            .toList()
        return paged(filtered, request) { it.toJson(includeChildren = request.includeChildren) } +
            ("source" to "fallback")
    }

    fun getReflection(request: UnrealQuery): Map<String, Any?> {
        requireDiagnosticFallback("unreal_reflection_get")
        val query = request.query.orEmpty()
        val item = reflection.firstOrNull { it.name == query || it.cppName == query || it.name.endsWith(query) }
        return mapOf(
            "item" to item?.toJson(includeChildren = true),
            "source" to "fallback",
            "warnings" to emptyList<String>(),
        )
    }

    fun searchAssets(request: UnrealQuery): Map<String, Any?> {
        requireDiagnosticFallback("unreal_asset_search")
        val filtered = assets.asSequence()
            .filter { request.query.isNullOrBlank() || it.matches(request.query) }
            .filter { request.kind.isNullOrBlank() || it.kind.equals(request.kind, ignoreCase = true) }
            .filter { request.pathPrefix.isNullOrBlank() || it.packagePath.startsWith(request.pathPrefix) || it.relativePath.startsWith(request.pathPrefix) }
            .toList()
        return paged(filtered, request) { it.toJson() } +
            ("source" to "fallback")
    }

    fun assetReferences(request: UnrealQuery): Map<String, Any?> {
        requireDiagnosticFallback("unreal_asset_references")
        val query = request.query.orEmpty()
        val filtered = assetReferences.asSequence()
            .filter { query.isBlank() || it.assetPath.contains(query, ignoreCase = true) || it.sourceLine.contains(query, ignoreCase = true) }
            .filter { request.pathPrefix.isNullOrBlank() || it.relativePath.startsWith(request.pathPrefix.replace('\\', '/')) }
            .toList()
        return paged(filtered, request) { it.toJson() } +
            ("source" to "fallback") +
            ("warnings" to listOf(ASSET_GRAPH_WARNING))
    }

    private fun requireDiagnosticFallback(operation: String) {
        if (diagnosticFallback) return
        throw ProviderFailureException(
            ApiError(
                errorCode = "RESHARPER_UNREAL_API_UNAVAILABLE",
                message = "Rider/ReSharper Unreal API bridge is not available for '$operation'. Product APIs do not parse .uasset/.umap files or return file-scan fallback results.",
                source = "resharper-unreal",
                recoverable = true,
            )
        )
    }

    private fun buildReflectionIndex(): List<UnrealReflectionItem> {
        val files = candidateTextFiles(listOf("Source", "Plugins"), setOf("h", "hpp", "hh", "hxx"))
        return files.flatMap(::parseReflectionFile)
    }

    private fun parseReflectionFile(file: Path): List<UnrealReflectionItem> {
        val text = file.readText()
        val lines = text.lines()
        val module = moduleFor(file)
        val items = mutableListOf<UnrealReflectionItem>()
        var pendingMacro: MacroInfo? = null

        lines.forEachIndexed { index, line ->
            val trimmed = line.trim()
            MACRO.matchEntire(trimmed)?.let {
                pendingMacro = MacroInfo(it.groupValues[1], it.groupValues[2])
                return@forEachIndexed
            }

            val macro = pendingMacro
            if (macro != null && macro.name in TYPE_MACROS) {
                TYPE_DECL.find(line)?.let { match ->
                    val kind = macro.name.removePrefix("U").lowercase()
                    val cppName = match.groupValues[2]
                    val bases = match.groupValues.getOrNull(3).orEmpty()
                        .split(',')
                        .map { it.trim().removePrefix("public ").removePrefix("protected ").removePrefix("private ") }
                        .filter { it.isNotBlank() }
                    val end = findBodyEnd(lines, index)
                    items += UnrealReflectionItem(
                        name = cppName,
                        cppName = cppName,
                        module = module,
                        kind = kind,
                        source = toRelative(file),
                        line = index,
                        apiMacro = match.groupValues[1].takeIf { it.endsWith("_API") },
                        baseTypes = bases,
                        ueMacro = "${macro.name}(${macro.args})",
                        specifiers = splitMacroArgs(macro.args).filter { !it.contains('=') },
                        metadata = parseMetadata(macro.args),
                        children = parseChildren(lines, file, module, cppName, index, end),
                    )
                    pendingMacro = null
                    return@forEachIndexed
                }
            }

            if (trimmed.isNotEmpty() && !trimmed.startsWith("//")) pendingMacro = null
        }
        return items
    }

    private fun parseChildren(
        lines: List<String>,
        file: Path,
        module: String,
        className: String,
        start: Int,
        end: Int,
    ): List<UnrealReflectionItem> {
        val children = mutableListOf<UnrealReflectionItem>()
        var pendingMacro: MacroInfo? = null
        for (i in start + 1..end.coerceAtMost(lines.lastIndex)) {
            val trimmed = lines[i].trim()
            MACRO.matchEntire(trimmed)?.let {
                pendingMacro = MacroInfo(it.groupValues[1], it.groupValues[2])
                continue
            }

            val macro = pendingMacro ?: continue
            when (macro.name) {
                "UFUNCTION" -> FUNCTION_DECL.find(trimmed)?.let { match ->
                    children += UnrealReflectionItem(
                        name = "$className/${match.groupValues[2]}",
                        cppName = match.groupValues[2],
                        module = module,
                        kind = "function",
                        source = toRelative(file),
                        line = i,
                        ueMacro = "${macro.name}(${macro.args})",
                        specifiers = splitMacroArgs(macro.args).filter { !it.contains('=') },
                        metadata = parseMetadata(macro.args),
                        type = match.groupValues[1].trim(),
                        signature = trimmed,
                    )
                    pendingMacro = null
                }

                "UPROPERTY" -> PROPERTY_DECL.find(trimmed)?.let { match ->
                    children += UnrealReflectionItem(
                        name = "$className/${match.groupValues[2]}",
                        cppName = match.groupValues[2],
                        module = module,
                        kind = "property",
                        source = toRelative(file),
                        line = i,
                        ueMacro = "${macro.name}(${macro.args})",
                        specifiers = splitMacroArgs(macro.args).filter { !it.contains('=') },
                        metadata = parseMetadata(macro.args),
                        type = match.groupValues[1].trim(),
                        signature = trimmed,
                    )
                    pendingMacro = null
                }
            }
        }
        return children
    }

    private fun buildAssetIndex(): List<UnrealAssetItem> {
        val roots = mutableListOf<Pair<Path, String>>()
        val content = root.resolve("Content")
        if (content.isDirectory()) roots += content to "/Game"
        val plugins = root.resolve("Plugins")
        if (plugins.isDirectory()) {
            Files.list(plugins).use { stream ->
                stream.filter { it.isDirectory() }.forEach { plugin ->
                    val pluginContent = plugin.resolve("Content")
                    if (pluginContent.isDirectory()) roots += pluginContent to "/${plugin.name}"
                }
            }
        }
        return roots.flatMap { (contentRoot, mount) ->
            Files.walk(contentRoot).use { stream ->
                stream.filter { it.isRegularFile() && it.extension.equals("uasset", ignoreCase = true) && !isIgnoredAssetPath(it) }
                    .map { assetPath ->
                        val relNoExt = assetPath.relativeTo(contentRoot).toString().replace('\\', '/').removeSuffix(".uasset")
                        val packagePath = "$mount/$relNoExt"
                        UnrealAssetItem(
                            name = assetPath.nameWithoutExtension,
                            packagePath = packagePath,
                            objectPath = "$packagePath.${assetPath.nameWithoutExtension}",
                            relativePath = toRelative(assetPath),
                            kind = inferAssetKind(assetPath.nameWithoutExtension),
                        )
                    }
                    .toList()
            }
        }
    }

    private fun buildAssetReferenceIndex(): List<UnrealAssetReference> {
        val files = candidateTextFiles(listOf("Source", "Config", "Plugins"), setOf("h", "hpp", "cpp", "cxx", "cc", "ini", "cs"))
        return files.flatMap { file ->
            val relativePath = toRelative(file)
            file.readText().lines().flatMapIndexed { index, line ->
                ASSET_PATH.findAll(line).map { match ->
                    UnrealAssetReference(
                        assetPath = match.value.trim('"'),
                        relativePath = relativePath,
                        line = index,
                        sourceLine = line.trim(),
                        referenceKind = inferReferenceKind(line),
                    )
                }.toList()
            }
        }
    }

    private fun candidateTextFiles(relativeRoots: List<String>, extensions: Set<String>): List<Path> {
        return relativeRoots.map { root.resolve(it) }
            .filter { it.isDirectory() }
            .flatMap { base ->
                Files.walk(base).use { stream ->
                    stream.filter { it.isRegularFile() && it.extension.lowercase() in extensions && !isIgnoredTextPath(it) }
                        .toList()
                }
            }
    }

    private fun moduleFor(file: Path): String {
        val rel = toRelative(file).split('/')
        return when {
            rel.firstOrNull() == "Source" && rel.size > 1 -> rel[1]
            rel.firstOrNull() == "Plugins" && rel.size > 3 && rel[2] == "Source" -> rel[3]
            else -> ""
        }
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

    private fun splitMacroArgs(args: String): List<String> {
        return args.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("meta", ignoreCase = true) }
    }

    private fun parseMetadata(args: String): Map<String, String> {
        val meta = Regex("""meta\s*=\s*\((.*)\)""").find(args)?.groupValues?.get(1) ?: return emptyMap()
        return meta.split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .associate {
                val parts = it.split('=', limit = 2)
                parts[0].trim() to parts.getOrElse(1) { "" }.trim().trim('"')
            }
    }

    private fun isIgnoredTextPath(path: Path): Boolean {
        val rel = toRelative(path)
        return IGNORED_TEXT_DIRS.any { rel == it || rel.startsWith("$it/") || rel.contains("/$it/") }
    }

    private fun isIgnoredAssetPath(path: Path): Boolean {
        val rel = toRelative(path)
        return IGNORED_ASSET_DIRS.any { rel == it || rel.startsWith("$it/") || rel.contains("/$it/") }
    }

    private fun inferAssetKind(name: String): String = when {
        name.startsWith("BP_") || name.startsWith("WBP_") || name.startsWith("ABP_") -> "Blueprint"
        name.startsWith("SM_") -> "StaticMesh"
        name.startsWith("SK_") -> "SkeletalMesh"
        name.startsWith("MI_") -> "MaterialInstance"
        name.startsWith("M_") -> "Material"
        name.startsWith("T_") -> "Texture"
        name.startsWith("IA_") -> "InputAction"
        name.startsWith("IMC_") -> "InputMappingContext"
        name.startsWith("DT_") -> "DataTable"
        name.startsWith("DA_") -> "DataAsset"
        name.startsWith("LS_") -> "LevelSequence"
        else -> "Asset"
    }

    private fun inferReferenceKind(line: String): String = when {
        line.contains("TSoftObjectPtr") -> "TSoftObjectPtr"
        line.contains("TSoftClassPtr") -> "TSoftClassPtr"
        line.contains("FSoftObjectPath") -> "FSoftObjectPath"
        line.contains("FSoftClassPath") -> "FSoftClassPath"
        line.contains("ConstructorHelpers") -> "ConstructorHelpers"
        line.contains("LoadObject") -> "LoadObject"
        line.contains("LoadClass") -> "LoadClass"
        line.contains("AllowedClasses") || line.contains("MustImplement") || line.contains("RequiredAssetDataTags") -> "UPROPERTY metadata"
        else -> "literal"
    }

    private fun toRelative(path: Path): String = path.toAbsolutePath().normalize().relativeTo(root).toString().replace('\\', '/')

    private fun <T> paged(items: List<T>, request: UnrealQuery, mapper: (T) -> Map<String, Any?>): Map<String, Any?> {
        val offset = request.offset.coerceAtLeast(0)
        val limit = request.limit.coerceIn(1, 500)
        return mapOf(
            "items" to items.drop(offset).take(limit).map(mapper),
            "total" to items.size,
            "limit" to limit,
            "offset" to offset,
            "warnings" to emptyList<String>(),
        )
    }

    companion object {
        const val ASSET_GRAPH_WARNING = "V1 tracks code/config asset references only; .uasset-to-.uasset package dependency graph is unavailable in Rider-only mode."
        private val TYPE_MACROS = setOf("UCLASS", "USTRUCT", "UENUM", "UINTERFACE")
        private val MACRO = Regex("""\s*(UCLASS|USTRUCT|UENUM|UINTERFACE|UFUNCTION|UPROPERTY|UDELEGATE)\s*\((.*)\)\s*""")
        private val TYPE_DECL = Regex("""\b(?:class|struct|enum\s+class|enum)\s+(?:(\w+_API)\s+)?(\w+)(?:\s*:\s*([^{]+))?""")
        private val FUNCTION_DECL = Regex("""([\w:<>,*&\s]+?)\s+(\w+)\s*\([^;{}]*\)\s*(?:const\s*)?(?:override\s*)?(?:final\s*)?[;{]?""")
        private val PROPERTY_DECL = Regex("""([\w:<>,*&\s]+?)\s+(\w+)\s*(?:[=;{])""")
        private val ASSET_PATH = Regex("""/(?:Game|Engine|Script|[A-Za-z][A-Za-z0-9_]+)/(?:[A-Za-z0-9_\-]+/)*[A-Za-z0-9_\-]+(?:\.[A-Za-z0-9_]+)?""")
        private val IGNORED_TEXT_DIRS = setOf(".git", ".idea", ".gradle", "Binaries", "DerivedDataCache", "Intermediate", "Saved")
        private val IGNORED_ASSET_DIRS = setOf("__ExternalActors__", "__ExternalObjects__", "DerivedDataCache", "Intermediate", "Saved")
    }
}

data class UnrealQuery(
    val query: String?,
    val kind: String?,
    val pathPrefix: String?,
    val module: String?,
    val limit: Int,
    val offset: Int,
    val includeChildren: Boolean,
    val includeGenerated: Boolean,
)

private data class MacroInfo(val name: String, val args: String)

private data class UnrealReflectionItem(
    val name: String,
    val cppName: String,
    val module: String,
    val kind: String,
    val source: String,
    val line: Int,
    val apiMacro: String? = null,
    val baseTypes: List<String> = emptyList(),
    val ueMacro: String? = null,
    val specifiers: List<String> = emptyList(),
    val metadata: Map<String, String> = emptyMap(),
    val type: String? = null,
    val signature: String? = null,
    val flags: List<String> = emptyList(),
    val children: List<UnrealReflectionItem> = emptyList(),
) {
    fun matches(query: String): Boolean {
        return name.contains(query, ignoreCase = true) ||
            cppName.contains(query, ignoreCase = true) ||
            source.contains(query, ignoreCase = true) ||
            specifiers.any { it.contains(query, ignoreCase = true) } ||
            metadata.any { it.key.contains(query, ignoreCase = true) || it.value.contains(query, ignoreCase = true) }
    }

    fun toJson(includeChildren: Boolean): Map<String, Any?> {
        val result = linkedMapOf<String, Any?>(
            "name" to name,
            "cppName" to cppName,
            "module" to module,
            "kind" to kind,
            "source" to source,
            "range" to mapOf("startLine" to line, "endLine" to line),
        )
        if (apiMacro != null) result["apiMacro"] = apiMacro
        if (baseTypes.isNotEmpty()) result["baseTypes"] = baseTypes
        if (ueMacro != null) result["ueMacro"] = ueMacro
        if (specifiers.isNotEmpty()) result["specifiers"] = specifiers
        if (metadata.isNotEmpty()) result["metadata"] = metadata
        if (type != null) result["type"] = type
        if (signature != null) result["signature"] = signature
        if (flags.isNotEmpty()) result["flags"] = flags
        if (includeChildren && children.isNotEmpty()) result["children"] = children.map { it.toJson(includeChildren = false) }
        return result
    }
}

private data class UnrealAssetItem(
    val name: String,
    val packagePath: String,
    val objectPath: String,
    val relativePath: String,
    val kind: String,
) {
    fun matches(query: String): Boolean {
        return name.contains(query, ignoreCase = true) ||
            packagePath.contains(query, ignoreCase = true) ||
            objectPath.contains(query, ignoreCase = true) ||
            relativePath.contains(query, ignoreCase = true)
    }

    fun toJson(): Map<String, Any?> = mapOf(
        "name" to name,
        "packagePath" to packagePath,
        "objectPath" to objectPath,
        "relativePath" to relativePath,
        "kind" to kind,
    )
}

private data class UnrealAssetReference(
    val assetPath: String,
    val relativePath: String,
    val line: Int,
    val sourceLine: String,
    val referenceKind: String,
) {
    fun toJson(): Map<String, Any?> = mapOf(
        "assetPath" to assetPath,
        "relativePath" to relativePath,
        "line" to line,
        "sourceLine" to sourceLine,
        "referenceKind" to referenceKind,
    )
}
