import { z } from "zod";
import type { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { UnrealPasserClient, UnrealPasserClientError, type JsonObject } from "./unrealpasserClient.js";

const optionalLimit = z.number().int().positive().max(500).optional();
const optionalOffset = z.number().int().min(0).optional();

export function registerUnrealPasserTools(server: McpServer, client: UnrealPasserClient): void {
  server.tool(
    "unreal_project_status",
    "Return Rider/UnrealPasser project readiness, index state, Rider build, plugin version, and backend bridge status.",
    {},
    async () => toToolResult(await client.projectStatus()),
  );

  server.tool(
    "unreal_reflection_search",
    "Search Unreal reflected C++ symbols from Rider/ReSharper PSI, including UCLASS, USTRUCT, UENUM, UFUNCTION, and UPROPERTY.",
    {
      query: z.string().optional(),
      namePath: z.string().optional(),
      kind: z.enum(["class", "struct", "enum", "function", "property", "UCLASS", "USTRUCT", "UENUM", "UFUNCTION", "UPROPERTY"]).optional(),
      relativePath: z.string().optional(),
      pathPrefix: z.string().optional(),
      limit: optionalLimit,
      offset: optionalOffset,
      includeGenerated: z.boolean().optional(),
    },
    async (args) => toToolResult(await client.reflectionSearch(clean(args))),
  );

  server.tool(
    "unreal_asset_references",
    "Find Blueprint and Unreal asset references for a reflected C++ symbol using Rider/ReSharper Unreal asset search.",
    {
      namePath: z.string().min(1),
      relativePath: z.string().min(1),
      includeChildren: z.boolean().optional(),
      limit: optionalLimit,
      offset: optionalOffset,
    },
    async (args) => toToolResult(await client.assetReferences(clean(args))),
  );

  server.tool(
    "rider_inspections",
    "Run Rider/ReSharper daemon inspections for one file and return inspection id, severity, message, range, and quick-fix availability.",
    {
      relativePath: z.string().min(1),
    },
    async (args) => toToolResult(await client.riderInspections(clean(args))),
  );

  server.tool(
    "rider_inspection_catalog",
    "List the Rider/ReSharper inspection catalog exposed by UnrealPasser. The result can be large.",
    {
      limit: optionalLimit,
      offset: optionalOffset,
    },
    async (args) => toToolResult(await client.inspectionCatalog(clean(args))),
  );
}

export function toToolResult(payload: JsonObject) {
  return {
    content: [
      {
        type: "text" as const,
        text: JSON.stringify(payload, null, 2),
      },
    ],
  };
}

export function toToolError(error: unknown) {
  const payload = error instanceof UnrealPasserClientError
    ? {
        error: {
          message: error.message,
          status: error.status,
          recoverable: error.recoverable,
          payload: error.payload,
        },
      }
    : {
        error: {
          message: error instanceof Error ? error.message : String(error),
          recoverable: true,
        },
      };

  return {
    isError: true,
    content: [
      {
        type: "text" as const,
        text: JSON.stringify(payload, null, 2),
      },
    ],
  };
}

function clean<T extends JsonObject>(value: T): JsonObject {
  return Object.fromEntries(
    Object.entries(value).filter(([, entry]) => entry !== undefined),
  );
}
