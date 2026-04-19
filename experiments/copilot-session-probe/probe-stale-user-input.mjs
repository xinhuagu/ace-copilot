// Probe: what happens when the SDK has already resolved a sendAndWait
// (no pending user_input) and we then try to "continuation-respond" with
// a stale, forged, or never-registered requestId?
//
// Phase 3 (#5) c3 assumes "sendAndWait returned == no pending clarification
// on daemon side == safe to treat as normal prompt submission". This probe
// pins the assumption across all three layers (SDK, sidecar, daemon) so the
// state machine doesn't silently corrupt if the assumption is wrong.
//
// Scenarios exercised in sequence against the same session:
//   T1 — Baseline:        send a prompt that completes without ask_user.
//                          Verify a normal JSON-RPC response + usage.copilot.
//   T2 — Forged requestId: post user_input.response { requestId:"forged" }.
//                          Daemon has no pending entry → expect warn + drop,
//                          session stays healthy, no wedge.
//   T3 — Stale requestId:  remember the sendAndWait's completion, then post
//                          user_input.response { requestId: T1's response
//                          apiCallId or similar } — another false match.
//                          Same expectation.
//   T4 — Follow-up prompt: immediately send another agent.prompt on the same
//                          session. Verify it returns normally and premium
//                          counter advances (rules out "stuck state").
//
// Run:
//   node experiments/copilot-session-probe/probe-stale-user-input.mjs [workspace]

import net from "node:net";
import os from "node:os";
import path from "node:path";

const SOCK = path.join(os.homedir(), ".ace-copilot", "ace-copilot.sock");
const WORKSPACE = process.argv[2] ?? process.cwd();
const PROMPT = "What is 2+2? Reply with just the number. Do not use any tools or ask any clarifying questions.";

const client = net.createConnection(SOCK);
let buf = "";
let nextId = 1;
const pending = new Map();
const notifications = [];

function send(obj) {
  client.write(JSON.stringify(obj) + "\n");
}

function request(method, params) {
  return new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    send({ jsonrpc: "2.0", id, method, params });
  });
}

function notify(method, params) {
  send({ jsonrpc: "2.0", method, params });
}

client.on("data", (chunk) => {
  buf += chunk.toString("utf8");
  let nl;
  while ((nl = buf.indexOf("\n")) >= 0) {
    const line = buf.slice(0, nl);
    buf = buf.slice(nl + 1);
    if (!line) continue;
    let m;
    try { m = JSON.parse(line); } catch { continue; }
    if (m.method && !m.id) {
      notifications.push(m);
    } else if (m.id !== undefined) {
      const p = pending.get(m.id);
      if (p) {
        pending.delete(m.id);
        if (m.error) p.reject(new Error(m.error.message ?? JSON.stringify(m.error)));
        else p.resolve(m.result);
      }
    }
  }
});

client.on("error", (e) => { console.error("[sock]", e.message); process.exit(1); });

async function main() {
  const health = await request("health.status", {});
  console.log(`[probe] daemon model=${health?.model}`);

  const session = await request("session.create", { project: WORKSPACE });
  const sid = session?.sessionId ?? session?.id;
  console.log(`[probe] session=${sid}`);

  // T1 — baseline: a prompt that completes without ask_user.
  console.log("\n=== T1: baseline sendAndWait (no pending expected) ===");
  const t1Start = Date.now();
  const t1 = await request("agent.prompt", { sessionId: sid, prompt: PROMPT });
  const t1Wall = Date.now() - t1Start;
  console.log(`  stopReason=${t1?.stopReason} wallMs=${t1Wall}`);
  console.log(`  response head: ${JSON.stringify(t1?.response).slice(0, 80)}`);
  const t1Cop = t1?.usage?.copilot ?? {};
  console.log(`  copilot.usageEventCount=${t1Cop.usageEventCount} premiumUsed=${t1Cop.premiumUsedBefore}->${t1Cop.premiumUsedAfter}`);
  const notifsDuringT1 = notifications.splice(0).filter(n =>
    n.method === "user_input.requested" || n.method === "stream.warning"
  );
  console.log(`  notifications of interest during T1: ${notifsDuringT1.length}`);
  for (const n of notifsDuringT1) {
    console.log(`    - ${n.method}: ${JSON.stringify(n.params).slice(0, 120)}`);
  }

  // T2 — forged requestId after T1's turn ended.
  console.log("\n=== T2: user_input.response with forged requestId ===");
  notify("user_input.response", {
    requestId: "forged-" + Math.random().toString(36).slice(2, 10),
    answer: "x",
    wasFreeform: true,
    cancel: false,
  });
  // Small wait for monitor to process; no reply expected.
  await new Promise((r) => setTimeout(r, 400));
  console.log("  posted; expecting daemon log 'no pending user_input for requestId=...' and no session effect");

  // T3 — stale but structured-looking requestId.
  console.log("\n=== T3: user_input.response with a UUID-shaped stale requestId ===");
  notify("user_input.response", {
    requestId: "00000000-0000-0000-0000-000000000000",
    answer: "hi",
    wasFreeform: true,
    cancel: false,
  });
  await new Promise((r) => setTimeout(r, 400));
  console.log("  posted; same expectation — dropped, session unaffected");

  // T4 — session still healthy: next agent.prompt works, premium advances.
  console.log("\n=== T4: follow-up prompt on the same session ===");
  const t4Start = Date.now();
  const t4 = await request("agent.prompt", {
    sessionId: sid,
    prompt: "What is 3+3? Just the number. No tools, no questions.",
  });
  const t4Wall = Date.now() - t4Start;
  console.log(`  stopReason=${t4?.stopReason} wallMs=${t4Wall}`);
  console.log(`  response head: ${JSON.stringify(t4?.response).slice(0, 80)}`);
  const t4Cop = t4?.usage?.copilot ?? {};
  console.log(`  copilot.premiumUsed=${t4Cop.premiumUsedBefore}->${t4Cop.premiumUsedAfter} sinceLastTurn=${t4Cop.premiumDeltaSinceLastTurn}`);

  // Summary.
  console.log("\n========= VERDICT =========");
  const t1Ok = t1?.stopReason === "COMPLETE" && t1Wall < 60_000;
  const t4Ok = t4?.stopReason === "COMPLETE" && t4Wall < 60_000;
  console.log(`  T1 completes normally:                    ${t1Ok ? "PASS" : "FAIL"}`);
  console.log(`  T2/T3 stray user_input.response tolerated: ${t4Ok ? "PASS (session healthy after noise)" : "FAIL"}`);
  console.log(`  T4 follow-up works + premium advances:    ${t4Ok ? "PASS" : "FAIL"}`);
  if (!t4Ok) {
    console.log("  → If T4 fails after T2/T3, stray notifications are corrupting state. Check daemon log.");
  }
  console.log("\n  Daemon log grep suggested:");
  console.log("    tail -100 ~/.ace-copilot/logs/daemon.log | grep -E \"user_input|pending\"");

  await request("session.destroy", { sessionId: sid }).catch(() => {});
  client.end();
}

main().catch((e) => { console.error("[probe error]", e); process.exit(1); });
