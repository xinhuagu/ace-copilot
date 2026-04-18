// Phase 2 mock sidecar (issue #4).
//
// During session.sendAndWait, this mock issues a tool.invoke RPC *back*
// to the daemon (simulating the SDK agent calling one of our custom
// tools), then echoes the tool's response into the final assistant
// content. Used by CopilotAcpClientToolsSmokeTest to verify that:
//   - initialize carries the tools[] array
//   - bidirectional JSON-RPC (sidecar → Java request) works
//   - Java-side RequestHandler dispatches tool.invoke correctly

let nextOutId = 1;
const pendingOut = new Map();
let sendCount = 0;
let lastToolSig = null;

function toolSig(tools) {
  if (!Array.isArray(tools) || tools.length === 0) return "empty";
  return tools.map((t) => t.name).sort().join(",");
}

function writeMessage(obj) {
  const body = JSON.stringify(obj);
  const buf = Buffer.from(body, "utf8");
  process.stdout.write(`Content-Length: ${buf.length}\r\n\r\n`);
  process.stdout.write(buf);
}

function request(method, params) {
  return new Promise((resolve, reject) => {
    const id = nextOutId++;
    pendingOut.set(id, { resolve, reject });
    writeMessage({ jsonrpc: "2.0", id, method, params });
  });
}

let buffer = Buffer.alloc(0);
process.stdin.on("data", (chunk) => {
  buffer = Buffer.concat([buffer, chunk]);
  while (true) {
    const sep = buffer.indexOf("\r\n\r\n");
    if (sep < 0) return;
    const m = /Content-Length:\s*(\d+)/i.exec(buffer.slice(0, sep).toString("utf8"));
    if (!m) process.exit(2);
    const len = parseInt(m[1], 10);
    const bodyStart = sep + 4;
    if (buffer.length < bodyStart + len) return;
    const body = buffer.slice(bodyStart, bodyStart + len).toString("utf8");
    buffer = buffer.slice(bodyStart + len);
    const msg = JSON.parse(body);
    if (msg.id !== undefined && (msg.result !== undefined || msg.error !== undefined) && msg.method === undefined) {
      const p = pendingOut.get(msg.id);
      if (p) {
        pendingOut.delete(msg.id);
        if (msg.error) p.reject(new Error(msg.error.message ?? "err"));
        else p.resolve(msg.result);
      }
      continue;
    }
    handle(msg);
  }
});

async function handle(msg) {
  const { id, method, params } = msg;
  try {
    let result;
    switch (method) {
      case "initialize":
        result = { protocolVersion: "0.1" };
        break;
      case "session.sendAndWait": {
        sendCount++;
        const sig = toolSig(params?.tools);
        const toolsReset = lastToolSig !== null && lastToolSig !== sig;
        lastToolSig = sig;

        // Call back into the Java daemon with a tool.invoke, using the
        // first tool advertised this turn (so the test can assert the
        // current catalog actually made it through).
        const firstTool = Array.isArray(params?.tools) && params.tools.length > 0
            ? params.tools[0].name : "read_file";
        const toolResp = await request("tool.invoke", {
          name: firstTool,
          arguments: { path: "mock-path.txt" },
        });
        const toolContent = toolResp?.content ?? "(no content)";
        writeMessage({
          jsonrpc: "2.0",
          method: "session/text",
          params: { delta: `tool said: ${toolContent}` },
        });
        writeMessage({
          jsonrpc: "2.0",
          method: "session/usage",
          params: {
            model: "test-model",
            initiator: "user",
            premiumUsed: 100 + sendCount,
            premiumLimit: 200,
          },
        });
        result = {
          content: `tool said: ${toolContent}`,
          stopReason: "COMPLETE",
          toolsReset: toolsReset || undefined,
        };
        break;
      }
      case "shutdown":
        writeMessage({ jsonrpc: "2.0", id, result: null });
        process.exit(0);
        return;
      default:
        throw new Error("unknown method: " + method);
    }
    if (id !== undefined) writeMessage({ jsonrpc: "2.0", id, result });
  } catch (e) {
    if (id !== undefined) {
      writeMessage({
        jsonrpc: "2.0",
        id,
        error: { code: -32000, message: String(e?.message ?? e) },
      });
    }
  }
}
