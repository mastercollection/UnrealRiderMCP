using JetBrains.Application.Parts;
using JetBrains.Application.Components;
using JetBrains.Application.Progress;
using JetBrains.Application.Threading;
using JetBrains.Collections.Viewable;
using JetBrains.Lifetimes;
using JetBrains.ProjectModel;
using JetBrains.Rd.Tasks;
using JetBrains.ReSharper.Daemon.Impl;
using JetBrains.ReSharper.Feature.Services.Daemon;
using JetBrains.ReSharper.Psi;
using JetBrains.ReSharper.Psi.Caches;
using JetBrains.ReSharper.Psi.Cpp.Symbols;
using JetBrains.ReSharper.Psi.Cpp.UE4;
using JetBrains.ReSharper.Psi.Files;
using JetBrains.ReSharper.Psi.Resolve;
using JetBrains.ReSharper.Psi.Search;
using JetBrains.ReSharper.Psi.Tree;
using JetBrains.ReSharper.Feature.Services.Cpp.UE4.UEAsset;
using JetBrains.ReSharper.Feature.Services.Cpp.UE4.UEAsset.Search;
using JetBrains.ReSharper.Feature.Services.Occurrences;
using JetBrains.ReSharper.Feature.Services.Protocol;
using JetBrains.Util;
using System.Collections.Concurrent;
using System.Text.Json;

namespace UnrealPasser.Backend;

[SolutionComponent(Instantiation.DemandAnyThreadSafe)]
public sealed class BackendComponent
{
    private static readonly JsonSerializerOptions JsonOptions = new()
    {
        PropertyNameCaseInsensitive = true,
    };

    public BackendComponent(
        Lifetime lifetime,
        ISolution solution,
        IHighlightingSettingsManager highlightingSettingsManager,
        DaemonImpl daemon,
        IPersistentIndexManager persistentIndexManager,
        UE4AssetsCache assetsCache,
        UEAssetUsagesSearcher assetUsagesSearcher)
    {
        Lifetime = lifetime;
        Solution = solution;
        HighlightingSettingsManager = highlightingSettingsManager;
        Daemon = daemon;
        PersistentIndexManager = persistentIndexManager;
        AssetsCache = assetsCache;
        AssetUsagesSearcher = assetUsagesSearcher;
        var protocolSolution = solution.GetProtocolSolution();
        var model = protocolSolution.GetUnrealPasserBackendModel();
        model.Execute.SetSync(Execute, null, null);
        Console.WriteLine("UnrealPasser BackendComponent bound RD model.");
    }

    public Lifetime Lifetime { get; }

    public ISolution Solution { get; }

    public IHighlightingSettingsManager HighlightingSettingsManager { get; }

    public DaemonImpl Daemon { get; }

    public IPersistentIndexManager PersistentIndexManager { get; }

    public UE4AssetsCache AssetsCache { get; }

    public UEAssetUsagesSearcher AssetUsagesSearcher { get; }

    public BackendApiAvailability Availability => BackendApiAvailability.Current;

    private string Execute(string request)
    {
        BackendRequest? parsed;
        try
        {
            parsed = JsonSerializer.Deserialize<BackendRequest>(request, JsonOptions);
        }
        catch (JsonException exception)
        {
            return BackendError.BadRequest($"Invalid backend request JSON: {exception.Message}").ToJson();
        }

        if (string.IsNullOrWhiteSpace(parsed?.Operation))
            return BackendError.BadRequest("Backend request is missing the 'operation' field.").ToJson();

        return parsed.Operation switch
        {
            "status" => Availability.ToJson(),
            "getSymbolsOverview" => GetSymbolsOverview(parsed.Payload),
            "findSymbol" => FindSymbol(parsed.Payload),
            "findDeclaration" => FindDeclaration(parsed.Payload),
            "findReferences" => FindReferences(parsed.Payload),
            "findImplementations" => FindImplementations(parsed.Payload),
            "getSupertypes" => GetSupertypes(parsed.Payload),
            "getSubtypes" => GetSubtypes(parsed.Payload),
            "listInspections" => ListInspections(),
            "runInspectionsOnFile" => RunInspectionsOnFile(parsed.Payload),
            "unrealCapabilities" => UnrealCapabilities(),
            "unrealProjectStatus" => UnrealProjectStatus(),
            "unrealReflectionSearch" => UnrealReflectionSearch(parsed.Payload),
            "unrealReflectionGet" => UnrealReflectionGet(parsed.Payload),
            "unrealAssetSearch" => UnrealAssetSearch(parsed.Payload),
            "unrealAssetReferences" => UnrealAssetReferences(parsed.Payload),
            _ => BackendError.UnknownOperation(parsed.Operation).ToJson(),
        };
    }

