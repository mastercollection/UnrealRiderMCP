import { spawn } from "node:child_process";
import { createInterface } from "node:readline";

const child = spawn(process.execPath, ["dist/server.js"], {
  cwd: new URL("..", import.meta.url),
  stdio: ["pipe", "pipe", "inherit"],
});

const lines = createInterface({ input: child.stdout });
const responses = new Map<number, unknown>();

lines.on("line", (line) => {
  const parsed = JSON.parse(line) as { id?: number };
  if (typeof parsed.id === "number") {
    responses.set(parsed.id, parsed);
  }
});

let nextId = 1;

function send(method: string, params: unknown = {}): number {
  const id = nextId++;
  child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
  return id;
}

async function waitFor(id: number, timeoutMs = 5000): Promise<unknown> {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (responses.has(id)) {
      return responses.get(id);
    }
    await new Promise((resolve) => setTimeout(resolve, 25));
  }
  throw new Error(`Timed out waiting for MCP response ${id}`);
}

try {
  const initializeId = send("initialize", {
    protocolVersion: "2025-06-18",
    capabilities: {},
    clientInfo: { name: "unrealpasser-smoke", version: "0.1.0" },
  });
  await waitFor(initializeId);
  child.stdin.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized", params: {} })}\n`);

  const toolsId = send("tools/list");
  const tools = await waitFor(toolsId);
  console.log(JSON.stringify(tools, null, 2));
} finally {
  child.kill();
}
