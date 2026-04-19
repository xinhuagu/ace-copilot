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

## Intentionally deferred

| Area | Status | Tracked in |
| --- | --- | --- |
| Prompt steering to maximise A hit rate | Agents may end turns without `ask_user`, falling back to B | #5 c4 |
| Structured-form elicitation (MCP etc.) | Auto-declined + yellow warning in TUI | #15 |
| Incremental mid-execution output for long-running tools (`bash`, etc.) | One `stream.tool_completed` payload at the end, not streamed | #5 |
| TaskPlanner consolidated inside the session | Still runs via separate LLM calls | #6 |
| SelfImprovementEngine (`ErrorDetector`, `PatternDetector`) | Separate LLM calls | #6 |
| Cancel with wire-level interrupt | Best-effort; cancel takes effect after the current `sendAndWait` | #5 |

## Diagnostic fields

Every session-path JSON-RPC response includes `result.usage.copilot`:

| Field | Meaning |
| --- | --- |
| `runtime` | Always `"session"` on this path. Useful if a tool renders output from multiple runtimes. |
| `usageEventCount` | Number of `assistant.usage` events observed inside this turn. >1 means the SDK agent ran multiple internal LLM calls (typically 1 initial + N tool-result continuations). All of them together cost one premium request. |
| `premiumUsedBefore` / `premiumUsedAfter` | Snapshots of `premium_interactions.usedRequests` at the first and last `assistant.usage` events of the turn. Useful for operators wanting raw numbers. |
| `previousTurnPremiumUsed` | The `lastUsage.premiumUsed` stored from the **previous** turn on this session. Baseline for the honest cross-turn delta. |
| `premiumDeltaSinceLastTurn` | **Authoritative per-turn billing signal.** `currentTurn.last.premiumUsed - previousTurnPremiumUsed`. `>0` iff the turn incurred a premium request. `-1` if no baseline yet (first turn of a session). |
| `intraTurnPremiumDelta` | Diagnostic only. Subtraction inside the turn (`last - first`). The SDK does not guarantee `first` is a pre-billing baseline — GitHub's counter updates asynchronously and can land mid-stream, so this alone can report 0 for a billable turn or vice-versa. Use `premiumDeltaSinceLastTurn`. |
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
A→B policy is holding. Each step records the expected
`premiumDeltaSinceLastTurn` delta; the sum across the scenario is the
number of premium requests a real user interaction like this would
have incurred. The legacy `chat` path would have incurred 1 per
LLM call (typically 5×+).

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

| # | Action in TUI | Expected CLI summary | Expected window-A delta | Notes |
| --- | --- | --- | --- | --- |
| 1 | Prompt: `summarize ace-copilot-core/build.gradle.kts and then end by asking whether I want a change` | `copilot: 0 premium this turn (kept inside the in-flight sendAndWait — ...)` or `+1 premium` depending on counter timing | `+1` net (may land at turn 1 or later) | First turn; baseline |
| 2 | At the `answer >` modal, type `add ace-copilot-sdk as an api dependency` | task continues; next summary shows either `0` or `+0` delta | `+0` | Clarification answer → A-path = 0 premium |
| 3 | Wait for task to complete and ask again | agent ends with another ask_user question (steering working) | n/a | Phase 3 c4 — observe whether agent naturally closes with ask_user |
| 4 | Answer: `yes, apply it` | agent applies change then asks again | `+0` | Still A-path |
| 5 | Type `/new run the unit tests` at the clarification modal | current clarification cancels, new task starts | `+1` for the new task | Explicit B-path — `/new` acknowledged as a new billable turn |
| 6 | Let new task complete (no ask_user); back to main prompt | turn summary shows `copilot: +1 premium this turn (new billable sendAndWait ...)` when agent ends cleanly | `+1` | Explicit B — renderCopilotBillingLine tags it |
| 7 | Plain follow-up: `now undo that change` | new task; summary again shows `+1 premium` | `+1` | Plain follow-up after idle = B-path, visible |
| 8 | Put a long task in background: prompt something multi-step, then press any key to auto-`/bg` | task moves to background; main prompt returns | `+1` when main task eventually surfaces completion | Backgrounded task behaviour |
| 9 | While backgrounded task emits an ask_user, TUI interrupts main prompt with `[Clarification] task #X -> ...` notice | drain fires → clarification modal → answer | `+0` | Background-task clarification path (reviewer P1 fix) |

### Success criteria

- Steps 2, 4, 9 all show `+0` premium delta (A-path).
- Steps 1, 5, 6, 7, 8 all show `+1` and the CLI prints the yellow
  `copilot: +N premium this turn` line so the user is aware.
- `usage.copilot.premiumDeltaSinceLastTurn` values sum matches the
  absolute `premiumUsed` counter advance seen in window A.
- At no point does the session wedge: if anything hangs more than 15s
  without activity, inspect the daemon log for pending/unanswered
  user_input entries.

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
