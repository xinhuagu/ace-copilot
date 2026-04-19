// Probe: what happens if we try to "continue" after sendAndWait has already
// resolved and there is no real pending user_input request?
//
// Goal:
//   1. Run a prompt that should complete naturally without ask_user
//   2. Verify whether the SDK exposes any valid after-the-fact user-input
//      continuation surface once the turn has gone idle
//   3. Show the practical fallback: another sendAndWait in the same session
//
// Run:
//   cd experiments/copilot-session-probe
//   node probe-fake-pending.mjs
//   MODEL=claude-haiku-4.5 node probe-fake-pending.mjs
//   GH_ACCOUNT=xinhua-gu_acncp node probe-fake-pending.mjs

import { CopilotClient, approveAll } from "@github/copilot-sdk";
import { execFileSync } from "node:child_process";

const MODEL = process.env.MODEL ?? "claude-haiku-4.5";
const GH_ACCOUNT = process.env.GH_ACCOUNT ?? null;

function resolveAccountToken(account) {
  const out = execFileSync("gh", ["auth", "token", "--user", account], {
    encoding: "utf8",
    stdio: ["ignore", "pipe", "pipe"],
  });
  const token = out.trim();
  if (!token) throw new Error(`gh auth token --user ${account} returned empty`);
  return token;
}

const NATURAL_PROMPT = `
Answer the following in exactly one short paragraph and then STOP.
Do not ask any clarifying questions.
Do not use ask_user.

Question: what is the purpose of a README file in a software project?
`.trim();

const FOLLOW_UP_PROMPT = `
Continue the same topic in one short paragraph: what should a good README include?
Do not ask any clarifying questions.
`.trim();

async function main() {
  const started = Date.now();
  const clientOpts = {};
  if (GH_ACCOUNT) {
    clientOpts.githubToken = resolveAccountToken(GH_ACCOUNT);
    clientOpts.useLoggedInUser = false;
    console.log(`[probe] using gh account: ${GH_ACCOUNT} (token resolved, gh active account unchanged)`);
  }

  const client = new CopilotClient(clientOpts);
  console.log(`[probe] client created (model=${MODEL})`);

  let userInputRequests = 0;
  const timeline = [];

  const session = await client.createSession({
    model: MODEL,
    streaming: true,
    onPermissionRequest: approveAll,
    onUserInputRequest: async (req) => {
      userInputRequests++;
      timeline.push({
        t: Date.now() - started,
        event: "unexpected.user_input.requested",
        question: req?.question ?? "",
      });
      console.log("\n[probe] UNEXPECTED ask_user fired:");
      console.log(`        requestId: ${req?.requestId ?? "(none)"}`);
      console.log(`        question:  ${req?.question ?? "(none)"}`);
      return { answer: "No clarification should have been needed here.", wasFreeform: true };
    },
  });

  session.on((event) => {
    if (event?.type === "assistant.message") {
      timeline.push({ t: Date.now() - started, event: "assistant.message" });
    } else if (event?.type === "session.idle") {
      timeline.push({ t: Date.now() - started, event: "session.idle" });
    }
  });

  console.log("\n[probe] turn #1: prompt that should finish naturally (no ask_user)\n");
  const first = await session.sendAndWait({ prompt: NATURAL_PROMPT });
  console.log("\n[probe] turn #1 resolved");
  console.log(`  content?   ${first?.data?.content ? "yes" : "no"}`);
  console.log(`  stopReason ${first?.data?.stopReason ?? "(unknown)"}`);
  console.log(`  ask_user events seen: ${userInputRequests}`);

  const publicKeys = Object.getOwnPropertyNames(Object.getPrototypeOf(session))
    .filter((k) => !k.startsWith("_"))
    .sort();
  console.log("\n[probe] public session methods:");
  console.log(" ", publicKeys.join(", "));
  console.log(`[probe] typeof session.respondToUserInput = ${typeof session.respondToUserInput}`);

  const rpcKeys = session.rpc ? Object.keys(session.rpc).sort() : [];
  console.log(`[probe] top-level session.rpc keys: ${rpcKeys.join(", ")}`);
  const userInputRpcKeys = rpcKeys.filter((k) => k.toLowerCase().includes("user"));
  console.log(`[probe] session.rpc keys mentioning 'user': ${userInputRpcKeys.join(", ") || "(none)"}`);

  let fakeContinuationOutcome = "not-attempted";
  let fakeContinuationError = "";
  if (typeof session.respondToUserInput === "function") {
    try {
      console.log("[probe] attempting session.respondToUserInput with fake requestId...");
      const r = await session.respondToUserInput({
        requestId: "fake-after-resolve-request-id",
        response: "Pretend this is an answer after the turn already ended.",
      });
      fakeContinuationOutcome = "resolved";
      console.log("[probe] fake continuation unexpectedly resolved:", r);
    } catch (e) {
      fakeContinuationOutcome = "threw";
      fakeContinuationError = e?.message ?? String(e);
      console.log(`[probe] fake continuation threw: ${fakeContinuationError}`);
    }
  } else {
    fakeContinuationOutcome = "api-missing";
    console.log("[probe] no public session.respondToUserInput API exists after the turn resolved.");
  }

  console.log("\n[probe] turn #2: explicit second sendAndWait in the SAME session\n");
  const second = await session.sendAndWait({ prompt: FOLLOW_UP_PROMPT });
  console.log("\n[probe] turn #2 resolved");
  console.log(`  content?   ${second?.data?.content ? "yes" : "no"}`);
  console.log(`  stopReason ${second?.data?.stopReason ?? "(unknown)"}`);

  console.log("\n[probe] ---------- SUMMARY ----------");
  console.log(`  turn #1 ask_user count:      ${userInputRequests}`);
  console.log(`  fake continuation outcome:   ${fakeContinuationOutcome}`);
  if (fakeContinuationError) {
    console.log(`  fake continuation error:     ${fakeContinuationError}`);
  }
  console.log("  observed fallback:           second sendAndWait in same SDK session succeeds");
  console.log("  interpretation:");
  console.log("    - If turn #1 ended without ask_user, there was no real pending question.");
  console.log("    - After that, the SDK surface does not provide a normal post-resolve answer path here.");
  console.log("    - Continuing is still possible, but as another sendAndWait in the same session.");

  if (timeline.length) {
    console.log("  timeline:");
    for (const e of timeline) {
      const extra = e.question ? ` :: ${e.question.slice(0, 60)}` : "";
      console.log(`    +${String(e.t).padStart(6)}ms  ${e.event}${extra}`);
    }
  }

  await client.stop();
}

main().catch((err) => {
  console.error("\n[probe] error:", err?.stack ?? err);
  process.exit(1);
});
