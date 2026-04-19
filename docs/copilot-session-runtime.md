# Copilot session runtime

`copilotRuntime: "session"` routes Copilot-provider prompts through
`@github/copilot-sdk` via a Node sidecar so the SDK agent's internal
ReAct loop runs inside **one** billable `sendAndWait` per user prompt,
regardless of how many tool calls or LLM iterations happen inside. Same
work that costs 5 premium requests on the `/chat/completions` path
costs 1 here.

This document is the current contract for operators and reviewers.
Everything below reflects `main` as of Pre-Phase 3 (#12); follow-ups
live in issues #5 (Phase 3) and #6 (Phase 4).

## Why this project exists — Copilot billing facts

ace-copilot only makes sense if you know exactly what you are working
against. These are the concrete, operator-verified facts that motivate
everything below.

### 1. Copilot bills by "premium request", and the quota is hard-capped per month

Unlike pay-as-you-go APIs (Anthropic, OpenAI direct, Ollama-local) where
you pay per token and there is no ceiling, **GitHub Copilot gives you a
fixed number of premium requests per month** and every LLM call that
hits their endpoint consumes one. When the monthly pool runs out,
you stop — there is no overage bill, there is no degraded tier, there
is no per-request top-up transparent to the agent. You run out, you
wait until the next billing cycle, or you upgrade the plan.

On **Copilot Enterprise** (1,000 premium requests/month per seat —
the canonical reference plan for this project), a single request
consumes **0.1%** of the entire monthly budget. That is the full
resolution of your month. A single complex task on the stock chat
path can eat 0.5–1% of your month.

### 2. The session endpoint applies a flat 3× multiplier on top of the published model multipliers

GitHub publishes a "model multiplier" table — 0.33x for Haiku-class,
1x for mid-range Claude and GPT, higher for frontier models. **The
session SDK path (`sendAndWait`) multiplies that table by 3**. Verified
by repeated observation of https://github.com/settings/copilot/usage
across many turns:

| Model | Published multiplier (chat path) | Observed per-turn on session path | Ratio |
| --- | --- | --- | --- |
| Claude Haiku 4.5 | 0.33× | **1×** | 3× |
| Claude Sonnet 4.5 | 1× | **3×** | 3× |
| Claude Sonnet 4.6 | 1× | **3×** | 3× |
| GPT-5.4 | 1× | **3×** | 3× |

Operational consequence: **Haiku is the cheapest option on session
mode too**. Three Haiku session turns fit in the premium budget of one
Sonnet session turn. On Enterprise (1,000 requests/month), a Sonnet
session turn consumes **0.3%** of your monthly budget; a Haiku session
turn consumes **0.1%**.

This project defaults to Haiku on session mode for that reason. When
you explicitly need Sonnet/Opus/GPT capability, use them — but budget
for the 3× cost relative to Haiku and be explicit about the tradeoff.

(The chat-completions path still honors the published multipliers
without the 3× surcharge. If GitHub ever publishes clarifying
documentation for the agent endpoint, update this section.)

### 3. Copilot reduces the native context window on some Claude models

Claude models have generous native context windows — Sonnet 4.5 ships
with 200K (or 1M with the context-1M beta), for example. GitHub
Copilot proxies Claude through their own infrastructure and **reduces
the effective context window below Claude's native limit** on at least
some of the supported Claude SKUs. Operators should not assume the
Copilot-branded Claude model has the context window advertised on
Anthropic's model card.

One premium request on Enterprise is 0.1% of the month; a single
`sendAndWait` that truncates silently and has to be reissued doubles
that cost with no way to see it coming.

This matters directly for long agent runs: a Copilot-side context
trim that happens silently can truncate conversation state the agent
was relying on, and we cannot compensate by compacting harder from
our side without burning additional premium requests (which is
exactly why the Phase 4 decision dropped our compaction summary on
the session path — see below).

### What this means for the architecture

Every design decision in the rest of this document — one `sendAndWait`
per user turn, planner kept in-session, compaction dropped, post-turn
learning skipped, honest per-turn and session-total billing UX — is a
direct answer to these three facts. The goal is to make the most of a
finite, opaque, capped budget inside a proxy that trims context
without telling us.

## Turning it on

```json
{
  "profiles": {
    "copilot": {
      "provider": "copilot",
      "model": "claude-haiku-4.5",
      "copilotRuntime": "session",
      "apiKey": "<GitHub OAuth or PAT with Copilot entitlement>"
    }
  }
}
```

Defaults to `"chat"` — existing installs see no change. The legacy
`copilotRuntimeAcceptUnsandboxed` field is accepted for backward compat
but is a no-op; daemon logs an info line if it is still present.

## Requirements

- Node.js 20+ on `PATH`. Daemon preflights this at startup; missing
  Node falls back to `chat` with a pointed ERROR log.
- The ace-copilot CLI distribution ships the sidecar under
  `<appHome>/sidecar/` (installed automatically by
  `installSidecarDeps` during `installDist`).
- GitHub token resolution reuses `CopilotTokenProvider`: cached OAuth
  → `apiKey` → `GITHUB_TOKEN` → `GH_TOKEN` → `gh auth token`.

## What's supported

- One long-lived Copilot SDK session per ace-copilot session. Sidecar
  reuses the SDK session across turns when model + tool catalog are
  stable.
- `defineTool` registration of ace-copilot's real tools (6 core +
  MCP / memory / skill / extras). The sidecar also installs an
  `onPreToolUse` allowlist that blocks every SDK built-in so
  ace-copilot is the only tool surface.
