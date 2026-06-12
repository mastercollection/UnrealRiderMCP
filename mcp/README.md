# UnrealPasser MCP Adapter

This package exposes UnrealPasser as a protocol-compliant stdio MCP server for Codex and Claude Code.

The adapter does not talk to Rider/ReSharper directly. Rider must be open with the Unreal project loaded, and the UnrealPasser plugin must be serving its local HTTP API on `127.0.0.1:24226..24245`.

## Build

```powershell
cd C:\tools\UnrealPasser\mcp
npm install
npm run build
npm run smoke
```

## Tools

- `unreal_project_status`
- `unreal_reflection_search`
- `unreal_asset_references`
- `rider_inspections`
- `rider_inspection_catalog`

## Codex

Add this to `~/.codex/config.toml` or a trusted project `.codex/config.toml`:

```toml
[mcp_servers.unrealpasser]
command = "node"
args = ["C:\\tools\\UnrealPasser\\mcp\\dist\\server.js"]
startup_timeout_sec = 10
tool_timeout_sec = 120
```

## Claude Code

```powershell
claude mcp add --transport stdio unrealpasser -- node C:\tools\UnrealPasser\mcp\dist\server.js
```

## Optional URL Override

If UnrealPasser is not on the default scanned port range, set one of:

```powershell
$env:UNREALPASSER_URL='http://127.0.0.1:24226'
$env:UNREALPASSER_PORT='24226'
```
