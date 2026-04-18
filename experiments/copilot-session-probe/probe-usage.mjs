// Track B preliminary probe: dump full SDK event payloads to look for any
// billing / usage / premium-request metadata that might be surfaced to the
// client. If nothing appears, billing verification must be done via the
// user's GitHub settings dashboard (no per-user usage API exists).
//
// Run: GH_ACCOUNT=xinhua-gu_acncp MODEL=claude-haiku-4.5 node probe-usage.mjs

import { CopilotClient, approveAll } from "@github/copilot-sdk";
import { execFileSync } from "node:child_process";

const MODEL = process.env.MODEL ?? "gpt-4.1";
const GH_ACCOUNT = process.env.GH_ACCOUNT ?? null;

function resolveAccountToken(account) {
  return execFileSync("gh", ["auth", "token", "--user", account], {
    encoding: "utf8", stdio: ["ignore", "pipe", "pipe"],
  }).trim();
}

const AMBIGUOUS_PROMPT = `
I need a tiny utility. Ask me exactly TWO clarifying questions using ask_user,
wait for each answer, then STOP without writing any code. Just summarize.
`.trim();
const ANSWERS = ["Echo stdin to stdout.", "Node.js."];

function sanitize(obj, depth = 0) {
  if (depth > 3 || obj == null) return obj;
  if (typeof obj === "string") return obj.length > 120 ? obj.slice(0, 120) + "…" : obj;
  if (Array.isArray(obj)) return obj.slice(0, 3).map((v) => sanitize(v, depth + 1));
  if (typeof obj === "object") {
    const out = {};
    for (const [k, v] of Object.entries(obj)) out[k] = sanitize(v, depth + 1);
    return out;
  }
  return obj;
}

async function main() {
  const opts = {};
  if (GH_ACCOUNT) { opts.githubToken = resolveAccountToken(GH_ACCOUNT); opts.useLoggedInUser = false; }
  const client = new CopilotClient(opts);

  let uiCount = 0;
  const session = await client.createSession({
    model: MODEL,
    streaming: true,
    onPermissionRequest: approveAll,
    onUserInputRequest: async (req) => {
      uiCount++;
      return { answer: ANSWERS[uiCount - 1] ?? "proceed", wasFreeform: true };
    },
  });

  // Log EVERY assistant.usage event — this carries quotaSnapshots.premium_interactions.usedRequests,
  // the real counter we need to verify per-session vs per-turn billing.
  const usages = [];
  session.on((event) => {
    if (event?.type === "assistant.usage") {
      const q = event.data?.quotaSnapshots?.premium_interactions;
      const snap = {
        t: new Date().toISOString(),
        apiCallId: event.data?.apiCallId,
        initiator: event.data?.initiator,
        inputTokens: event.data?.inputTokens,
        outputTokens: event.data?.outputTokens,
        cost: event.data?.cost,
        premiumUsed: q?.usedRequests,
        premiumRemainingPct: q?.remainingPercentage,
      };
      usages.push(snap);
      console.log(`[usage#${usages.length}] premium_used=${snap.premiumUsed}  initiator=${snap.initiator}  apiCall=${snap.apiCallId?.slice(0,8)}  cost=${snap.cost}`);
    }
  });

  console.log(`[probe] sending prompt (GH_ACCOUNT=${GH_ACCOUNT ?? "default"}, MODEL=${MODEL})`);
  const result = await session.sendAndWait({ prompt: AMBIGUOUS_PROMPT });

  console.log("\n[probe] ---------- BILLING VERDICT ----------");
  console.log(`  user_input.requested fired:      ${uiCount}`);
  console.log(`  assistant.usage events fired:    ${usages.length}`);
  if (usages.length > 0) {
    const first = usages[0].premiumUsed;
    const last = usages[usages.length - 1].premiumUsed;
    const delta = last - first;
    console.log(`  premium_interactions.usedRequests:`);
    console.log(`    before first turn (reported): ${first}`);
    console.log(`    after last turn  (reported):  ${last}`);
    console.log(`    delta across session:         ${delta}`);
    console.log("  initiators per usage event:");
    usages.forEach((u, i) => console.log(`    #${i + 1} initiator=${u.initiator}  premium_used=${u.premiumUsed}`));
    console.log(`\n  Interpretation:`);
    console.log(`    If delta == 1 despite ${uiCount} user_input round-trips → session counts as 1 request.`);
    console.log(`    If delta == ${uiCount + 1} or matches assistant.usage count → each LLM turn is a request.`);
  }

  await client.stop();
}

main().catch((e) => { console.error(e); process.exit(1); });