- Permission enforcement via `PermissionAwareTool` → `PermissionManager`
  → existing TUI `permission.request` round-trip for WRITE / EXECUTE.
  Sidecar's `onPermissionRequest` only approves `custom-tool` kinds;
  everything else (shell / mcp / url / memory / hook directly from the
  SDK) is denied.
- Per-turn tool-catalog refresh. The registry is re-evaluated against
  the live daemon state each prompt, so MCP tools that register
  asynchronously reach subsequent turns.
- Remote context-reset warnings surfaced as yellow `[warning: ...]`
  in the TUI (backed by `stream.warning` on the wire, buffered in
  `BackgroundOutputBuffer` for `/bg` tasks).
- Tool activity via `stream.tool_use` / `stream.tool_completed` —
  parity with the chat path's turn-summary rendering.

## Clarification routing (Phase 3, #5)

When the SDK agent fires `ask_user`, the sidecar round-trips the
question through the daemon to the TUI. Answering costs **0**
additional premium requests — the exchange stays inside the current
`sendAndWait`. The TUI shows a short `[waiting for clarification]`
line, a dedicated answer prompt, and whether the clarification was
answered or cancelled.

Two commands the user can type at the clarification prompt:

- **plain text** — treated as the answer. If the text matches one of
  the SDK's offered `choices` (case-insensitive), it's sent back with
  `wasFreeform: false`; otherwise as free-form.
- **`/new <prompt>`** — cancels the pending clarification (sidecar
  returns `cancel: true` to the SDK, agent wraps up the current turn),
  then queues `<prompt>` as the next task. A bare `/new` cancels
  without queuing anything and returns the session to ready.

Timeout: if the user does not answer within **5 minutes**, the TUI
auto-cancels and the agent wraps up. Daemon-side has a matching
+30-second deadline as a safety net — either side firing first yields
the same shape (cancel response) so no state is ever wedged.

### Policy: mix A→B fallback (locked)

The `@github/copilot-sdk` does not expose an externally-callable
`respondToUserInput` — the only way to resolve an `ask_user` is from
inside its `onUserInputRequest` callback (see
`experiments/copilot-session-probe/probe-fake-pending.mjs`). This
means 0-premium follow-ups are only possible when the agent is
currently pending on `ask_user`. The runtime therefore uses a two-path
policy:

- **A (preferred)**: agent stays in `ask_user` between turns →
  user's typed answer resolves the callback → 0 premium.
- **B (fallback)**: agent finished `sendAndWait` without pending →
  typed input becomes a new `sendAndWait` on the same SDK session
  → context preserved but +1 premium.

Hit rate of A depends on prompt steering (agent discipline in closing
each turn with `ask_user`), tracked in Phase 3 c4. There is no
additional code-level trick that achieves 0-premium follow-up —
pretending a pending state exists would silently desync from SDK
reality.

## Phase 4 locked decisions (#6)

Three chat-mode subsystems that are **intentionally** not invoked on
the session path. Each is an explicit product decision under the
Copilot-only + savings-first policy, not a silent gap:

| Subsystem | Decision | Why |
| --- | --- | --- |
| TaskPlanner / replan / plan-step executor | **In-session via prompt steering (c4).** The SDK agent plans inside the same `sendAndWait` when the task warrants it, nudged by the steering preamble. No extra premium. | Preserves planning quality without a separate billable plan turn. |
| Compaction summary | **Dropped.** SDK manages context internally on the session path. | Running our summary would be double work and cost an extra premium per trigger. Revisit if operators report turn truncation. |
| Post-turn learning (`SkillRefinementEngine`, `DynamicSkillGenerator`, `SessionSkillPacker`) | **Kept skipped.** Restoring these on session mode under the Copilot-only policy would add +1–3 premium per turn, directly undercutting Phase 3's savings. The learning pipeline still runs on chat mode; it is off on session mode by design. | Savings first. Reconsider if a 0-premium path emerges (e.g. SDK lets a sub-task run inside the same `sendAndWait`). |

