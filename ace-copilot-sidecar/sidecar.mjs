// ace-copilot Copilot SDK sidecar.
//
// Bridges the Java ace-copilot daemon to @github/copilot-sdk over LSP-framed
// JSON-RPC on stdio. One sidecar process = one long-lived Copilot session;
// the daemon spawns one sidecar per ace-copilot session.
//
// Wire protocol:
//   - Framing: LSP-style `Content-Length: N\r\n\r\n<body>` (UTF-8 JSON)
//   - Requests from daemon → sidecar:
//       initialize          { githubToken?, tools?: [{name, description, parameters}] }
//                            → { protocolVersion }
//       session.sendAndWait { model, prompt }   → { content, stopReason }
//       shutdown            null                → null (then exit 0)
//   - Notifications from sidecar during session.sendAndWait:
//       session/text   { delta }
//       session/usage  { model, inputTokens, outputTokens, cost, initiator,
//                        premiumUsed, premiumLimit }
//   - Requests from sidecar → daemon (Phase 2, issue #4):
//       tool.invoke         { name, arguments } → { content, isError? }
//       permission.request  { kind, toolName?, fileName?, command?, url?, reason? }
//                            → { decision: "approved"|"denied-interactively-by-user"
//                                         |"denied-by-rules", reason? }
//   - Logs go to stderr, not stdout (stdout carries framed JSON-RPC only).

import { CopilotClient, defineTool } from "@github/copilot-sdk";

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
    // Responses to sidecar → daemon requests: msg has id + result/error, no method.
    if (msg.id !== undefined && (msg.result !== undefined || msg.error !== undefined) && msg.method === undefined) {
      const p = pendingOut.get(msg.id);
      if (p) {
        pendingOut.delete(msg.id);
        if (msg.error) p.reject(new Error(msg.error.message ?? JSON.stringify(msg.error)));
        else p.resolve(msg.result);
      } else {
        log("unexpected response for id", msg.id);
      }
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
let toolDefs = [];           // [{ name, description, parameters }] from initialize
let toolAllowlist = null;    // Set of allowed tool names (Phase 2 allowlist hook)

// Outgoing request tracking (sidecar → daemon). Pending[id] = { resolve, reject }.
let nextOutgoingId = 1;
const pendingOut = new Map();

function request(method, params) {
  return new Promise((resolve, reject) => {
    const id = nextOutgoingId++;
    pendingOut.set(id, { resolve, reject });
    writeMessage({ jsonrpc: "2.0", id, method, params });
  });
}

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
    // Build ace-copilot tools via defineTool. Each handler proxies to the
    // daemon via `tool.invoke` so the real implementation lives in-daemon
    // (ace-copilot-tools), protected by PermissionManager + audit.
    const aceCopilotTools = toolDefs.map((def) =>
      defineTool(def.name, {
        description: def.description ?? "",
        parameters: def.parameters ?? { type: "object", properties: {} },
        // Allow override of SDK built-ins (e.g. glob, view/read_file) —
        // we register our own implementation backed by the daemon and
        // the allowlist hook below blocks any non-registered tool name.
        overridesBuiltInTool: true,
        handler: async (args) => {
          const r = await request("tool.invoke", { name: def.name, arguments: args ?? {} });
          if (r?.isError) {
            // SDK treats thrown errors as tool failures
            throw new Error(typeof r.content === "string" ? r.content : JSON.stringify(r.content));
          }
          return r?.content ?? "";
        },
      })
    );

    const sessionOpts = {
      model,
      streaming: true,
      onPermissionRequest: async (req) => {
        // Custom tools are gated by the daemon's PermissionAwareTool when
        // tool.invoke executes — approve here so the SDK calls our handler
        // which then does the real check (with TUI round-trip if needed).
        // Every other kind is denied because the ace-copilot tool surface
        // is the allowlisted custom set; anything else is defense-in-depth
        // against SDK built-ins / MCP / URLs / memory slipping past the
        // onPreToolUse hook.
        if (req?.kind === "custom-tool" || req?.kind === "custom_tool") {
          return { kind: "approved" };
        }
        return {
          kind: "denied-by-rules",
          reason: `kind="${req?.kind}" is not allowed under the ace-copilot custom-tool allowlist.`,
        };
      },
    };
    if (aceCopilotTools.length > 0) {
      sessionOpts.tools = aceCopilotTools;
      // Phase 2 allowlist hook: block any tool not registered by us so the
      // SDK's built-in filesystem/shell tools (view, write, bash, etc.)
      // cannot fire and bypass PermissionManager.
      toolAllowlist = new Set(toolDefs.map((t) => t.name));
      sessionOpts.hooks = {
        onPreToolUse: async (input) => {
          if (!toolAllowlist.has(input.toolName)) {
            return {
              permissionDecision: "deny",
              permissionDecisionReason:
                `Tool "${input.toolName}" is not exposed by ace-copilot. ` +
                `Only these tools are available: ${[...toolAllowlist].join(", ")}.`,
            };
          }
          return { permissionDecision: "allow" };
        },
      };
    }
    session = await client.createSession(sessionOpts);
    currentModel = model;
    attachSessionListeners(session);
    log(`session created model=${model} tools=${aceCopilotTools.length}`);
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
        toolDefs = Array.isArray(params?.tools) ? params.tools : [];
        result = { protocolVersion: "0.1", toolsRegistered: toolDefs.length };
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
