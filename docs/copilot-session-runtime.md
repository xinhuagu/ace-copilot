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

## Intentionally deferred

| Area | Status | Tracked in |
| --- | --- | --- |
| `respondToUserInput` routing (follow-up messages cost 0 premium) | Not implemented — each follow-up is a new billable `sendAndWait` | #5 |
| Structured-form elicitation (MCP etc.) | Auto-declined + yellow warning in TUI | #5 |
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