Telemetry to verify: every session-path turn emits
`usage.copilot.subsystemsSkipped = "planner,compaction,post_turn_learning"`.
That string is stable and grep-friendly — if any future change tries
to wire one of these back onto session mode silently, it would have
to drop the corresponding entry, which is a visible diff.

## Intentionally deferred

| Area | Status | Tracked in |
| --- | --- | --- |
| Structured-form elicitation (MCP etc.) | Auto-declined + yellow warning in TUI | #15 |
| Incremental mid-execution output for long-running tools (`bash`, etc.) | One `stream.tool_completed` payload at the end, not streamed | #5 |
| Cancel with wire-level interrupt | Best-effort; cancel takes effect after the current `sendAndWait` | #5 |

## Diagnostic fields

Every session-path JSON-RPC response includes `result.usage.copilot`:

| Field | Meaning |
| --- | --- |
| `runtime` | Always `"session"` on this path. Useful if a tool renders output from multiple runtimes. |
| `usageEventCount` | Number of `assistant.usage` events observed inside this turn. >1 means the SDK agent ran multiple internal LLM calls (typically 1 initial + N tool-result continuations). All of them together cost one premium request. |
| `premiumUsedBefore` / `premiumUsedAfter` | Snapshots of `premium_interactions.usedRequests` at the first and last `assistant.usage` events of the turn. Useful for operators wanting raw numbers. |
| `previousTurnPremiumUsed` | The `lastUsage.premiumUsed` stored from the **previous** turn on this session. Baseline for the honest cross-turn delta. |
| `premiumDeltaSinceLastTurn` | **Session-counter advance observed since the previous turn's last sample** — `currentTurn.last.premiumUsed - previousTurnPremiumUsed`. Not a per-turn billing attribution: GitHub's counter is eventually consistent across turns, so a billable turn's +1 can land on a later turn's observation window (and vice-versa). Reliable only when accumulated across many turns — use the sum to reconcile with `https://github.com/settings/copilot/usage`. `-1` on the first turn (no baseline). |
| `intraTurnPremiumDelta` | Diagnostic only. Subtraction inside the turn (`last - first`). The SDK does not guarantee `first` is a pre-billing baseline — GitHub's counter updates asynchronously and can land mid-stream, so this alone can report 0 for a billable turn or vice-versa. Prefer the session-level advance in `premiumDeltaSinceLastTurn` over any single-turn reading. |
| `initiatorFirst` / `initiatorLast` | `"user"` for the initial turn kickoff, `"agent"` for SDK-driven continuations. |
| `wallMs` | Total wall time of the turn. |

### `stream.warning` reasons

| `reason` | When |
| --- | --- |
| (unset) | Legacy pre-Phase-2 warnings (model change, etc.) |
| `tool_catalog_changed` | The tool set shifted between turns; remote SDK session was recreated and prior Copilot context discarded. Local transcript still reads continuously. |
| `elicitation_declined` | The SDK (or an MCP server it invoked) requested structured-form input, which this runtime cannot surface yet. Auto-declined. |

## Phase 3 live acceptance walkthrough

Run this end-to-end once after any Phase 3 change to verify the mix
A→B policy is holding. Each step records an **ideal**
`premiumDeltaSinceLastTurn` — what you'd expect if GitHub's counter
updated synchronously. In practice that counter is eventually
consistent across turns, so a billable turn's +1 can land on the next
turn's observation window (or vice versa).

What this means for this walkthrough:

- Individual per-step deltas can drift by ±1 from the "ideal" column
  without indicating a bug. A clarification answer (A-path) can still
  report +1 if a previous turn's accounting is propagating late;
  conversely a `/new` turn (B-path) can report 0 if its own +1 hasn't
  surfaced yet.
- The **accumulated** sum across the whole scenario is the reliable
  signal. It should match the absolute `premiumUsed` counter advance
  visible in window A, and — after a few minutes of propagation — the
  dashboard reconciliation in the final section.
- The comparison against the legacy `chat` path holds regardless of
  counter timing: the legacy path would have incurred ~1 premium per
  LLM call in any of these steps (5×+ total).

If an individual step reports an "unexpected" delta, keep going and
check the sum at the end. Only a persistent accumulated miss is a
regression.

### Setup

Two terminal windows:

- **A** — daemon log tail:
  ```bash
  tail -f ~/.ace-copilot/logs/daemon.log | grep -E "Copilot session turn|user_input|pending"
  ```

- **B** — TUI: `./tui.sh` (starts the daemon if not running; profile
  should have `copilotRuntime: "session"` and working Copilot auth).

