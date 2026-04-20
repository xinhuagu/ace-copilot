<h1 align="center">ace-copilot</h1>

<p align="center">A GitHub Copilot–focused agent harness. One premium request per user turn — on purpose.</p>

<p align="center">
  <a href="https://github.com/xinhuagu/ace-copilot/actions/workflows/ci.yml"><img src="https://github.com/xinhuagu/ace-copilot/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/Node.js-20%2B-339933?logo=node.js&logoColor=white" alt="Node.js 20+">
  <img src="https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white" alt="Gradle 8.14">
</p>

> **ace-copilot exists to make GitHub Copilot usable as an agent backend from the terminal, with pricing you can see and tradeoffs you can audit.**
>
> Copilot's premium-request billing is among the most **opaque and punitively priced** options in the current AI-subscription market for agent workloads — an integer counter with no per-turn receipt, an **undocumented 3× multiplier** on the session endpoint, a silently trimmed context window, and overage priced several times higher than the same model costs direct from the provider ([evidence](docs/copilot-session-runtime.md#why-this-project-exists--copilot-billing-facts)). ace-copilot uses Copilot's **own** session SDK — the same underlying mechanism as VSCode Copilot Chat's agent mode — to deliver one-premium-per-user-prompt billing in a CLI/daemon, with telemetry that surfaces the 3× multiplier GitHub doesn't show you and [explicit Phase 4 tradeoffs](docs/copilot-phase4-audit.md) for the subsystems that would otherwise silently re-bill you.

<p align="center">
  <img src="docs/img/ace-copilot-hero.png" alt="GitHub Copilot meters interactions (premium request meter clicking up with every step); ace-copilot meters execution (one turn, one request, full execution)" width="700">
</p>

## The problem, concretely

Copilot's billing model is structurally hostile to agent workloads. The facts below are operator-verified, not guessed from marketing pages.

