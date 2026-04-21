// ace-copilot Copilot SDK sidecar.
//
// Bridges the Java ace-copilot daemon to @github/copilot-sdk over LSP-framed
// JSON-RPC on stdio. One sidecar process = one long-lived Copilot session;
// the daemon spawns one sidecar per ace-copilot session.
//
// Wire protocol:
//   - Framing: LSP-style `Content-Length: N\r\n\r\n<body>` (UTF-8 JSON)
//   - Requests from daemon → sidecar:
//       initialize          { githubToken? }
//                            → { protocolVersion }
//       session.sendAndWait { model, prompt,
//                             tools?: [{name, description, parameters}] }
//                            → { content, stopReason, toolsReset?: boolean }
//         — tools represent the CURRENT catalog for this turn. If the
//           signature (names + descriptions) differs from the active
//           session's snapshot the sidecar disconnects and recreates the
//           SDK session so async-registered MCP tools etc. can flow in
//           mid-conversation. toolsReset:true signals the daemon to
//           surface a context-reset warning to the TUI.
//       shutdown            null                → null (then exit 0)
//   - Notifications from sidecar during session.sendAndWait:
//       session/text   { delta }
//       session/usage  { model, inputTokens, outputTokens, cost, initiator,
//                        premiumUsed, premiumLimit }
//   - Requests from sidecar → daemon (Phase 2, issue #4):
//       tool.invoke         { name, arguments } → { content, isError? }
//   - Requests from sidecar → daemon (Phase 3, issue #5):
//       user_input.request  { requestId, question, choices?, allowFreeform? }
//                            → { answer: string, wasFreeform: boolean, cancel?: boolean }
//                            — fired when the SDK agent asks a clarifying
//                              question. The daemon blocks on this RPC while
//                              it awaits the user's response (through the TUI
//                              agent.respondToUserInput path). If the daemon
//                              returns { cancel: true } the sidecar responds
//                              to the SDK with a decline-shaped answer.
//   - Additional notifications (Pre-Phase 3, issue #12):
//       session/elicitation_declined
//                            { mode, message, elicitationSource, url }
//                            — fired when the SDK agent requests structured
//                              form input; auto-declined until Phase 3 (#5)
//                              grows a TUI surface for elicitation + user_input
//   - Logs go to stderr, not stdout (stdout carries framed JSON-RPC only).

import { CopilotClient, defineTool } from "@github/copilot-sdk";

const log = (...args) => process.stderr.write(`[sidecar] ${args.join(" ")}\n`);

// Matches CopilotAcpClient.java's 10-minute request() ceiling. The SDK's
// sendAndWait defaults to 60s — too tight for slower models, long tool
// chains, or user_input round-trips inside one turn.
const SEND_AND_WAIT_TIMEOUT_MS = 10 * 60 * 1000;

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
let currentToolSig = null;   // stable signature of the active session's tool set
let toolAllowlist = null;    // Set of allowed tool names (Phase 2 allowlist hook)

/**
 * Stable signature of a tool list: sorted name + description pairs, plus
 * schema serialization. Used to decide whether to recreate the SDK session
 * because the tool catalog shifted (e.g. async MCP registration).
 */
function toolSignature(defs) {
  if (!Array.isArray(defs) || defs.length === 0) return "empty";
  const parts = defs
    .map((t) => ({ n: t.name, d: t.description ?? "", p: t.parameters ?? {} }))
    .sort((a, b) => (a.n < b.n ? -1 : a.n > b.n ? 1 : 0))
    .map((t) => `${t.n}|${t.d}|${JSON.stringify(t.p)}`);
  return parts.join("\n");
}

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

