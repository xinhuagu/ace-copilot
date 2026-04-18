// One-shot JSON-RPC smoke client for the ace-copilot daemon UDS socket.
// Creates a session, sends agent.prompt, prints stream.text + usage.copilot.
// Used to verify Phase 1 (issue #3): copilotRuntime=session routes through
// the sidecar and reports premiumDelta.

import net from "node:net";
import os from "node:os";
import path from "node:path";

const SOCK = path.join(os.homedir(), ".ace-copilot", "ace-copilot.sock");
const WORKSPACE = process.argv[2] ?? process.cwd();
const PROMPT = process.argv[3] ?? "List the files in this directory and describe the project in one sentence. Use tools.";

const client = net.createConnection(SOCK);
let buf = "";
let nextId = 1;
const pending = new Map();

function send(msg) {
  client.write(JSON.stringify(msg) + "\n");
}
function request(method, params) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    send({ jsonrpc: "2.0", id, method, params });
  });
}

client.on("data", (chunk) => {
  buf += chunk.toString("utf8");
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl);
    buf = buf.slice(nl + 1);
    if (!line) continue;
    let msg;
    try { msg = JSON.parse(line); } catch { continue; }
    if (msg.method) {
      if (msg.method === "stream.text") {
        process.stdout.write(msg.params?.delta ?? "");
      } else if (msg.method === "stream.tool_use") {
        process.stderr.write(`\n[tool_use] ${msg.params?.name}(${msg.params?.summary ?? ""})\n`);
      } else if (msg.method === "stream.thinking") {
        // drop
      } else {
        process.stderr.write(`\n[notif] ${msg.method}\n`);
      }
    } else if (msg.id !== undefined) {
      const p = pending.get(msg.id);
      if (p) {
        pending.delete(msg.id);
        if (msg.error) p.reject(new Error(msg.error.message ?? JSON.stringify(msg.error)));
        else p.resolve(msg.result);
      }
    }
  }
});

client.on("error", (e) => { console.error("[sock]", e.message); process.exit(1); });
client.on("close", () => {});

async function main() {
  const health = await request("health.status", {});
  console.error("[health] model=" + health?.model);

  const session = await request("session.create", { project: WORKSPACE });
  const sessionId = session?.sessionId ?? session?.id;
  console.error("[session] id=" + sessionId);

  console.error("[prompt]", PROMPT);
  console.error("----");
  const start = Date.now();
  const result = await request("agent.prompt", { sessionId, prompt: PROMPT });
  const ms = Date.now() - start;
  console.error("\n----");
  console.error(`[result] stopReason=${result?.stopReason} wallMs=${ms}`);
  if (result?.usage) {
    console.error("[usage] " + JSON.stringify(result.usage, null, 2));
  }

  await request("session.destroy", { sessionId }).catch(() => {});
  client.end();
}

main().catch((e) => { console.error("[error]", e.message); process.exit(1); });