Before starting, note the baseline counter from any recent
`premiumUsed=N->M` line in window A.

### Scenario

| # | Action in TUI | Expected CLI summary line | Ideal per-step delta | Contributes to ideal total | Notes |
| --- | --- | --- | --- | --- | --- |
| 1 | Prompt: `summarize ace-copilot-core/build.gradle.kts and then end by asking whether I want a change` | `copilot: session counter +N since last turn` OR `unchanged` (either is fine) | +1 | +1 | First turn; baseline |
| 2 | At the `answer >` modal, type `add ace-copilot-sdk as an api dependency` | `unchanged` expected; `+1` acceptable if step 1's increment lands here | 0 (A-path) | +0 | Clarification answer — no new sendAndWait |
| 3 | Wait for task to complete and ask again | agent should end with another ask_user question | n/a | n/a | Phase 3 c4 — observe steering hit rate, not billing |
| 4 | Answer: `yes, apply it` | `unchanged` expected; `+1` acceptable | 0 (A-path) | +0 | Still A-path |
| 5 | Type `/new run the unit tests` at the clarification modal | current clarification cancels, new task starts; banner likely `+1` | +1 | +1 | Explicit B-path — `/new` is a new billable turn |
| 6 | Let new task complete (no ask_user); back to main prompt | `+1` or `unchanged` depending on when step 5's increment surfaces | +1 | +1 | Explicit B-path |
| 7 | Plain follow-up: `now undo that change` | banner expected to show `+1` at some point in the next 1–2 turns | +1 | +1 | Plain follow-up after idle = B-path |
| 8 | Put a long task in background: prompt something multi-step, then press any key to auto-`/bg` | task moves to background; main prompt returns | +1 | +1 | Backgrounded task |
| 9 | While backgrounded task emits an ask_user, TUI interrupts main prompt with `[Clarification] task #X -> ...` notice | drain fires → clarification modal → answer | 0 (A-path) | +0 | Background-task clarification path |

### Success criteria

- **Accumulated** `usage.copilot.premiumDeltaSinceLastTurn` across all
  turns matches the absolute `premiumUsed` counter advance seen in
  window A, and the ideal total (5) within ±1–2 (propagation drift).
- Per-step banners never mis-assert causality: the CLI says
  "session counter +N since last turn", not "this turn was billable"
  — so a clarification answer showing +1 is not called out as a bug.
- Steering observation (step 3): the agent closes with another
  ask_user instead of ending silently. If it consistently skips
  ask_user across multiple runs, the c4 steering block may need
  tuning.
- At no point does the session wedge: if anything hangs more than
  15s without activity, inspect the daemon log for pending /
  unanswered user_input entries.

### Dashboard reconciliation

After running the scenario, wait ~10 minutes and check
`https://github.com/settings/copilot/usage` (for the account whose
token the sidecar is using). The `Used` count should advance by the
same total as the in-log deltas (±1 for rounding / propagation lag).

## Billing verification

Authoritative: GitHub's `premium_interactions.usedRequests` delta over
time. To compute what a session actually billed locally:

```bash
grep "Copilot session turn" ~/.ace-copilot/logs/daemon.log | tail -50 | \
  awk -F'premiumUsed=' '{split($2, a, "->"); \
    gsub(/[^0-9]/, "", a[1]); gsub(/[^0-9]/, "", a[2]); \
    if(!first) first=a[1]; last=a[2]} \
    END {print "session billing delta: " (last - first)}'
```

This matches `https://github.com/settings/copilot/usage` up to the
dashboard's propagation lag (minutes to hours).

## Troubleshooting

- **"copilotRuntime='session' requires Node.js on PATH"** at startup:
  install Node 20+ (`brew install node`, `nvm install 20`, etc.) and
  restart the daemon. Until then the daemon stays on `chat`.
- **"copilotRuntime='session' requires a sidecar directory"** at
  prompt time: the distribution is missing its `sidecar/` subtree.
  Set `ACE_COPILOT_SIDECAR_DIR` to a populated sidecar or rebuild the
  CLI distribution (`./gradlew :ace-copilot-cli:installDist`).
- **Agent answers without using tools even though you asked it to**:
  the SDK agent decides tool use on its own; our registration +
  allowlist only constrain *which* tools it can use. A prompt that
  explicitly says "Use tools" usually coaxes it.
- **`premiumDeltaSinceLastTurn: -1` on every turn**: the daemon's
  per-session baseline map is keyed on `sessionId`. If you destroy +
  recreate the ace-copilot session between prompts, each new prompt
  is a first turn with no baseline. Long-running TUI sessions should
  show real deltas after the first turn.