async function ensureSession(model, toolDefs) {
  if (!client) {
    const opts = {};
    if (githubToken) {
      opts.githubToken = githubToken;
      opts.useLoggedInUser = false;
    }
    client = new CopilotClient(opts);
    log("CopilotClient created", githubToken ? "(with supplied token)" : "(using logged-in user)");
  }
  const newSig = toolSignature(toolDefs);
  const modelChanged = currentModel !== model;
  const toolsChanged = currentToolSig !== newSig;
  const hadPriorSession = session != null;
  if (!session || modelChanged || toolsChanged) {
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
    const defsForThisSession = Array.isArray(toolDefs) ? toolDefs : [];
    const aceCopilotTools = defsForThisSession.map((def) =>
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
      // Phase 3 (#5): the SDK agent can ask a clarifying question
      // (`ask_user` tool under the hood) — surface it to the daemon via
      // the long-running user_input.request RPC. The Promise returned
      // here only resolves when the daemon has the user's answer in
      // hand, which is what keeps the whole exchange inside a single
      // billable sendAndWait. If the daemon decides to cancel the
      // pending question (e.g. the user typed /new), we return a short
      // decline message so the SDK agent wraps up gracefully.
      onUserInputRequest: async (req, _invocation) => {
        try {
          const r = await request("user_input.request", {
            requestId: req?.requestId ?? null,
            question: req?.question ?? "",
            choices: Array.isArray(req?.choices) ? req.choices : [],
            allowFreeform: req?.allowFreeform !== false,
          });
          if (r?.cancel === true) {
            return {
              answer: "(user cancelled this clarification and started a new task)",
              wasFreeform: true,
            };
          }
          return {
            answer: typeof r?.answer === "string" ? r.answer : "",
            wasFreeform: r?.wasFreeform !== false,
          };
        } catch (e) {
          log("user_input bridge failed:", e?.message ?? e);
          return {
            answer: "(clarification could not be delivered — please proceed with best-effort assumptions)",
            wasFreeform: true,
          };
        }
      },
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
      // Pre-Phase 3 (#12): structured-form input from MCP servers or the
      // SDK agent. We do not yet have a TUI surface for elicitation
      // (Phase 3, #5 will land respondToUserInput / respondToElicitation
      // together), so decline deterministically and surface a warning up
      // to the TUI. Silent decline was the default before — explicit
      // decline + notification makes the behavior visible.
      onElicitationRequest: async (ctx) => {
        writeMessage({
          jsonrpc: "2.0",
          method: "session/elicitation_declined",
          params: {
            mode: ctx?.mode ?? null,
            message: ctx?.message ?? null,
            elicitationSource: ctx?.elicitationSource ?? null,
            url: ctx?.url ?? null,
          },
        });
        log(`elicitation auto-declined: mode=${ctx?.mode} source=${ctx?.elicitationSource}`);
        return { action: "decline", content: {} };
      },
    };
    if (aceCopilotTools.length > 0) {
      sessionOpts.tools = aceCopilotTools;
      // Phase 2 allowlist hook: block any tool not registered by us so the
      // SDK's built-in filesystem/shell tools (view, write, bash, etc.)
      // cannot fire and bypass PermissionManager.
      toolAllowlist = new Set(defsForThisSession.map((t) => t.name));
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
    currentToolSig = newSig;
    attachSessionListeners(session);
    log(`session created model=${model} tools=${aceCopilotTools.length}`
        + (hadPriorSession && toolsChanged && !modelChanged ? " (tool catalog changed)" : ""));
    return { session, toolsReset: hadPriorSession && toolsChanged && !modelChanged };
  }
  return { session, toolsReset: false };
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
        const tools = Array.isArray(params?.tools) ? params.tools : [];
        if (!model) throw new Error("'model' is required");
        if (!prompt) throw new Error("'prompt' is required");
        const ens = await ensureSession(model, tools);
        const r = await ens.session.sendAndWait({ prompt }, SEND_AND_WAIT_TIMEOUT_MS);
        result = {
          content: r?.data?.content ?? null,
          stopReason: r?.data?.stopReason ?? null,
          toolsReset: ens.toolsReset === true ? true : undefined,
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
