// Phase 3 mock sidecar (#5).
//
// During session.sendAndWait, this mock issues a user_input.request RPC
// back to the daemon (simulating the SDK agent firing ask_user) and
// threads the daemon's resolution into the final assistant content so
// the test can assert round-trip shape. Used by
// CopilotAcpClientUserInputSmokeTest.

let nextOutId = 1;
const pendingOut = new Map();

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
        const r = await request("user_input.request", {
          requestId: "mock-rid-42",
          question: "Which color?",
          choices: ["red", "blue"],
          allowFreeform: true,
        });
        const answer = r?.cancel ? "(cancelled)" : (r?.answer ?? "");
        const wasFreeform = r?.wasFreeform === true ? " freeform" : "";
        result = {
          content: `agent received: ${answer}${wasFreeform}`,
          stopReason: "COMPLETE",
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
      writeMessage({ jsonrpc: "2.0", id, error: { code: -32000, message: String(e?.message ?? e) } });
    }
  }
}
