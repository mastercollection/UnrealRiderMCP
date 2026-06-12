#!/usr/bin/env node
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StdioServerTransport } from "@modelcontextprotocol/sdk/server/stdio.js";
import { UnrealPasserClient } from "./unrealpasserClient.js";
import { registerUnrealPasserTools, toToolError } from "./tools.js";

const server = new McpServer(
  {
    name: "unrealpasser",
    version: "0.1.0",
  },
  {
    instructions: "Use UnrealPasser tools for read-only Rider/ReSharper Unreal project intelligence. Rider must be open with the Unreal project loaded. Do not request mutations; V1 exposes status, reflection search, asset references, and inspections only.",
  },
);

const client = new UnrealPasserClient();
registerUnrealPasserTools(server, client);

process.on("uncaughtException", (error) => {
  console.error("UnrealPasser MCP uncaught exception", toToolError(error));
});

process.on("unhandledRejection", (error) => {
  console.error("UnrealPasser MCP unhandled rejection", toToolError(error));
});

await server.connect(new StdioServerTransport());