- **Premium requests are capped monthly; overage is pay-as-you-go at a premium price.** Unlike APIs (Anthropic, OpenAI direct, Ollama-local) where you pay per token at a rate you can reason about up front, Copilot gives you a fixed monthly pool of premium requests; when you run out you either wait for the next billing cycle, upgrade, or [buy overage at **$0.04 per premium request**](https://github.blog/changelog/2025-08-22-premium-request-overage-policy-is-generally-available-for-copilot-business-and-enterprise/) (GA'd Aug 2025 for Business / Enterprise). Combined with the session-path **3× multiplier** (below), overage lands at **$0.12 per Sonnet session turn** and **$0.04 per Haiku session turn** — higher for frontier models with 5×/20× base multipliers. On [**Copilot Enterprise**](https://docs.github.com/en/copilot/get-started/plans) (1,000 premium requests/month per seat), **every single request is 0.1% of your entire monthly budget**. A single complex task on the stock chat path can eat 0.5–1% of your month.
- **The session endpoint applies a flat 3× multiplier on top of the published model multipliers.** Operator-verified by repeated measurement against your GitHub Copilot usage dashboard (Settings → Billing & Plans → Copilot premium requests) across many turns. Model choice is **not** neutral on session mode — it is *more* expensive than chat mode for every model except Haiku.

  | Model | Published multiplier (chat path) | Observed per-turn on session path | Session-vs-published ratio |
  | --- | --- | --- | --- |
  | Claude Haiku 4.5 | 0.33× | **1×** | 3× |
  | Claude Sonnet 4.5 | 1× | **3×** | 3× |
  | Claude Sonnet 4.6 | 1× | **3×** | 3× |
  | GPT-5.4 | 1× | **3×** | 3× |

  On Enterprise (1,000 req/month), one Sonnet session turn = **0.3%** of your month, one Haiku session turn = **0.1%**. Haiku is still the cheapest model on session mode — three Haiku turns fit in the premium budget of one Sonnet turn. Pick Sonnet/Opus/GPT when capability justifies 3× the cost, not by default.
- **Copilot caps Claude context windows below the model's native capacity.** Anthropic ships Claude Sonnet 4.6 at **200K tokens** native, **500K for Enterprise**, and **1M via the `context-1m-2025-08-07` beta header** ([Anthropic Sonnet 4.5 announcement](https://www.anthropic.com/news/claude-sonnet-4-5), [Claude API context-windows docs](https://platform.claude.com/docs/en/build-with-claude/context-windows)). Through GitHub Copilot: the Copilot Chat Anthropic provider **does not pass the 1M beta header**, so Sonnet 4.6 / Opus 4.6 are capped at 200K even when the account would otherwise qualify ([microsoft/vscode#298901](https://github.com/microsoft/vscode/issues/298901)). Worse, Copilot's reported `max_prompt_tokens` for Sonnet 4.6 is below 200K — compaction fires **~40K tokens earlier** than the advertised limit ([microsoft/vscode#298900](https://github.com/microsoft/vscode/issues/298900)). Third-party operators observe the effective usable window **closer to 128K** in practice ([anomalyco/opencode#16129](https://github.com/anomalyco/opencode/issues/16129)) and ~40% of the window silently reserved for output ([github/community#188691](https://github.com/orgs/community/discussions/188691), [github/community#186340](https://github.com/orgs/community/discussions/186340)). You pay for Sonnet 4.6, you get a version materially more trimmed than what Anthropic sells directly.
- **Per-iteration billing is the `/chat/completions` reality, and ace-copilot's chat path pays it.** Every call to `/chat/completions` counts as one premium request ([Requests in GitHub Copilot](https://docs.github.com/en/copilot/concepts/billing/copilot-requests)). ace-copilot's chat path loops through that endpoint once per ReAct iteration — so a task with 4 tool calls costs ~4 premium requests. For fairness: GitHub's **own** agent modes (VSCode Copilot Chat agent mode, Copilot Coding Agent, the `@github/copilot-sdk` `sendAndWait` endpoint) already batch tool iterations into one billable prompt ([community #165798: Coding Agent = 1 premium/session](https://github.com/orgs/community/discussions/165798)). ace-copilot's contribution isn't inventing per-prompt billing for Copilot — it's **delivering that economics in a CLI/daemon with honest telemetry and explicit tradeoffs, instead of locking it inside the VSCode UI**.
- **The counter is eventually consistent and the surface is closed.** GitHub's own docs acknowledge "there may be a delay in the displayed usage percentage" ([Requests in GitHub Copilot](https://docs.github.com/en/copilot/concepts/billing/copilot-requests)), and users routinely observe counters stuck for hours before catching up ([community #166810](https://github.com/orgs/community/discussions/166810)). A billable turn's `+1` can land in a later turn's observation window — no per-turn receipt, no granular controls, no way to opt subsystems out of billing. You pay what the counter says, and you find out after the fact.

This is why ace-copilot exists. Not as a polite integration — as mechanism engineering against a billing model designed to resist exactly the kind of use agents require.

## How ace-copilot answers it

Copilot ships an SDK (`@github/copilot-sdk`) with a sessionful runtime: one `sendAndWait` call can host an entire ReAct loop — multiple LLM iterations, tool calls, clarification round-trips — and still bill as **one** premium request. ace-copilot builds on that: a Node sidecar speaks the SDK protocol, a Java daemon orchestrates the agent, and every user prompt maps to exactly one `sendAndWait`.

| Scenario | Stock Copilot chat | ace-copilot session runtime |
| --- | --- | --- |
| Simple 1-turn answer | 1 | **1** |
| ReAct task with 5 tool iterations | ~5 | **1** |
| Task + 3 clarifying follow-ups | ~8 | **1** |

Every decision in the harness is measured against one rule: a plain user prompt must cost exactly one premium request. Any subsystem that would add extra Copilot work has to earn its keep — most of them don't, and the ones that don't stay off.

## The tradeoffs we made

Session-mode savings don't come free. The policy below is locked and documented in [docs/copilot-session-runtime.md](docs/copilot-session-runtime.md) / [docs/copilot-phase4-audit.md](docs/copilot-phase4-audit.md). Each row is an explicit product decision, not a hidden gap.

| Subsystem | What session mode does | What we give up |
| --- | --- | --- |
| **Main ReAct loop** | Runs inside one `sendAndWait`. All tool calls and internal LLM iterations are folded in. | Per-iteration streaming granularity is looser than chat: you see tool activity and text deltas, but not a fresh "assistant thought" block per iteration. |
| **Task planner / replan / plan-step executor** | Kept **in-session** via prompt steering. The SDK agent plans inside the same `sendAndWait` when the task warrants it. Zero extra premium. | No explicit structured plan object, no per-step iteration budgets, no inline replan as a distinct Copilot turn. If the agent doesn't plan well from the preamble, we can't rescue it with a second billable planner call. |
| **Context compaction summary** | **Dropped.** The SDK manages its own context on the session path. | No control over which messages get trimmed. Long conversations can hit SDK-side context pressure before our summary would have fired. We accept SDK truncation behavior rather than burn a premium request re-summarising ourselves. |
| **Post-turn learning** (skill refinement, pattern detection, auto-memory updates) | **Kept skipped on session mode.** Running it would add +1–3 premium per turn. | No skill refinement, no heuristic-driven memory writes, no session-end retrospective on session mode. Learning still runs on the chat path — but if you're here for savings you're not on chat. |
| **Clarification round-trips** | Agent asks questions via `user_input.requested` inside the same `sendAndWait`. Answers cost **0** extra premium. | No externally-resolved clarification — answers must flow back through the live session. The `/new` command cancels a stuck clarification. |
| **Non-Copilot provider for learning** | **Rejected.** Routing learning to Anthropic/Ollama was considered and explicitly declined — it would have kept learning alive but violates the Copilot-only constraint and complicates attribution. | Learning stays off on session mode. Revisit only if the SDK ever exposes a 0-premium sub-task primitive. |
| **Cross-reattach session total** | Session-cumulative premium counter is scoped to the current TUI attachment. | Reattaching a session resets the visible total. True cross-reattach baseline would need daemon-side session state — deferred until someone asks. |

Every skip is visible in telemetry: every session-mode turn emits `usage.copilot.subsystemsSkipped = "planner,compaction,post_turn_learning"`. A future change that silently rewires any of these back onto session mode would have to drop the corresponding entry, which shows up as a visible diff in logs and dashboards.

## Honest billing UX

Two lines appear in the TUI after every session-mode turn:

```
25.3s  1 LLM req (main=1)  41475 in / 554 out  context 40K/128K
  copilot: session counter +1 since last turn   premiumUsed 223→224   (…)
  copilot: total +1 since this TUI attached     premiumUsed 223→224
```

- **Per-turn line** reports the counter advance observed during this turn's window. Labeled as observable, not causal — Copilot's counter is eventually consistent, so a billable turn's +1 can surface in a later turn's window.
- **Session-total line** reports cumulative advance since the current TUI attached. Summed across many turns it converges to the real consumption and is more reliable than any single-turn delta.

No "+1 premium this turn" claims where the counter can't prove it. No silent subsystem usage hidden from the UI. If the number is wrong, the wording should not make it worse.

## Quick Start

### One-Line Install

```bash
curl -fsSL https://raw.githubusercontent.com/xinhuagu/ace-copilot/main/install.sh | sh
```

Downloads the latest pre-built release, extracts to `~/.ace-copilot/`, and adds commands to your PATH. Requires Java 21 runtime. Node.js 20+ is required for the Copilot session runtime (the thing this project exists for); if Node is missing the daemon falls back to the chat path with a loud ERROR log.

### Run against Copilot

```bash
ace-copilot-restart copilot    # Start daemon in Copilot session mode
ace-copilot                    # Attach TUI
```

#### First-time login

**Easiest path:** run `gh auth login` once, then start ace-copilot. No prompt.

**Otherwise** (fresh machine, no `gh` CLI), ace-copilot prints a device code + URL:

```
No GitHub Copilot credentials found.
Requires an active GitHub Copilot subscription (Individual / Business / Enterprise).
Token will be cached at ~/.ace-copilot/copilot-oauth-token.

  GitHub Copilot Authentication
  ─────────────────────────────
  1. Open: https://github.com/login/device
  2. Enter code: XXXX-XXXX
  Waiting for authorization...
```

Open the URL, paste the code, authorize. The token is cached and reused.

> **Subscription required.** Free GitHub accounts cannot use the Copilot API.
>
> **About env vars and config tokens.** ace-copilot will happily *use* a token from `apiKey` (in `~/.ace-copilot/config.json`), `GITHUB_TOKEN`, or `GH_TOKEN` once it's running — but these **do not** count as "logged in" on first start. A fresh install always shows the device-code prompt unless a cached token or `gh auth login` is present. When more than one source is set, the cached token and `gh auth token` win at runtime too, so the account that passed the first-time prompt is the same account billed for Copilot quota. Config / env tokens only kick in when no cached or `gh` token exists. This is on purpose: you should see and confirm exactly which GitHub account ace-copilot is binding to before it starts spending your Copilot quota.

#### Choose a model

Default is **`claude-haiku-4.5`** — the only Copilot model with a 1× session-mode multiplier (every other model is 3×). For most agent work this is the cheapest competent choice; switch up only when capability justifies the cost. See the multiplier matrix in [The problem, concretely](#the-problem-concretely) above.

| When you want to choose | How | Example |
|---|---|---|
| **At startup** (one-shot for this run) | `ACE_COPILOT_MODEL` env var on the launch command | `ACE_COPILOT_MODEL=claude-sonnet-4.5 ace-copilot` |
| **Mid-session** (try a model for the next turn) | `/model <name>` at the prompt | `/model claude-opus-4.6` |
| **Permanently** (every future launch) | `model` field in the copilot profile of `~/.ace-copilot/config.json` | `"model": "claude-sonnet-4.6"` |

Common Copilot model names: `claude-haiku-4.5`, `claude-sonnet-4.5`, `claude-sonnet-4.6`, `claude-opus-4.6`, `gpt-4o`, `gpt-5.2-codex`, `o4-mini`. Full list and per-model notes in [docs/provider-configuration.md](docs/provider-configuration.md#available-models).

#### Per-turn output

First turn prints `copilot: first turn of session (no baseline yet)`. Every subsequent turn shows the per-turn and session-total lines above.

### Commands

| Command | What it does |
|---------|-------------|
| `ace-copilot` | Start TUI (auto-starts daemon if not running) |
| `ace-copilot-tui [provider]` | Open another TUI window — never restarts the daemon, safe for multi-session |
| `ace-copilot-restart [provider]` | Stop daemon + restart with fresh build (warns if sessions active) |
| `ace-copilot-update` | Update to latest release (refuses if sessions active) |

#### Daemon Management

```bash
ace-copilot daemon start              # Start daemon in background
ace-copilot daemon start -p copilot   # Start background daemon with Copilot session mode
ace-copilot daemon start --foreground # Start daemon in foreground (for debugging)
ace-copilot daemon stop               # Gracefully stop daemon
ace-copilot daemon status             # Show health, version, model, active sessions
```

### Build from Source

```bash
git clone https://github.com/xinhuagu/ace-copilot.git && cd ace-copilot
./gradlew clean build && ./gradlew :ace-copilot-cli:installDist
./ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli
```

Development scripts (from git checkout):

| Script | What it does |
|--------|-------------|
| `./dev.sh [provider]` | Rebuild + restart daemon + auto-benchmark on feature branches |
| `./restart.sh [provider]` | Rebuild + restart daemon (no benchmarks, fastest restart) |
| `./tui.sh [provider]` | Open TUI window (no restart, no rebuild if binary exists) |

See [Multi-Session Model](docs/multi-session.md) for details on running multiple TUI windows against one daemon.

## Platform Support

| Platform | Status | IPC | CI Gate |
|----------|--------|-----|---------|
| **Linux** | Fully supported | AF_UNIX | `pre-merge-check` — full test suite (required) |
| **macOS** | Fully supported | AF_UNIX | `platform-smoke` — build + cross-platform tests (required) |
| **Windows 10 1803+** | Experimental | AF_UNIX (JEP 380) | `platform-smoke` — build + cross-platform tests (required) |

All three platform checks are required for merging to main.

## Tech Stack

Java 21 (preview features) · Gradle 8.14 · Node 20+ (Copilot session runtime) · Picocli 4.7.6 · JLine3 3.27.1 · Jackson 2.18.2 · JUnit 5

## License

[Apache License 2.0](LICENSE)
