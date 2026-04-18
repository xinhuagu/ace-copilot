// ace-copilot Copilot SDK sidecar.
//
// Bridges the Java ace-copilot daemon to @github/copilot-sdk over LSP-framed
// JSON-RPC on stdio. One sidecar process = one long-lived Copilot session;
// the daemon spawns one sidecar per ace-copilot session.
//
// Wire protocol:
//   - Framing: LSP-style `Content-Length: N\r\n\r\n<body>` (UTF-8 JSON)
//   - Requests from daemon:
//       initialize        { githubToken?: string }      → { protocolVersion }
//       session.sendAndWait { model, prompt }           → { content, stopReason }
//       shutdown          null                          → null (then exit 0)
//   - Notifications from sidecar during session.sendAndWait:
//       session/text   { delta }
//       session/usage  { model, inputTokens, outputTokens, cost, initiator,
//                        premiumUsed, premiumLimit }
//   - Logs go to stderr, not stdout (stdout carries framed JSON-RPC only).

import { CopilotClient, approveAll } from "@github/copilot-sdk";

const log = (...args) => process.stderr.write(`[sidecar] ${args.join(" ")}\n`);

function writeMessage(obj) {
  const body = JSON.stringify(obj);
  const buf = Buffer.from(body, "utf8");
  process.stdout.write(`Content-Length: ${buf.length}\r\n\r\n`);
  process.stdout.write(buf);
}

let buffer = Buffer.alloc(0);
function onStdinData(chunk) {
  buffer = Buffer.concat([buffer, chunk]);
  while (true) {
    const sep = buffer.indexOf("\r\n\r\n");
    if (sep < 0) return;
    const header = buffer.slice(0, sep).toString("utf8");
    const m = /Content-Length:\s*(\d+)/i.exec(header);
    if (!m) {
      log("malformed header:", JSON.stringify(header));
      process.exit(2);
    }
    const len = parseInt(m[1], 10);
    const bodyStart = sep + 4;
    if (buffer.length < bodyStart + len) return;
    const body = buffer.slice(bodyStart, bodyStart + len).toString("utf8");
    buffer = buffer.slice(bodyStart + len);
    let msg;
    try {
      msg = JSON.parse(body);
    } catch (e) {
      log("bad json body:", e.message);
      continue;
    }
    handleMessage(msg).catch((err) => log("handler threw:", err?.stack ?? err));
  }
}

process.stdin.on("data", onStdinData);
process.stdin.on("end", () => process.exit(0));

let client = null;
let session = null;
let currentModel = null;
let githubToken = null;

function attachSessionListeners(s) {
  s.on((event) => {
    if (!event?.type) return;
    if (event.type === "assistant.message_delta" || event.type === "assistant.reasoning_delta") {
      const delta = event.data?.deltaContent ?? event.data?.delta;
      if (delta) {
        writeMessage({ jsonrpc: "2.0", method: "session/text", params: { delta } });
      }
    } else if (event.type === "assistant.usage") {
      const q = event.data?.quotaSnapshots?.premium_interactions;
      writeMessage({
        jsonrpc: "2.0",
        method: "session/usage",
        params: {
          model: event.data?.model ?? null,
          inputTokens: event.data?.inputTokens ?? null,
          outputTokens: event.data?.outputTokens ?? null,
          cost: event.data?.cost ?? null,
          initiator: event.data?.initiator ?? null,
          premiumUsed: q?.usedRequests ?? null,
          premiumLimit: q?.entitlementRequests ?? null,
        },
      });
    }
  });
}

async function ensureSession(model) {
  if (!client) {
    const opts = {};
    if (githubToken) {
      opts.githubToken = githubToken;
      opts.useLoggedInUser = false;
    }
    client = new CopilotClient(opts);
    log("CopilotClient created", githubToken ? "(with supplied token)" : "(using logged-in user)");
  }
  if (!session || currentModel !== model) {
    if (session) {
      try {
        await session.disconnect();
      } catch (e) {
        log("session.disconnect failed:", e.message);
      }
    }
    session = await client.createSession({
      model,
      streaming: true,
      onPermissionRequest: approveAll,
    });
    currentModel = model;
    attachSessionListeners(session);
    log(`session created model=${model}`);
  }
  return session;
}

async function handleMessage(msg) {
  const { id, method, params } = msg;
  try {
    let result;
    switch (method) {
      case "initialize": {
        githubToken = params?.githubToken ?? null;
        result = { protocolVersion: "0.1" };
        break;
      }
      case "session.sendAndWait": {
        const model = params?.model;
        const prompt = params?.prompt;
        if (!model) throw new Error("'model' is required");
        if (!prompt) throw new Error("'prompt' is required");
        const s = await ensureSession(model);
        const r = await s.sendAndWait({ prompt });
        result = {
          content: r?.data?.content ?? null,
          stopReason: r?.data?.stopReason ?? null,
        };
        break;
      }
      case "shutdown": {
        if (session) {
          try { await session.disconnect(); } catch {}
        }
        if (client) {
          try { await client.stop(); } catch {}
        }
        if (id !== undefined) writeMessage({ jsonrpc: "2.0", id, result: null });
        process.exit(0);
        return;
      }
      default:
        throw new Error(`unknown method: ${method}`);
    }
    if (id !== undefined) writeMessage({ jsonrpc: "2.0", id, result });
  } catch (e) {
    log("error handling", method, ":", e?.message ?? e);
    if (id !== undefined) {
      writeMessage({
        jsonrpc: "2.0",
        id,
        error: { code: -32000, message: String(e?.message ?? e) },
      });
    }
  }
}

log("ready");
