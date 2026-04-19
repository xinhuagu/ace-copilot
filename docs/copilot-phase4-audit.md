# Phase 4 LLM call-site audit (#6)

This is the audit half of issue #6 — an inventory of every place the
daemon issues an LLM request outside the main user-prompt turn, plus
per-site decisions for the Phase 4 "savings-first, Copilot-only" policy.

**Rule of the game:** a simple session-mode prompt must still cost
exactly **1** Copilot premium request. Any subsystem that would add
extra Copilot work has to earn its keep — otherwise it stays skipped.

**Constraint (locked by #6):** no provider switch in this phase.
ace-copilot is Copilot-only. A subsystem cannot be moved to Anthropic
or Ollama just to avoid premium.

## Call-site inventory

Discovered via systematic grep for `LlmClient.sendMessage`,
`LlmClient.streamMessage`, and `CopilotAcpClient.sendAndWait` across
every `ace-copilot-*` module.

| # | Site | Subsystem | Fires when | Current path | Tagged with |
| - | --- | --- | --- | --- | --- |
| 1 | `StreamingAgentLoop.runTurn:313` | main-react | Each ReAct iteration of a chat-path prompt | chat (`LlmClient.streamMessage`) | `MAIN_TURN` / `CONTINUATION` |
| 2 | `AgentLoop.runTurn:121` | main-react | Non-streaming fallback | chat | `MAIN_TURN` |
| 3 | `StreamingAgentHandler.handlePromptViaCopilotSession:2334` | main-react | Session-path prompt dispatch | session (`CopilotAcpClient.sendAndWait`) | `MAIN_TURN` — recorded through `RequestAttribution.builder().record(MAIN_TURN)` and emitted via the shared `writeLlmRequestsBySource` helper, same contract as the chat path |
| 4 | `LLMTaskPlanner.plan:45` | task-planner | `plannerEnabled && complexityScore.shouldPlan()` (chat path only — session dispatch returns before this check) | chat | `PLANNER` |
| 5 | `AdaptiveReplanner.replan:118` | task-planner | Plan step failure triggers replan (chat path only) | chat | `REPLAN` |
| 6 | `SequentialPlanExecutor.java:194 / :254` | task-planner | Plan step execution and fallback paths (chat path only) | chat | `MAIN_TURN` / `FALLBACK` |
| 7 | `MessageCompactor.summarizeMessages:449` | compaction | Context exceeds compaction threshold mid-chat-turn | chat | `COMPACTION_SUMMARY` |
| 8 | `SkillRefinementEngine.proposeRefinement:147` | post-turn-learning | `schedulePostRequestLearning` fires at the end of each chat-path turn | chat (`LlmClient.sendMessage`) | **not tagged** |
| 9 | `DynamicSkillGenerator.proposeDraft:396` | post-turn-learning | `schedulePostRequestLearning` fires (chat path only) | chat | **not tagged** |
| 10 | `SessionSkillPacker.pack:164` | post-turn-learning | `schedulePostRequestLearning` fires (chat path only) | chat | **not tagged** |

## Chat vs session — intentional asymmetry

`handlePrompt`'s dispatch at line 380 routes to
`handlePromptViaCopilotSession` **before** the chat-path subsystems
get a chance to fire. Under the Phase 4 savings-first + Copilot-only
policy this is the intended behaviour for three of the four subsystems
below:

| Subsystem | Chat mode behaviour | Session mode behaviour |
| --- | --- | --- |
| Upfront planner | Fires on complex prompts → +1 premium | **Replaced by in-session prompt steering** (c4 preamble) — agent plans inside the same `sendAndWait` if the task warrants it |
| Fallback / replan | Fires on plan-step failure → +N premium | **Not applicable** — no separate plan object exists; agent recovers inside the turn |
| Compaction summary | Fires mid-turn if context overflows → +1 premium | **Dropped by decision.** SDK manages context internally; no our-side summary |
| Post-turn learning (skill refine / generate / pack) | Fires after every turn → +1-3 premium and background work | **Kept skipped by decision.** Under the Copilot-only savings-first policy, restoring it would re-add premium the project has chosen not to pay. Telemetry (`usage.copilot.subsystemsSkipped`) flags this clearly |

Phase 3's 1-premium-per-session-turn claim holds, and the gaps relative
to chat mode are now documented product decisions rather than silent
skips. No subsystem is "missing" by accident.

## Locked decisions (PR B)

Options under the Copilot-only + savings-first policy: **(a) in-session**,
**(b) separate Copilot session**, **(d) drop / keep skipped**. The
earlier draft of this doc listed a fourth option — **(c) route to a
non-Copilot provider** — which is out of scope once the Copilot-only
constraint is in force, and has been removed.

| Site | Decision | Rationale |
| --- | --- | --- |
| Planner (4, 5, 6) | **(a) in-session** via the c4 prompt-steering preamble — already live. Extend the steering as needed so that for complex prompts the SDK agent explicitly produces a plan before executing, all within the same `sendAndWait`. Zero extra premium. | The SDK agent is capable enough in practice; explicit planning is mostly about nudging. Keeps context in one turn. |
| Compaction (7) | **(d) drop** on session path. The SDK manages its own context; re-summarising from our side is double work. Document the tradeoff: long conversations may see SDK-side context pressure before the chat path would compact — revisit if operators report turn truncation. | SDK has its own sliding-context mechanism. Our summary would burn premium with no clear upside. |
| Post-turn learning (8, 9, 10) | **(d) keep skipped for now.** Restoring learning while staying Copilot-only would add +1–3 premium requests to every session turn (one per learning component that fires). That directly undercuts Phase 3's savings; the capability is not worth that cost at this time. | Learning was historically valuable on chat mode, but was bundled with chat's higher per-turn premium cost. Under a savings-first Copilot-only policy, running it again per turn re-introduces exactly the overhead we removed. Keep off; reconsider if we get a low-cost path (e.g. if the SDK ever lets a sub-task run inside the same sendAndWait). |

All three decisions preserve the 1-premium-per-session-turn target and
leave no silent subsystem skips — every skip below is documented and
intentional, not a regression.

## Instrumentation delta in this PR

- `StreamingAgentHandler.handlePromptViaCopilotSession` now emits
  attribution through the existing chat-path contract — field name
  `usage.llmRequestsBySource`, keys are lowercase `RequestSource`
  names, and only recorded sources appear (no zero-padding). Session
  mode records only `MAIN_TURN`, so a session turn's payload looks
  like:

  ```json
  "usage": {
    "llmRequests": 1,
    "llmRequestsBySource": { "main_turn": 1 },
    "copilot": { "subsystemsSkipped": "planner,compaction,post_turn_learning", ... }
  }
  ```

  Dashboards and the existing CLI parser (which only understands
  `llmRequestsBySource`) see session turns identically to chat turns.
  An earlier draft of this PR used a separate `usage.bySource` field
  with uppercase keys and explicit zeros for every category; that was
  caught in review and swapped for the shared helper. See
  `CopilotSessionBySourceShapeTest` which pins the field name and key
  casing.
- Same path emits `usage.copilot.subsystemsSkipped = "planner,compaction,post_turn_learning"`
  (a session-only signal, deliberately **not** on the shared
  attribution map). With the Phase 4 decisions locked, all three
  entries are intentional and permanent. The marker is kept as a
  regression signal: a future change that tries to wire one of these
  subsystems back onto session mode (and silently rebills the user)
  would need to drop the corresponding entry, which is a visible
  string diff in logs / dashboards.
- No change to chat-path attribution — it already tags every call site
  per `RequestSource` (verified above).

## Out of scope for this phase

- Rewriting the learning algorithms themselves.
- Reintroducing learning on session mode under the current
  Copilot-only policy. If a future phase finds a 0-premium path
  (e.g. an SDK change that lets a sub-task run inside the same
  `sendAndWait`), revisit with an explicit follow-up.
- Deciding the fate of chat mode — this audit and the decisions apply
  to session mode. Chat mode stays as-is (it is the default and the
  fallback).

## Closure

- Decision table above is locked unless reopened.
- Session path already reflects the decisions: planner is handled by
  c4 steering, compaction is dropped, learning is skipped.
- `usage.copilot.subsystemsSkipped` telemetry makes every skip visible
  to anyone grepping the daemon output or driving a dashboard.
- Issue #6 closes on the PR that lands this doc update + records the
  decision table back into the issue body.