    private string GetSymbolsOverview(JsonElement payload)
    {
        var relativePath = payload.GetRequiredString("relativePath");
        var depth = payload.GetInt32OrDefault("depth", 0);

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var sourceFile = ResolveSourceFile(relativePath);
            if (sourceFile == null)
                return BackendError.PsiUnavailable($"Unable to resolve PSI source file for '{relativePath}'.").ToJson();

            var psiFile = sourceFile.GetPsiServices().Files.GetPsiFiles(sourceFile).FirstOrDefault();
            if (psiFile == null)
                return BackendError.PsiUnavailable($"ReSharper PSI file is unavailable for '{relativePath}'.").ToJson();

            var symbols = new List<Dictionary<string, object?>>();
            CollectDeclarations(psiFile, relativePath, depth, includeBody: false, symbols);
            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = symbols });
        });
    }

    private string FindSymbol(JsonElement payload)
    {
        var namePath = payload.GetRequiredString("namePath");
        var relativePath = payload.GetStringOrNull("relativePath");
        var includeBody = payload.GetBooleanOrDefault("includeBody", false);
        var depth = payload.GetInt32OrDefault("depth", 0);

        if (string.IsNullOrWhiteSpace(relativePath))
            return BackendError.Unsupported("findSymbol without relativePath").ToJson();

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var sourceFile = ResolveSourceFile(relativePath);
            if (sourceFile == null)
                return BackendError.PsiUnavailable($"Unable to resolve PSI source file for '{relativePath}'.").ToJson();

            var psiFile = sourceFile.GetPsiServices().Files.GetPsiFiles(sourceFile).FirstOrDefault();
            if (psiFile == null)
                return BackendError.PsiUnavailable($"ReSharper PSI file is unavailable for '{relativePath}'.").ToJson();

            var symbols = new List<Dictionary<string, object?>>();
            CollectDeclarations(psiFile, relativePath, depth, includeBody, symbols);
            var matches = symbols
                .Where(symbol => SymbolMatches(symbol, namePath))
                .ToList();
            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = matches });
        });
    }

    private string FindDeclaration(JsonElement payload)
    {
        var relativePath = payload.GetRequiredString("relativePath");
        var includeBody = payload.GetBooleanOrDefault("includeBody", false);
        var position = payload.GetRequiredObject("position");
        var line = position.GetInt32OrDefault("line", 0);
        var col = position.GetInt32OrDefault("col", 0);

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var sourceFile = ResolveSourceFile(relativePath);
            if (sourceFile == null)
                return BackendError.PsiUnavailable($"Unable to resolve PSI source file for '{relativePath}'.").ToJson();

            var psiFile = sourceFile.GetPsiServices().Files.GetPsiFiles(sourceFile).FirstOrDefault();
            if (psiFile == null)
                return BackendError.PsiUnavailable($"ReSharper PSI file is unavailable for '{relativePath}'.").ToJson();

            var offset = OffsetFromLineCol(sourceFile.Document.GetText(), line, col);
            var node = psiFile.FindNodeAt(new TreeOffset(offset));
            var declaration = FindContainingDeclaration(node);
            var symbols = declaration == null
                ? new List<Dictionary<string, object?>>()
                : new List<Dictionary<string, object?>> { BuildDeclarationSymbol(declaration, relativePath, includeBody) };
            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = symbols });
        });
    }

    private string FindReferences(JsonElement payload)
    {
        var namePath = payload.GetRequiredString("namePath");
        var relativePath = payload.GetRequiredString("relativePath");

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var declaredElement = FindDeclaredElement(relativePath, namePath);
            if (declaredElement == null)
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = Array.Empty<object>() });

            var references = new List<Dictionary<string, object?>>();
            var psiServices = Solution.GetPsiServices();
            var domain = psiServices.SearchDomainFactory.CreateSearchDomain(Solution, includeLibraries: false);
            var consumer = new FindResultConsumer<Dictionary<string, object?>?>(
                result => BuildReferenceSymbol(result, declaredElement.ShortName),
                item =>
                {
                    if (item != null)
                        references.Add(item);
                    return FindExecution.Continue;
                });
            psiServices.Finder.FindReferences(
                declaredElement,
                domain,
                consumer,
                new ProgressIndicator(Lifetime),
                includeDynamic: false);

            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = references });
        });
    }

    private string FindImplementations(JsonElement payload)
    {
        var namePath = payload.GetRequiredString("namePath");
        var relativePath = payload.GetRequiredString("relativePath");

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var declaredElement = FindDeclaredElement(relativePath, namePath);
            if (declaredElement == null)
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = Array.Empty<object>() });

            if (declaredElement is ITypeElement typeElement)
            {
                var symbols = FindInheritorElements(typeElement)
                    .Select(BuildDeclaredElementSymbol)
                    .Where(symbol => symbol != null)
                    .ToList();
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = symbols });
            }

            if (declaredElement is IOverridableMember overridableMember &&
                declaredElement is ITypeMember typeMember)
            {
                if (typeMember.ContainingType == null)
                    return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = Array.Empty<object>() });

                var symbols = FindImplementingMembers(overridableMember, typeMember.ContainingType)
                    .Select(BuildDeclaredElementSymbol)
                    .Where(symbol => symbol != null)
                    .ToList();
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = symbols });
            }

            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["symbols"] = Array.Empty<object>() });
        });
    }

    private string GetSupertypes(JsonElement payload)
    {
        var namePath = payload.GetRequiredString("namePath");
        var relativePath = payload.GetRequiredString("relativePath");
        var depth = payload.GetInt32OrDefault("depth", 1);

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var declaredElement = FindDeclaredElement(relativePath, namePath);
            if (declaredElement == null)
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["hierarchy"] = Array.Empty<object>() });

            var superTypes = GetImmediateSuperElements(declaredElement);
            var hierarchy = superTypes
                .Select(element => BuildHierarchyNode(element, depth - 1, parentDirection: true))
                .Where(node => node != null)
                .ToList();

            if (hierarchy.Count == 0)
            {
                var declaration = FindDeclaration(relativePath, namePath);
                hierarchy.AddRange(GetCppBaseNamesFromDeclaration(declaration)
                    .Select(baseName => BuildSyntheticHierarchyNode(baseName, depth - 1))
                    .Where(node => node != null));
            }

            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["hierarchy"] = hierarchy });
        });
    }

    private string GetSubtypes(JsonElement payload)
    {
        var namePath = payload.GetRequiredString("namePath");
        var relativePath = payload.GetRequiredString("relativePath");
        var depth = payload.GetInt32OrDefault("depth", 1);

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var declaredElement = FindDeclaredElement(relativePath, namePath);
            if (declaredElement is not ITypeElement typeElement)
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["hierarchy"] = Array.Empty<object>() });

            var hierarchy = FindInheritorElements(typeElement)
                .Select(element => BuildHierarchyNode(element, depth - 1, parentDirection: false))
                .Where(node => node != null)
                .ToList();
            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["hierarchy"] = hierarchy });
        });
    }

    private string ListInspections()
    {
        try
        {
            var inspections = EnumerateInspectionCatalog().ToList();
            return JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["inspections"] = inspections,
                ["catalogAvailable"] = inspections.Count > 0,
                ["message"] = inspections.Count > 0
                    ? "Inspection catalog was enumerated from loaded ReSharper highlighting and inspection types."
                    : "No ReSharper inspection catalog entries were visible from the backend component container; use runInspectionsOnFile for concrete daemon results."
            });
        }
        catch (Exception exception)
        {
            return JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["inspections"] = Array.Empty<Dictionary<string, object?>>(),
                ["catalogAvailable"] = false,
                ["catalogError"] = exception.ToString(),
                ["message"] = "ReSharper daemon inspection results are available via runInspectionsOnFile; static inspection catalog enumeration failed in this backend container."
            });
        }
    }

    private string RunInspectionsOnFile(JsonElement payload)
    {
        var relativePath = payload.GetRequiredString("relativePath");

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var sourceFile = ResolveSourceFile(relativePath);
            if (sourceFile == null)
                return BackendError.PsiUnavailable($"Unable to resolve PSI source file for '{relativePath}'.").ToJson();

            Daemon.ForceReHighlight(sourceFile.Document);
            var daemonProcess = Daemon.TryGetExistingDaemonProcess(sourceFile.Document);
            if (daemonProcess == null)
                return BackendError.DaemonUnavailable($"ReSharper daemon process is unavailable for '{relativePath}'.").ToJson();

            var issues = new List<Dictionary<string, object?>>();
            foreach (var stageProcess in daemonProcess.GetCompletedStageProcesses())
            {
                stageProcess.Execute(result =>
                {
                    foreach (var highlighting in result.Highlightings)
                        issues.Add(BuildInspectionIssue(sourceFile, daemonProcess, highlighting));
                });
            }

            return JsonSerializer.Serialize(new Dictionary<string, object?> { ["inspections"] = issues });
        });
    }

    private static IEnumerable<Dictionary<string, object?>> EnumerateInspectionCatalog()
    {
        return AppDomain.CurrentDomain.GetAssemblies()
            .Where(assembly =>
            {
                var name = assembly.GetName().Name ?? string.Empty;
                return name.StartsWith("JetBrains.ReSharper", StringComparison.Ordinal) ||
                    name.StartsWith("JetBrains.Rider", StringComparison.Ordinal);
            })
            .SelectMany(SafeGetTypes)
            .Where(IsInspectionCatalogType)
            .OrderBy(type => type.FullName, StringComparer.Ordinal)
            .Select(type => new Dictionary<string, object?>
            {
                ["inspectionId"] = type.Name,
                ["severity"] = InferInspectionSeverity(type.Name),
                ["message"] = type.FullName,
                ["source"] = type.Assembly.GetName().Name,
                ["quickFixAvailable"] = false,
            });
    }

    private static IEnumerable<Type> SafeGetTypes(System.Reflection.Assembly assembly)
    {
        try
        {
            return assembly.GetTypes();
        }
        catch (System.Reflection.ReflectionTypeLoadException exception)
        {
            return exception.Types.Where(type => type != null)!;
        }
        catch
        {
            return Array.Empty<Type>();
        }
    }

    private static bool IsInspectionCatalogType(Type type)
    {
        if (type.IsAbstract || type.IsGenericTypeDefinition)
            return false;

        var fullName = type.FullName ?? string.Empty;
        if (fullName.Contains("+", StringComparison.Ordinal))
            return false;

        return fullName.Contains(".Daemon.Highlightings.", StringComparison.Ordinal) ||
            fullName.Contains(".CodeStyle.Inspections.", StringComparison.Ordinal) ||
            fullName.Contains(".Inspections.", StringComparison.Ordinal);
    }

    private static string InferInspectionSeverity(string typeName)
    {
        if (typeName.Contains("Error", StringComparison.OrdinalIgnoreCase))
            return "ERROR";
        if (typeName.Contains("Warning", StringComparison.OrdinalIgnoreCase))
            return "WARNING";
        if (typeName.Contains("Suggestion", StringComparison.OrdinalIgnoreCase))
            return "SUGGESTION";
        if (typeName.Contains("Hint", StringComparison.OrdinalIgnoreCase))
            return "HINT";
        return "INFO";
    }

    private string UnrealCapabilities()
    {
        return JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["source"] = "resharper-unreal",
            ["projectStatus"] = true,
            ["reflection"] = true,
            ["assetSearch"] = true,
            ["assetReferences"] = true,
            ["assetPackageGraph"] = false,
            ["diagnosticFallback"] = false,
            ["backendApis"] = Availability.UnrealApis,
            ["warnings"] = new[]
            {
                "Unreal reflection operations classify ReSharper C++ PSI declarations with CppUE4Util. Asset search/reference operations use UE4AssetsCache and UEAssetUsagesSearcher.",
            },
        });
    }

    private string UnrealProjectStatus()
    {
        return JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["source"] = "resharper-unreal",
            ["backend"] = "rd",
            ["projectRoot"] = Solution.SolutionDirectory.FileAccessPath.Replace('\\', '/'),
            ["unrealApis"] = Availability.UnrealApis,
        });
    }

    private string UnrealReflectionSearch(JsonElement payload)
    {
        var query = payload.GetStringOrNull("query") ?? payload.GetStringOrNull("namePath");
        var kind = payload.GetStringOrNull("kind");
        var relativePath = payload.GetStringOrNull("relativePath");
        var pathPrefix = payload.GetStringOrNull("pathPrefix");
        var includeGenerated = payload.GetBooleanOrDefault("includeGenerated", false);
        var limit = payload.GetInt32OrDefault("limit", 50);
        var offset = payload.GetInt32OrDefault("offset", 0);

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var sourceFiles = EnumerateReflectionSourceFiles(relativePath, pathPrefix, includeGenerated);
            var items = sourceFiles
                .SelectMany(CollectUnrealReflectionItems)
                .Where(item => MatchesReflectionQuery(item, query, kind))
                .OrderBy(item => item.TryGetValue("relativePath", out var path) ? path as string : string.Empty)
                .ThenBy(item => item.TryGetValue("namePath", out var name) ? name as string : string.Empty)
                .Skip(Math.Max(0, offset))
                .Take(Math.Max(1, limit))
                .ToList();

            return JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["items"] = items,
                ["source"] = "resharper-unreal",
                ["providerDetail"] = "IPersistentIndexManager.GetAllSourceFiles + CppUE4Util",
            });
        });
    }

    private string UnrealReflectionGet(JsonElement payload)
    {
        var query = payload.GetStringOrNull("query") ?? payload.GetStringOrNull("namePath");
        if (string.IsNullOrWhiteSpace(query))
            return BackendError.BadRequest("Unreal reflection get requires 'query' or 'namePath'.").ToJson();

        using var document = JsonDocument.Parse(payload.GetRawText());
        var request = document.RootElement.Clone();
        var response = UnrealReflectionSearch(request);
        using var parsed = JsonDocument.Parse(response);
        if (!parsed.RootElement.TryGetProperty("items", out var items) || items.ValueKind != JsonValueKind.Array)
            return response;

        var first = items.EnumerateArray().FirstOrDefault();
        return JsonSerializer.Serialize(new Dictionary<string, object?>
        {
            ["item"] = first.ValueKind == JsonValueKind.Undefined ? null : JsonSerializer.Deserialize<object>(first.GetRawText()),
            ["source"] = "resharper-unreal",
            ["providerDetail"] = "unrealReflectionSearch first result",
        });
    }

    private string UnrealAssetSearch(JsonElement payload)
    {
        var query = payload.GetStringOrNull("query");
        var limit = payload.GetInt32OrDefault("limit", 50);
        var offset = payload.GetInt32OrDefault("offset", 0);
        var pathPrefix = payload.GetStringOrNull("pathPrefix");

        if (string.IsNullOrWhiteSpace(query))
            return BackendError.BadRequest("Unreal asset search requires a non-empty 'query'.").ToJson();

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var files = AssetsCache.GetAssetFilesContainingWord(query)
                .Where(sourceFile => MatchesAssetPathPrefix(sourceFile, pathPrefix))
                .OrderBy(ToRelativePath)
                .Skip(Math.Max(0, offset))
                .Take(Math.Max(1, limit))
                .Select(BuildAssetSearchResult)
                .ToList();

            return JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["assets"] = files,
                ["source"] = "resharper-unreal",
                ["providerDetail"] = "UE4AssetsCache.GetAssetFilesContainingWord",
            });
        });
    }

    private string UnrealAssetReferences(JsonElement payload)
    {
        var namePath = payload.GetRequiredString("namePath");
        var relativePath = payload.GetRequiredString("relativePath");
        var includeChildren = payload.GetBooleanOrDefault("includeChildren", true);
        var limit = payload.GetInt32OrDefault("limit", 50);
        var offset = payload.GetInt32OrDefault("offset", 0);

        return Solution.Locks.ExecuteWithReadLock(() =>
        {
            var declaredElement = FindDeclaredElement(relativePath, namePath);
            if (declaredElement == null)
                return JsonSerializer.Serialize(new Dictionary<string, object?> { ["references"] = Array.Empty<object>() });

            if (!UE4SearchUtil.IsApplicableForUnrealSpecificSearch(declaredElement))
                return JsonSerializer.Serialize(new Dictionary<string, object?>
                {
                    ["references"] = Array.Empty<object>(),
                    ["source"] = "resharper-unreal",
                    ["providerDetail"] = "UE4SearchUtil.IsApplicableForUnrealSpecificSearch=false",
                });

            var searchTargets = UE4SearchUtil.BuildUESearchTargets(declaredElement, includeChildren);
            var accessorCache = new ConcurrentDictionary<IPsiSourceFile, UEAssetFileAccessor>();
            var references = AssetUsagesSearcher
                .FindPossibleReadWriteResults(searchTargets, accessorCache, searchReadOccurrences: true)
                .Skip(Math.Max(0, offset))
                .Take(Math.Max(1, limit))
                .Select(BuildUnrealFindResult)
                .ToList();

            return JsonSerializer.Serialize(new Dictionary<string, object?>
            {
                ["references"] = references,
                ["targets"] = searchTargets.Select(BuildUnrealSearchTarget).ToList(),
                ["source"] = "resharper-unreal",
                ["providerDetail"] = "UE4SearchUtil.BuildUESearchTargets + UEAssetUsagesSearcher.FindPossibleReadWriteResults",
            });
        });
    }

    private IPsiSourceFile? ResolveSourceFile(string relativePath)
    {
        var path = VirtualFileSystemPath.ParseRelativelyTo(relativePath, Solution.SolutionDirectory);
        var projectFile = Solution.FindProjectItemsByLocation(path).OfType<IProjectFile>().FirstOrDefault();
        return projectFile?.ToSourceFile();
    }

    private static void CollectDeclarations(
        ITreeNode root,
        string relativePath,
        int depth,
        bool includeBody,
        ICollection<Dictionary<string, object?>> output)
    {
        var stack = new Stack<(ITreeNode Node, int DeclarationDepth)>();
        stack.Push((root, -1));

        while (stack.Count > 0)
        {
            var (node, declarationDepth) = stack.Pop();
            var nextDeclarationDepth = declarationDepth;

            if (node is IDeclaration declaration && !declaration.IsSynthetic())
            {
                nextDeclarationDepth++;
                if (depth <= 0 || nextDeclarationDepth <= depth)
                    output.Add(BuildDeclarationSymbol(declaration, relativePath, includeBody));
            }

            for (var child = node.LastChild; child != null; child = child.PrevSibling)
                stack.Push((child, nextDeclarationDepth));
        }
    }

    private static Dictionary<string, object?> BuildDeclarationSymbol(
        IDeclaration declaration,
        string relativePath,
        bool includeBody)
    {
        var declaredElement = declaration.DeclaredElement;
        var symbol = new Dictionary<string, object?>
        {
            ["namePath"] = declaredElement?.ShortName ?? declaration.DeclaredName,
            ["relativePath"] = relativePath,
            ["type"] = declaredElement?.GetElementType().ToString() ?? declaration.NodeType.ToString(),
            ["quickInfo"] = declaredElement?.ToString(),
            ["providerDetail"] = "ReSharper PSI IDeclaration",
        };
        if (includeBody)
            symbol["body"] = declaration.GetText();
        return symbol;
    }

    private static IDeclaration? FindContainingDeclaration(ITreeNode? node)
    {
        while (node != null)
        {
            if (node is IDeclaration declaration && !declaration.IsSynthetic())
                return declaration;
            node = node.Parent;
        }
        return null;
    }

    private static int OffsetFromLineCol(string text, int line, int col)
    {
        var currentLine = 0;
        var lineStart = 0;
        for (var i = 0; i < text.Length && currentLine < line; i++)
        {
            if (text[i] != '\n') continue;
            currentLine++;
            lineStart = i + 1;
        }

        return Math.Clamp(lineStart + Math.Max(0, col), 0, text.Length);
    }

    private static bool SymbolMatches(IReadOnlyDictionary<string, object?> symbol, string namePath)
    {
        var symbolName = symbol.TryGetValue("namePath", out var value) ? value as string : null;
        if (string.IsNullOrEmpty(symbolName)) return false;
        return string.Equals(symbolName, namePath, StringComparison.Ordinal) ||
               symbolName.EndsWith("/" + namePath, StringComparison.Ordinal) ||
               symbolName.Contains(namePath, StringComparison.OrdinalIgnoreCase);
    }

    private IDeclaredElement? FindDeclaredElement(string relativePath, string namePath)
    {
        return FindDeclarationAndElement(relativePath, namePath).Element;
    }

    private IDeclaration? FindDeclaration(string relativePath, string namePath)
    {
        return FindDeclarationAndElement(relativePath, namePath).Declaration;
    }

    private (IDeclaredElement? Element, IDeclaration? Declaration) FindDeclarationAndElement(string relativePath, string namePath)
    {
        var sourceFile = ResolveSourceFile(relativePath);
        if (sourceFile == null)
            return (null, null);

        var psiFile = sourceFile.GetPsiServices().Files.GetPsiFiles(sourceFile).FirstOrDefault();
        if (psiFile == null)
            return (null, null);

        IDeclaredElement? match = null;
        IDeclaration? declarationMatch = null;
        var symbols = new List<Dictionary<string, object?>>();
        CollectDeclarations(psiFile, relativePath, depth: 0, includeBody: false, symbols);
        var matchedName = symbols
            .Where(symbol => SymbolMatches(symbol, namePath))
            .Select(symbol => symbol.TryGetValue("namePath", out var value) ? value as string : null)
            .FirstOrDefault();
        if (matchedName == null)
            return (null, null);

        var stack = new Stack<ITreeNode>();
        stack.Push(psiFile);
        while (stack.Count > 0)
        {
            var node = stack.Pop();
            if (node is IDeclaration declaration && !declaration.IsSynthetic())
            {
                var declaredElement = declaration.DeclaredElement;
                if (declaredElement != null && string.Equals(declaredElement.ShortName, matchedName, StringComparison.Ordinal))
                    return (declaredElement, declaration);
                if (declaredElement != null && string.Equals(declaration.DeclaredName, matchedName, StringComparison.Ordinal))
                {
                    match = declaredElement;
                    declarationMatch = declaration;
                }
            }

            for (var child = node.LastChild; child != null; child = child.PrevSibling)
                stack.Push(child);
        }

        return (match, declarationMatch);
    }

    private Dictionary<string, object?>? BuildReferenceSymbol(FindResult result, string fallbackName)
    {
        if (result is not FindResultReference referenceResult)
            return null;

        var node = referenceResult.Reference.GetTreeNode();
        var sourceFile = node.GetSourceFile();
        var relativePath = ToRelativePath(sourceFile);
        var navigationRange = node.GetNavigationRange();
        var startCoords = navigationRange.StartOffset.ToDocumentCoords();
        return new Dictionary<string, object?>
        {
            ["namePath"] = referenceResult.Reference.GetName() ?? fallbackName,
            ["relativePath"] = relativePath,
            ["type"] = "reference",
            ["referenceLineNo"] = (int)startCoords.Line,
            ["providerDetail"] = "ReSharper PSI IFinder.FindReferences",
        };
    }

    private IEnumerable<IDeclaredElement> FindInheritorElements(ITypeElement typeElement)
    {
        var results = new List<IDeclaredElement>();
        var consumer = new FindResultConsumer<IDeclaredElement?>(
            result => result is FindResultDeclaredElement declared ? declared.DeclaredElement : null,
            element =>
            {
                if (element != null)
                    results.Add(element);
                return FindExecution.Continue;
            });
        Solution.GetPsiServices().Finder.FindInheritors(
            typeElement,
            consumer,
            new ProgressIndicator(Lifetime));
        return results;
    }

    private IEnumerable<IDeclaredElement> FindImplementingMembers(
        IOverridableMember member,
        ITypeElement originType)
    {
        var results = new List<IDeclaredElement>();
        var consumer = new FindResultConsumer<IDeclaredElement?>(
            result => result is FindResultDeclaredElement declared ? declared.DeclaredElement : null,
            element =>
            {
                if (element != null)
                    results.Add(element);
                return FindExecution.Continue;
            });
        var psiServices = Solution.GetPsiServices();
        var domain = psiServices.SearchDomainFactory.CreateSearchDomain(Solution, includeLibraries: false);
        psiServices.Finder.FindImplementingMembers(
            member,
            originType,
            EmptySubstitution.INSTANCE,
            domain,
            consumer,
            SearchImplementorsPattern.DEFAULT,
            new ProgressIndicator(Lifetime));
        return results;
    }

    private IEnumerable<IDeclaredElement> GetImmediateSuperElements(IDeclaredElement element)
    {
        if (element is ICppClassResolveEntity cppClass)
            return cppClass.GetNonDependentBases()
                .ToEnumerable()
                .Select(baseDescription => baseDescription.Entity())
                .Cast<IDeclaredElement>();

        if (element is ITypeElement typeElement)
            return typeElement.GetSuperTypeElements().Cast<IDeclaredElement>();

        return Solution.GetPsiServices().Finder.FindImmediateBaseElements(
            element,
            new ProgressIndicator(Lifetime),
            searchQuasi: true);
    }

    private Dictionary<string, object?>? BuildHierarchyNode(
        IDeclaredElement element,
        int remainingDepth,
        bool parentDirection)
    {
        var symbol = BuildDeclaredElementSymbol(element);
        if (symbol == null)
            return null;

        var node = new Dictionary<string, object?> { ["symbol"] = symbol };
        if (remainingDepth <= 0)
            return node;

        IEnumerable<IDeclaredElement> next = Array.Empty<IDeclaredElement>();
        if (parentDirection)
        {
            next = GetImmediateSuperElements(element);
        }
        else if (element is ITypeElement typeElement)
        {
            next = FindInheritorElements(typeElement);
        }

        var children = next
            .Select(child => BuildHierarchyNode(child, remainingDepth - 1, parentDirection))
            .Where(child => child != null)
            .ToList();
        if (children.Count > 0)
            node["children"] = children;
        return node;
    }

    private Dictionary<string, object?>? BuildSyntheticHierarchyNode(string namePath, int remainingDepth)
    {
        var symbol = FindSymbolSummaryByName(namePath) ?? new Dictionary<string, object?>
        {
            ["namePath"] = namePath,
            ["relativePath"] = null,
            ["type"] = "class",
            ["quickInfo"] = namePath,
            ["providerDetail"] = "ReSharper C++ PSI base-clause"
        };

        var node = new Dictionary<string, object?> { ["symbol"] = symbol };
        if (remainingDepth <= 0)
            return node;

        var relativePath = symbol.TryGetValue("relativePath", out var pathValue) ? pathValue as string : null;
        if (string.IsNullOrWhiteSpace(relativePath))
            return node;

        var children = GetCppBaseNamesFromDeclaration(FindDeclaration(relativePath, namePath))
            .Select(baseName => BuildSyntheticHierarchyNode(baseName, remainingDepth - 1))
            .Where(child => child != null)
            .ToList();
        if (children.Count > 0)
            node["children"] = children;
        return node;
    }

    private Dictionary<string, object?>? FindSymbolSummaryByName(string namePath)
    {
        foreach (var sourceFile in EnumerateReflectionSourceFiles(relativePath: null, pathPrefix: "Source", includeGenerated: false))
        {
            var relativePath = TryToRelativePath(sourceFile);
            if (relativePath == null)
                continue;

            foreach (var psiFile in SafeGetPsiFiles(sourceFile))
            {
                var symbols = new List<Dictionary<string, object?>>();
                CollectDeclarations(psiFile, relativePath, depth: 0, includeBody: false, symbols);
                var match = symbols.FirstOrDefault(symbol => SymbolMatches(symbol, namePath));
                if (match != null)
                {
                    match["providerDetail"] = "ReSharper C++ PSI declaration lookup";
                    return match;
                }
            }
        }

        return null;
    }

    private Dictionary<string, object?>? BuildDeclaredElementSymbol(IDeclaredElement element)
    {
        var declaration = element.GetDeclarations().FirstOrDefault();
        var sourceFile = declaration?.GetSourceFile() ?? element.GetSourceFiles().FirstOrDefault();
        if (sourceFile == null)
            return null;

        var symbol = new Dictionary<string, object?>
        {
            ["namePath"] = element.ShortName,
            ["relativePath"] = ToRelativePath(sourceFile),
            ["type"] = element.GetElementType().ToString(),
            ["quickInfo"] = element.ToString(),
            ["providerDetail"] = "ReSharper PSI IDeclaredElement",
        };
        return symbol;
    }

    private IEnumerable<IPsiSourceFile> EnumerateReflectionSourceFiles(
        string? relativePath,
        string? pathPrefix,
        bool includeGenerated)
    {
        if (!string.IsNullOrWhiteSpace(relativePath))
        {
            var sourceFile = ResolveSourceFile(relativePath);
            return sourceFile == null ? Array.Empty<IPsiSourceFile>() : new[] { sourceFile };
        }

        var normalizedPrefix = pathPrefix?.Replace('\\', '/');
        return PersistentIndexManager.GetAllSourceFiles()
            .Where(sourceFile =>
            {
                var path = TryToRelativePath(sourceFile);
                if (path == null)
                    return false;
                if (!IsUnrealReflectionSourcePath(path, includeGenerated))
                    return false;
                if (!includeGenerated && path.Contains("/Intermediate/", StringComparison.OrdinalIgnoreCase))
                    return false;
                if (string.IsNullOrWhiteSpace(normalizedPrefix))
                    return true;
                return path.StartsWith(normalizedPrefix, StringComparison.OrdinalIgnoreCase);
            })
            .ToList();
    }

    private IEnumerable<Dictionary<string, object?>> CollectUnrealReflectionItems(IPsiSourceFile sourceFile)
    {
        var relativePath = TryToRelativePath(sourceFile);
        if (relativePath == null)
            yield break;

        foreach (var psiFile in SafeGetPsiFiles(sourceFile))
        {
            var stack = new Stack<ITreeNode>();
            stack.Push(psiFile);
            while (stack.Count > 0)
            {
                var node = stack.Pop();
                if (node is IDeclaration declaration && !declaration.IsSynthetic())
                {
                    var item = BuildUnrealReflectionItem(declaration, relativePath);
                    if (item != null)
                        yield return item;
                }

                for (var child = node.LastChild; child != null; child = child.PrevSibling)
                    stack.Push(child);
            }
        }
    }

    private Dictionary<string, object?>? BuildUnrealReflectionItem(IDeclaration declaration, string relativePath)
    {
        var declaredElement = declaration.DeclaredElement;
        if (declaredElement == null)
            return null;

        var reflectionKind = GetUnrealReflectionKind(declaredElement) ??
            GetUnrealReflectionKindFromPsi(declaration) ??
            GetUnrealReflectionKindFromDocument(declaration);
        if (reflectionKind == null)
            return null;

        var range = declaration.GetNavigationRange();
        var start = range.StartOffset.ToDocumentCoords();
        return new Dictionary<string, object?>
        {
            ["namePath"] = declaredElement.ShortName,
            ["relativePath"] = relativePath,
            ["kind"] = reflectionKind,
            ["type"] = declaredElement.GetElementType().ToString(),
            ["module"] = TryGetUnrealModuleName(declaredElement),
            ["quickInfo"] = declaredElement.ToString(),
            ["textRange"] = new Dictionary<string, object?>
            {
                ["startPos"] = new Dictionary<string, object?>
                {
                    ["line"] = (int)start.Line,
                    ["col"] = (int)start.Column,
                },
            },
            ["providerDetail"] = "ReSharper C++ PSI + CppUE4Util",
        };
    }

    private static string? GetUnrealReflectionKind(IDeclaredElement declaredElement)
    {
        if (declaredElement is ICppClassSymbol classSymbol)
        {
            if (CppUE4Util.IsUClass(classSymbol)) return "UCLASS";
            if (CppUE4Util.IsUStruct(classSymbol)) return "USTRUCT";
            if (CppUE4Util.IsUEnum(classSymbol)) return "UENUM";
        }

        if (declaredElement is ICppClassResolveEntity classEntity)
        {
            if (CppUE4Util.IsUInterface(classEntity)) return "UINTERFACE";
            if (CppUE4Util.IsUClass(classEntity)) return "UCLASS";
            if (CppUE4Util.IsUStruct(classEntity)) return "USTRUCT";
            if (CppUE4Util.IsUEnum(classEntity)) return "UENUM";
        }

        if (declaredElement is ICppFunctionDeclaratorResolveEntity functionEntity &&
            CppUE4Util.IsUFunction(functionEntity))
            return "UFUNCTION";

        if (declaredElement is ICppDeclaratorResolveEntity declaratorEntity &&
            CppUE4Util.IsUProperty(declaratorEntity))
            return "UPROPERTY";

        if (declaredElement is ICppDeclaratorSymbol declaratorSymbol)
        {
            if (CppUE4Util.IsUFunction(declaratorSymbol)) return "UFUNCTION";
            if (CppUE4Util.IsUProperty(declaratorSymbol)) return "UPROPERTY";
        }

        return null;
    }

    private static string? GetUnrealReflectionKindFromPsi(IDeclaration declaration)
    {
        var macro = FindNearestReflectionMacro(declaration);
        return macro switch
        {
            "UCLASS" => "UCLASS",
            "USTRUCT" => "USTRUCT",
            "UENUM" => "UENUM",
            "UINTERFACE" => "UINTERFACE",
            "UFUNCTION" => "UFUNCTION",
            "UPROPERTY" => "UPROPERTY",
            _ => null,
        };
    }

    private static string? GetUnrealReflectionKindFromDocument(IDeclaration declaration)
    {
        var sourceFile = declaration.GetSourceFile();
        var document = sourceFile?.Document;
        if (document == null)
            return null;

        var text = document.GetText();
        var range = declaration.GetNavigationRange();
        var start = range.StartOffset.ToDocumentCoords();
        var offset = OffsetFromLineCol(text, (int)start.Line, (int)start.Column);
        if (offset <= 0)
            return null;

        var windowStart = Math.Max(0, offset - 768);
        var prefix = text.Substring(windowStart, offset - windowStart);
        var lastMacro = LastReflectionMacro(prefix);
        if (lastMacro == null)
            return null;

        var declarationText = declaration.GetText().TrimStart();
        if (lastMacro is "UCLASS" or "USTRUCT" or "UINTERFACE" &&
            !declarationText.StartsWith("class ", StringComparison.Ordinal) &&
            !declarationText.StartsWith("struct ", StringComparison.Ordinal))
            return null;

        if (lastMacro == "UENUM" && !declarationText.StartsWith("enum ", StringComparison.Ordinal))
            return null;

        return lastMacro;
    }

    private static string? FindNearestReflectionMacro(ITreeNode node)
    {
        foreach (var text in EnumeratePreviousNodeTexts(node, maxSteps: 18))
        {
            var macro = FirstReflectionMacro(text);
            if (macro != null)
                return macro;

            if (IsDeclarationBoundary(text))
                return null;
        }

        return null;
    }

    private static IEnumerable<string> EnumeratePreviousNodeTexts(ITreeNode node, int maxSteps)
    {
        var current = node.PrevSibling;
        var steps = 0;
        while (current != null && steps < maxSteps)
        {
            var text = current.GetText();
            if (!string.IsNullOrWhiteSpace(text))
            {
                yield return text;
                steps++;
            }

            current = current.PrevSibling;
        }

        var parent = node.Parent;
        while (parent != null && steps < maxSteps)
        {
            current = parent.PrevSibling;
            while (current != null && steps < maxSteps)
            {
                var text = current.GetText();
                if (!string.IsNullOrWhiteSpace(text))
                {
                    yield return text;
                    steps++;
                }

                current = current.PrevSibling;
            }

            parent = parent.Parent;
        }
    }

    private static string? FirstReflectionMacro(string text)
    {
        foreach (var macro in new[] { "UCLASS", "USTRUCT", "UENUM", "UINTERFACE", "UFUNCTION", "UPROPERTY" })
        {
            if (text.Contains(macro + "(", StringComparison.Ordinal) || string.Equals(text.Trim(), macro, StringComparison.Ordinal))
                return macro;
        }

        return null;
    }

    private static string? LastReflectionMacro(string text)
    {
        string? best = null;
        var bestIndex = -1;
        foreach (var macro in new[] { "UCLASS", "USTRUCT", "UENUM", "UINTERFACE", "UFUNCTION", "UPROPERTY" })
        {
            var index = text.LastIndexOf(macro + "(", StringComparison.Ordinal);
            if (index > bestIndex)
            {
                best = macro;
                bestIndex = index;
            }
        }

        return best;
    }

    private static bool IsDeclarationBoundary(string text)
    {
        var trimmed = text.Trim();
        return trimmed.EndsWith(";}", StringComparison.Ordinal) ||
            trimmed.EndsWith("};", StringComparison.Ordinal) ||
            trimmed.EndsWith(";", StringComparison.Ordinal) ||
            trimmed.EndsWith("}", StringComparison.Ordinal) ||
            trimmed.StartsWith("class ", StringComparison.Ordinal) ||
            trimmed.StartsWith("struct ", StringComparison.Ordinal) ||
            trimmed.StartsWith("enum ", StringComparison.Ordinal);
    }

    private static string? TryGetUnrealModuleName(IDeclaredElement declaredElement)
    {
        try
        {
            return UE4SearchUtil.GetModuleName(declaredElement);
        }
        catch
        {
            return null;
        }
    }

    private static bool MatchesReflectionQuery(
        IReadOnlyDictionary<string, object?> item,
        string? query,
        string? kind)
    {
        if (!string.IsNullOrWhiteSpace(kind))
        {
            var actualKind = item.TryGetValue("kind", out var kindValue) ? kindValue as string : null;
            var normalizedKind = kind.Trim().TrimStart('U').ToUpperInvariant();
            var normalizedActual = actualKind?.TrimStart('U').ToUpperInvariant();
            if (!string.Equals(normalizedActual, normalizedKind, StringComparison.OrdinalIgnoreCase) &&
                !string.Equals(actualKind, kind, StringComparison.OrdinalIgnoreCase))
                return false;
        }

        if (string.IsNullOrWhiteSpace(query))
            return true;

        return item.Values.OfType<string>().Any(value =>
            value.Contains(query, StringComparison.OrdinalIgnoreCase));
    }

    private static IReadOnlyCollection<IFile> SafeGetPsiFiles(IPsiSourceFile sourceFile)
    {
        try
        {
            return sourceFile.GetPsiServices().Files.GetPsiFiles(sourceFile).ToList();
        }
        catch
        {
            return Array.Empty<IFile>();
        }
    }

    private static IEnumerable<string> GetCppBaseNamesFromDeclaration(IDeclaration? declaration)
    {
        if (declaration == null)
            return Array.Empty<string>();

        var text = declaration.GetText();
        var colon = text.IndexOf(':');
        if (colon < 0)
            return Array.Empty<string>();

        var end = text.IndexOf('{', colon);
        if (end < 0)
            end = text.IndexOf('\n', colon);
        if (end < 0)
            end = text.Length;

        var baseClause = text.Substring(colon + 1, end - colon - 1);
        return baseClause
            .Split(',')
            .Select(NormalizeCppBaseName)
            .Where(name => !string.IsNullOrWhiteSpace(name))
            .Distinct(StringComparer.Ordinal);
    }

    private static string NormalizeCppBaseName(string baseName)
    {
        var tokens = baseName
            .Replace('\r', ' ')
            .Replace('\n', ' ')
            .Trim()
            .Split(new[] { ' ', '\t' }, StringSplitOptions.RemoveEmptyEntries)
            .Where(token =>
                !string.Equals(token, "public", StringComparison.Ordinal) &&
                !string.Equals(token, "protected", StringComparison.Ordinal) &&
                !string.Equals(token, "private", StringComparison.Ordinal) &&
                !string.Equals(token, "virtual", StringComparison.Ordinal) &&
                !token.EndsWith("_API", StringComparison.Ordinal))
            .ToList();
        if (tokens.Count == 0)
            return string.Empty;

        var normalized = tokens.Last();
        var templateStart = normalized.IndexOf('<');
        if (templateStart >= 0)
            normalized = normalized.Substring(0, templateStart);
        var scope = normalized.LastIndexOf("::", StringComparison.Ordinal);
        if (scope >= 0)
            normalized = normalized.Substring(scope + 2);
        return normalized.Trim();
    }

    private string ToRelativePath(IPsiSourceFile sourceFile)
    {
        return TryToRelativePath(sourceFile) ?? sourceFile.GetLocation().FileAccessPath.Replace('\\', '/');
    }

    private string? TryToRelativePath(IPsiSourceFile sourceFile)
    {
        try
        {
            var location = sourceFile.GetLocation();
            var relative = location.MakeRelativeTo(Solution.SolutionDirectory).ToString();
            return string.IsNullOrEmpty(relative) ? location.FileAccessPath.Replace('\\', '/') : relative.Replace('\\', '/');
        }
        catch (InvalidOperationException)
        {
            return null;
        }
    }

    private static bool IsUnrealReflectionSourcePath(string relativePath, bool includeGenerated)
    {
        var path = relativePath.Replace('\\', '/');
        if (!includeGenerated && path.Contains("/Intermediate/", StringComparison.OrdinalIgnoreCase))
            return false;
        if (!path.StartsWith("Source/", StringComparison.OrdinalIgnoreCase) &&
            !path.Contains("/Source/", StringComparison.OrdinalIgnoreCase) &&
            !(includeGenerated && path.Contains("/Intermediate/", StringComparison.OrdinalIgnoreCase)))
            return false;

        return path.EndsWith(".h", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith(".hpp", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith(".hh", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith(".cpp", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith(".cc", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith(".cxx", StringComparison.OrdinalIgnoreCase) ||
            path.EndsWith(".inl", StringComparison.OrdinalIgnoreCase);
    }

    private string ToRelativePath(VirtualFileSystemPath path)
    {
        var relative = path.MakeRelativeTo(Solution.SolutionDirectory).ToString();
        return string.IsNullOrEmpty(relative) ? path.FileAccessPath : relative.Replace('\\', '/');
    }

    private bool MatchesAssetPathPrefix(IPsiSourceFile sourceFile, string? pathPrefix)
    {
        if (string.IsNullOrWhiteSpace(pathPrefix))
            return true;

        var normalizedPrefix = pathPrefix.Replace('\\', '/');
        var relativePath = ToRelativePath(sourceFile);
        if (relativePath.StartsWith(normalizedPrefix, StringComparison.OrdinalIgnoreCase))
            return true;

        var accessor = AssetsCache.GetUEAssetFileAccessor(sourceFile);
        return accessor.AssetPath.ToString().Replace('\\', '/')
            .StartsWith(normalizedPrefix, StringComparison.OrdinalIgnoreCase);
    }

    private Dictionary<string, object?> BuildAssetSearchResult(IPsiSourceFile sourceFile)
    {
        var accessor = AssetsCache.GetUEAssetFileAccessor(sourceFile);
        return new Dictionary<string, object?>
        {
            ["relativePath"] = ToRelativePath(sourceFile),
            ["assetPath"] = accessor.AssetPath.ToString().Replace('\\', '/'),
            ["valid"] = accessor.IsValid(),
            ["hasErrors"] = AssetsCache.HasErrors(sourceFile),
            ["providerDetail"] = "UE4AssetsCache.GetUEAssetFileAccessor",
        };
    }

    private Dictionary<string, object?> BuildUnrealSearchTarget(IUE4SearchTarget target)
    {
        var result = new Dictionary<string, object?>
        {
            ["className"] = target.ClassName,
            ["moduleName"] = target.ModuleName,
            ["fullClassName"] = target.FullClassName.ToString(),
            ["isCoreRedirectName"] = target.IsCoreRedirectName,
            ["targetType"] = target.GetType().Name,
        };

        if (target is IUE4SearchMemberTarget memberTarget)
            result["memberName"] = memberTarget.MemberName;

        return result;
    }

    private Dictionary<string, object?> BuildUnrealFindResult(UnrealFindResult result)
    {
        var item = new Dictionary<string, object?>
        {
            ["relativePath"] = ToRelativePath(result.AssetFile),
            ["assetPath"] = result.AssetFile.ToString().Replace('\\', '/'),
            ["occurrenceKind"] = result.OccurrenceKind.ToString(),
            ["resultType"] = result.GetType().Name,
            ["displayText"] = result.ToString(),
            ["providerDetail"] = "UEAssetUsagesSearcher",
        };

        if (result is UnrealAssetFindResult assetResult)
        {
            item["objectName"] = assetResult.ObjectName;
        }

        if (result is UnrealAssetFindFunctionResult functionResult)
            item["functionName"] = functionResult.FunctionName;

        if (result is UnrealAssetFindPropertyResult propertyResult)
        {
            item["parentName"] = propertyResult.ParentName;
            item["property"] = propertyResult.Property.ToString();
        }

        if (result is UnrealAssetFindMemberResult memberResult)
            item["guid"] = memberResult.Guid;

        return item;
    }

    private Dictionary<string, object?> BuildInspectionIssue(
        IPsiSourceFile sourceFile,
        IDaemonProcess daemonProcess,
        HighlightingInfo highlighting)
    {
        var range = highlighting.Range;
        var start = range.StartOffset.ToDocumentCoords();
        var end = range.EndOffset.ToDocumentCoords();
        return new Dictionary<string, object?>
        {
            ["inspectionId"] = highlighting.GetAttributeId(
                HighlightingSettingsManager,
                sourceFile,
                daemonProcess.ContextBoundSettingsStore),
            ["severity"] = highlighting.GetSeverity(
                HighlightingSettingsManager,
                sourceFile,
                daemonProcess.ContextBoundSettingsStore).ToString(),
            ["message"] = highlighting.Highlighting.ToolTip,
            ["relativePath"] = ToRelativePath(sourceFile),
            ["textRange"] = new Dictionary<string, object?>
            {
                ["startPos"] = new Dictionary<string, object?>
                {
                    ["line"] = (int)start.Line,
                    ["col"] = (int)start.Column,
                },
                ["endPos"] = new Dictionary<string, object?>
                {
                    ["line"] = (int)end.Line,
                    ["col"] = (int)end.Column,
                },
            },
            ["quickFixAvailable"] = false,
            ["providerDetail"] = "ReSharper daemon HighlightingInfo",
        };
    }
}

[SolutionComponent(Instantiation.DemandAnyThreadSafe)]
public sealed class BackendStartupActivity : IStartupActivity
{
    public BackendStartupActivity(BackendComponent backendComponent)
    {
        BackendComponent = backendComponent;
        Console.WriteLine("UnrealPasser BackendStartupActivity created.");
    }

    public BackendComponent BackendComponent { get; }
}

public sealed record BackendRequest(string Operation, JsonElement Payload);

public static class BackendJsonElementExtensions
{
    public static string GetRequiredString(this JsonElement element, string propertyName)
    {
        return element.TryGetProperty(propertyName, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString() ?? string.Empty
            : throw new JsonException($"Missing required string property '{propertyName}'.");
    }

    public static int GetInt32OrDefault(this JsonElement element, string propertyName, int defaultValue)
    {
        return element.TryGetProperty(propertyName, out var property) && property.TryGetInt32(out var value)
            ? value
            : defaultValue;
    }

    public static string? GetStringOrNull(this JsonElement element, string propertyName)
    {
        return element.TryGetProperty(propertyName, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString()
            : null;
    }

    public static bool GetBooleanOrDefault(this JsonElement element, string propertyName, bool defaultValue)
    {
        return element.TryGetProperty(propertyName, out var property) && property.ValueKind is JsonValueKind.True or JsonValueKind.False
            ? property.GetBoolean()
            : defaultValue;
    }

    public static JsonElement GetRequiredObject(this JsonElement element, string propertyName)
    {
        return element.TryGetProperty(propertyName, out var property) && property.ValueKind == JsonValueKind.Object
            ? property
            : throw new JsonException($"Missing required object property '{propertyName}'.");
    }
}

public sealed record BackendApiAvailability(
    string Provider,
    string[] SymbolApis,
    string[] UnrealApis)
{
    public static BackendApiAvailability Current { get; } = new(
        "resharper-backend",
        new[]
        {
            "JetBrains.ReSharper.Psi.IDeclaredElement",
            "JetBrains.ReSharper.Feature.Services.FindUsages",
            "JetBrains.ReSharper.Feature.Services.Hierarchy",
            "JetBrains.ReSharper.Feature.Services.Daemon",
        },
        new[]
        {
            typeof(UE4AssetsCache).FullName ?? nameof(UE4AssetsCache),
            typeof(UEAssetUsagesSearcher).FullName ?? nameof(UEAssetUsagesSearcher),
            "JetBrains.ReSharper.Feature.Services.Cpp.UE4.UnrealHeaderTool.UhtClassDefinitionBuilder",
            "JetBrains.ReSharper.Feature.Services.Cpp.UE4.UnrealHeaderTool.UnrealHeaderToolRunner",
        });

    public string ToJson()
    {
        return $$"""
        {"provider":"{{Provider}}","symbolApis":[{{QuoteJoin(SymbolApis)}}],"unrealApis":[{{QuoteJoin(UnrealApis)}}],"bridge":"rd"}
        """;
    }

    private static string QuoteJoin(IEnumerable<string> values)
    {
        return string.Join(",", values.Select(value => "\"" + JsonEscape(value) + "\""));
    }

    private static string JsonEscape(string value)
    {
        return value.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal);
    }
}

public sealed record BackendError(string ErrorCode, string Message, string Source, bool Recoverable)
{
    public static BackendError BadRequest(string message) => new(
        "UNREALPASSER_BACKEND_BAD_REQUEST",
        message,
        "resharper",
        true);

    public static BackendError UnknownOperation(string operation) => new(
        "UNREALPASSER_BACKEND_UNKNOWN_OPERATION",
        $"The UnrealPasser backend RD bridge does not recognize operation '{operation}'.",
        "resharper",
        false);

    public static BackendError PsiUnavailable(string message) => new(
        "PSI_UNAVAILABLE",
        message,
        "resharper",
        true);

    public static BackendError DaemonUnavailable(string message) => new(
        "RESHARPER_DAEMON_UNAVAILABLE",
        message,
        "resharper",
        true);

    public static BackendError Unsupported(string operation) => new(
        "UNREALPASSER_BACKEND_OPERATION_UNSUPPORTED",
        $"The UnrealPasser backend RD bridge is connected, but operation '{operation}' is not implemented yet.",
        "resharper",
        true);

    public static BackendError UnsupportedUnreal(string operation) => new(
        "RESHARPER_UNREAL_API_UNAVAILABLE",
        $"Rider/ReSharper Unreal backend bridge is connected, but operation '{operation}' still needs a concrete Unreal API adapter. Product APIs do not return file-scan fallback results.",
        "resharper-unreal",
        true);

    public string ToJson()
    {
        return $$"""
        {"errorCode":"{{Escape(ErrorCode)}}","message":"{{Escape(Message)}}","source":"{{Escape(Source)}}","recoverable":{{Recoverable.ToString().ToLowerInvariant()}}}
        """;
    }

    private static string Escape(string value)
    {
        return value.Replace("\\", "\\\\", StringComparison.Ordinal).Replace("\"", "\\\"", StringComparison.Ordinal);
    }
}
