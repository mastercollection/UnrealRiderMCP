# UnrealPasser

UnrealPasser is a Rider plugin that exposes Rider/ReSharper project intelligence through Serena-compatible local HTTP endpoints and UnrealPasser product endpoints.

The product path is intentionally read-only and ReSharper-first. The Rider frontend owns the HTTP/MCP surface and routes product requests over a custom RD extension to a ReSharperHost backend assembly. The backend queries ReSharper PSI, Find Usages, type hierarchy, daemon inspections, and Rider/ReSharper Unreal APIs such as `UE4AssetsCache`, `UEAssetUsagesSearcher`, `CppUE4Util`, and UHT support types. File/config scanning is retained only behind the explicit diagnostic fallback flag and is never returned from the default product path.

## Current Scope

- Starts an HTTP server on `127.0.0.1`, scanning ports `0x5EA2..0x5EB5`.
- Keeps the Serena-compatible read-only surface:
  - `GET /status`
  - `POST /getSymbolsOverview`
  - `POST /findSymbol`
  - `POST /findDeclaration`
  - `POST /findReferences`
  - `POST /findImplementations`
  - `POST /getSupertypes`
  - `POST /getSubtypes`
  - `POST /runInspectionsOnFile`
  - `POST /listInspections`
- Adds UnrealPasser/MCP-style aliases:
  - `POST /mcp/unreal_project_status`
  - `POST /mcp/unreal_reflection_search`
  - `POST /mcp/unreal_asset_references`
  - `POST /mcp/rider_inspections`
- Adds a protocol-compliant stdio MCP adapter under `mcp/` for Codex and Claude Code.
- Returns read-only errors for edit/debug endpoints.
- Includes response metadata on every API response:
  - `provider`
  - `riderBuild`
  - `pluginVersion`
  - `projectRoot`
  - `indexState`
  - `partial: false`
- Includes readiness details from Rider frontend dumb mode and RD `UnrealModel` where available:
  - Unreal background indexing completion
  - UBT progress
  - Unreal dumb mode
  - Unreal engine location
- Does not return file-scan fallback results from the product path.

## Build

This project is configured against the local Rider installation:

`C:/Program Files/JetBrains/JetBrains Rider 2025.1`

Use a JDK 21 runtime for Gradle. The local Rider 2025.1 installation may ship with a newer JBR that Gradle/Kotlin cannot parse yet. On this machine, this path was verified:

`C:/Users/NX3GAMES/AppData/Local/JetBrains/Rider2025.1/tmp/patch-update/jre`

Build the plugin:

```powershell
$env:JAVA_HOME='C:\Users\NX3GAMES\AppData\Local\JetBrains\Rider2025.1\tmp\patch-update\jre'
./gradlew.bat buildPlugin
```

Run the policy and backend catalog checks:

```powershell
$env:JAVA_HOME='C:\Users\NX3GAMES\AppData\Local\JetBrains\Rider2025.1\tmp\patch-update\jre'
./gradlew.bat check buildPlugin verifyReSharperBackendCatalog
```

Run a sandbox Rider:

```powershell
$env:JAVA_HOME='C:\Users\NX3GAMES\AppData\Local\JetBrains\Rider2025.1\tmp\patch-update\jre'
./gradlew.bat runIde
```

The built plugin ZIP is emitted under `build/distributions/`.

## Serena Usage

Set Serena to the JetBrains backend and open the same project folder in Rider:

```yaml
language_backend: JetBrains
```

UnrealPasser reports the Rider project root from `/status`; Serena matches that against its active project path.

## MCP Usage

Build the stdio MCP adapter:

```powershell
cd mcp
npm install
npm run build
```

Codex config example:

```toml
[mcp_servers.unrealpasser]
command = "node"
args = ["C:\\tools\\UnrealPasser\\mcp\\dist\\server.js"]
startup_timeout_sec = 10
tool_timeout_sec = 120
```

Claude Code example:

```powershell
claude mcp add --transport stdio unrealpasser -- node C:\tools\UnrealPasser\mcp\dist\server.js
```

Rider must be open with the Unreal project loaded. The adapter scans `127.0.0.1:24226..24245` for the UnrealPasser HTTP server.

## Diagnostic Fallback

The old file/config scanner is retained only for local diagnostics. Enable it explicitly with:

```powershell
$env:JAVA_TOOL_OPTIONS='-Dunrealpasser.diagnosticFallback=true'
```

When enabled, responses report `provider: "fallback"` and may include fallback warnings. This mode is not the product behavior.

## Implementation Notes

`ReSharperSymbolProvider` is the product symbol provider. It delegates to `ReSharperBackendRpcBridge`, which sends JSON requests through the `UnrealPasserBackendModel` RD extension. The bridge uses infinite RD RPC timeouts so UnrealPasser does not impose its own request timeout.

`backend/UnrealPasser.Backend` is a ReSharperHost-side assembly built into the plugin `dotnet/` payload. `BackendStartupActivity` eagerly demands `BackendComponent`, and `BackendComponent` binds the backend endpoint for `UnrealPasserBackendModel.Execute`.

Symbol, declaration, reference, implementation, hierarchy, and inspection operations are implemented in the ReSharper backend with PSI, `IFinder`, type hierarchy helpers, and daemon highlighting APIs. Unreal reflection uses ReSharper C++ PSI plus `CppUE4Util`; asset search/reference operations use `UE4AssetsCache`, `UE4SearchUtil`, and `UEAssetUsagesSearcher`.

`RiderUnrealStatusProbe` reads Rider's RD `UnrealModel` via the active solution model and powers `/status`, response metadata readiness, and `/mcp/unreal_project_status`.
