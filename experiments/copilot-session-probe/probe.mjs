// Track A probe for ace-copilot issue #1.
//
// Verifies that a standalone Node process can:
//   1. Start a Copilot session via @github/copilot-sdk
//   2. Receive user_input.requested events from inside that session
//   3. Respond programmatically and have the session continue
//      without issuing a new external prompt
//
// This is a pure feasibility check — no billing measurement here.
// Billing comparison belongs to Track B.
//
// Run:
//   cd experiments/copilot-session-probe
//   npm install
//   node probe.mjs                                      # uses gh active account
//   MODEL=gpt-4.1 node probe.mjs                        # override model
//   GH_ACCOUNT=xinhua-gu_acncp node probe.mjs           # use a specific gh account
//                                                         without touching `gh auth switch`
//
// Auth:
//   - If GH_ACCOUNT is set, resolves token via `gh auth token --user <acct>`
//     and passes it to the SDK explicitly (useLoggedInUser:false). This mirrors
//     ace-copilot's CopilotTokenProvider token resolution, scoped to a chosen
//     gh-stored account — does NOT alter the global active account.
//   - Otherwise, the SDK discovers credentials on its own.

import { CopilotClient, approveAll } from "@github/copilot-sdk";
import { execFileSync } from "node:child_process";

const MODEL = process.env.MODEL ?? "gpt-5";
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

const AMBIGUOUS_PROMPT = `
I want a small utility, but I have not decided the details.

BEFORE writing any code, you MUST ask me at least TWO separate clarifying
questions using the ask_user tool. Each question must wait for my answer
before the next one. Do not guess. Do not write code until I have answered.

Start by asking your first clarifying question now.
`.trim();

const CANNED_ANSWERS = [
  "A CLI that counts word frequencies in stdin.",
  "Node.js, ESM, no dependencies.",
  "Read from stdin; print a JSON object {word: count} to stdout; sort by count desc.",
];

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

  let userInputCount = 0;
  const timeline = [];

  const session = await client.createSession({
    model: MODEL,
    streaming: true,
    onPermissionRequest: approveAll,
    onUserInputRequest: async (req) => {
      userInputCount++;
      const answer =
        CANNED_ANSWERS[userInputCount - 1] ?? "No further details, please proceed.";

      console.log(`\n[probe] >>> user_input.requested #${userInputCount}`);
      console.log(`        question:      ${req.question}`);
      if (req.choices?.length) {
        console.log(`        choices:       ${req.choices.join(" | ")}`);
      }
      console.log(`        allowFreeform: ${req.allowFreeform ?? true}`);
      console.log(`[probe] <<< answering in-session: ${JSON.stringify(answer)}`);

      timeline.push({
        t: Date.now() - started,
        event: "user_input.requested",
        question: req.question,
      });
      return { answer, wasFreeform: true };
    },
  });
  console.log("[probe] session created");

  session.on("assistant.message_delta", (e) => {
    const chunk = e.data?.deltaContent ?? e.data?.delta ?? "";
    if (chunk) process.stdout.write(chunk);
  });
  session.on("assistant.message", () => {
    timeline.push({ t: Date.now() - started, event: "assistant.message" });
  });
  session.on("session.idle", () => {
    timeline.push({ t: Date.now() - started, event: "session.idle" });
  });

  console.log("\n[probe] sending ambiguous prompt, expecting mid-session clarifications...\n");
  await session.sendAndWait({ prompt: AMBIGUOUS_PROMPT });

  console.log("\n\n[probe] ---------- SUMMARY ----------");
  console.log(`  model:                       ${MODEL}`);
  console.log(`  user_input.requested fired:  ${userInputCount} time(s)`);
  console.log(`  timeline entries:            ${timeline.length}`);
  console.log("  timeline:");
  for (const e of timeline) {
    const extra = e.question ? ` :: ${e.question.slice(0, 60)}` : "";
    console.log(`    +${String(e.t).padStart(6)}ms  ${e.event}${extra}`);
  }

  const pass = userInputCount >= 1;
  console.log(`\n  TRACK A VERDICT:             ${pass ? "PASS" : "FAIL"}`);
  if (pass) {
    console.log("  -> Copilot SDK session-internal clarification is reachable from a Node process.");
    console.log("     Same session continued after in-handler answer (no new sendAndWait issued).");
    console.log("     Next: Track B billing comparison with N runs over the dashboard delta.");
  } else {
    console.log("  -> Agent never invoked ask_user. Mechanism unverified from this probe.");
    console.log("     Try a different model, a more constraining prompt, or inspect whether");
    console.log("     the SDK surfaces the event through a different channel.");
  }

  await client.stop();
  process.exit(pass ? 0 : 2);
}

main().catch((err) => {
  console.error("\n[probe] error:", err?.stack ?? err);
  process.exit(1);
});
