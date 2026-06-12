export const DEFAULT_BASE_PORT = 0x5ea2;
export const DEFAULT_PORT_COUNT = 20;

export type JsonObject = Record<string, unknown>;

export class UnrealPasserClientError extends Error {
  constructor(
    message: string,
    public readonly status?: number,
    public readonly payload?: unknown,
    public readonly recoverable = true,
  ) {
    super(message);
    this.name = "UnrealPasserClientError";
  }
}

export class UnrealPasserClient {
  private baseUrl?: string;

  constructor(private readonly configuredUrl = process.env.UNREALPASSER_URL) {
    if (configuredUrl) {
      this.baseUrl = normalizeUrl(configuredUrl);
    } else if (process.env.UNREALPASSER_PORT) {
      this.baseUrl = `http://127.0.0.1:${process.env.UNREALPASSER_PORT}`;
    }
  }

  async status(): Promise<JsonObject> {
    return this.request("GET", "/status");
  }

  async projectStatus(): Promise<JsonObject> {
    return this.request("POST", "/mcp/unreal_project_status", {});
  }

  async reflectionSearch(payload: JsonObject): Promise<JsonObject> {
    return this.request("POST", "/mcp/unreal_reflection_search", payload);
  }

  async assetReferences(payload: JsonObject): Promise<JsonObject> {
    return this.request("POST", "/mcp/unreal_asset_references", payload);
  }

  async riderInspections(payload: JsonObject): Promise<JsonObject> {
    return this.request("POST", "/mcp/rider_inspections", payload);
  }

  async inspectionCatalog(payload: JsonObject): Promise<JsonObject> {
    return this.request("POST", "/listInspections", payload);
  }

  private async request(method: "GET" | "POST", path: string, payload?: JsonObject): Promise<JsonObject> {
    const url = await this.resolveUrl();
    try {
      return await this.requestAt(url, method, path, payload);
    } catch (error) {
      if (this.configuredUrl || !isRecoverableConnectionError(error)) {
        throw error;
      }

      this.baseUrl = undefined;
      return this.requestAt(await this.resolveUrl(), method, path, payload);
    }
  }

  private async resolveUrl(): Promise<string> {
    if (this.baseUrl) {
      return this.baseUrl;
    }

    for (let port = DEFAULT_BASE_PORT; port < DEFAULT_BASE_PORT + DEFAULT_PORT_COUNT; port++) {
      const candidate = `http://127.0.0.1:${port}`;
      try {
        await this.requestAt(candidate, "GET", "/status");
        this.baseUrl = candidate;
        return candidate;
      } catch {
        // Continue scanning the UnrealPasser port window.
      }
    }

    throw new UnrealPasserClientError(
      `Unable to find UnrealPasser HTTP server on 127.0.0.1:${DEFAULT_BASE_PORT}..${DEFAULT_BASE_PORT + DEFAULT_PORT_COUNT - 1}. Open the Unreal project in Rider with the UnrealPasser plugin enabled.`,
      undefined,
      undefined,
      true,
    );
  }

  private async requestAt(baseUrl: string, method: "GET" | "POST", path: string, payload?: JsonObject): Promise<JsonObject> {
    const response = await fetch(`${baseUrl}${path}`, {
      method,
      headers: method === "POST" ? { "content-type": "application/json" } : undefined,
      body: method === "POST" ? JSON.stringify(payload ?? {}) : undefined,
    });
    const text = await response.text();
    const parsed = parseJson(text);
    if (!response.ok) {
      const errorPayload = isObject(parsed) && isObject(parsed.error) ? parsed.error : undefined;
      const message = errorPayload && typeof errorPayload.message === "string"
        ? errorPayload.message
        : `UnrealPasser HTTP request failed: ${response.status} ${response.statusText}`;
      const recoverable = errorPayload && typeof errorPayload.recoverable === "boolean"
        ? errorPayload.recoverable
        : response.status >= 500;
      throw new UnrealPasserClientError(message, response.status, parsed, recoverable);
    }
    return parsed;
  }
}

function normalizeUrl(value: string): string {
  return value.endsWith("/") ? value.slice(0, -1) : value;
}

function parseJson(text: string): JsonObject {
  try {
    const parsed: unknown = text.length > 0 ? JSON.parse(text) : {};
    if (isObject(parsed)) {
      return parsed;
    }
    return { value: parsed };
  } catch {
    return { raw: text };
  }
}

function isObject(value: unknown): value is JsonObject {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isRecoverableConnectionError(error: unknown): boolean {
  return error instanceof UnrealPasserClientError ? error.recoverable : true;
}
