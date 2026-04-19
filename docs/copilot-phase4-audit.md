# Phase 4 LLM call-site audit (#6, PR A)

This is the first half of issue #6 — an inventory of every place the
daemon issues an LLM request outside the main user-prompt turn, plus
a per-site proposal for Phase 4 PR B to act on.

**Rule of the game:** a simple user prompt should cost exactly **1**
premium request end-to-end. Anything extra is either (a) accepted with
open eyes, (b) moved off the Copilot premium bill, or (c) removed.

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

## Key finding — the chat vs session asymmetry

`handlePrompt`'s dispatch at line 380 routes to
`handlePromptViaCopilotSession` **before** any of the
chat-path-only subsystems have a chance to fire. The concrete
consequences:

| Subsystem | Chat mode behaviour | Session mode behaviour |
| --- | --- | --- |
| Upfront planner | Fires on complex prompts → +1 premium | **Silently skipped.** SDK agent plans internally if it decides to |
| Fallback / replan | Fires on plan-step failure → +N premium | **Silently skipped.** No plan exists |
| Compaction summary | Fires mid-turn if context overflows → +1 premium | **Silently skipped.** SDK manages context itself |
| Post-turn learning (skill refine / generate / pack) | Fires after every turn → +1-3 premium and background work | **Conditional.** PR B1 (#6) lets operators set `learningProvider` to a non-Copilot provider; when set, session-mode turns schedule learning on that client and incur 0 Copilot premium. When unset (default), learning stays off on session path and the daemon logs a WARN at startup. |

Phase 3's 1-premium-per-turn claim is therefore accurate for session
mode, but the user also silently loses four capabilities that chat
mode exercises. The project's real target ("one simple prompt pays
the premium it should, and the learning pipeline still runs") is not
yet met end-to-end; it just pays less than chat did.

## Per-site proposal for PR B

Options from #6 scope: **(a) in-session**, **(b) separate SDK session**,
**(c) non-Copilot provider**, **(d) drop**. Proposed here; decisions
locked in PR B after discussion.

| Site | Proposal | Rationale |
| --- | --- | --- |
| Planner (4, 5, 6) | **(a) in-session** via the c4 prompt-steering preamble — already partly done. Extend steering so that for complex prompts the SDK agent explicitly produces a plan before executing. Zero extra premium. | The SDK agent is capable enough in practice; explicit planning is mostly about nudging. Keeps context in one turn |
| Compaction (7) | **(d) drop** on session path; SDK handles context internally. Document the tradeoff: long sessions may see SDK-side context pressure before the chat path would compact. Revisit if operators report turn truncation | SDK has its own sliding-context mechanism; re-summarising from our side is double work |
| Post-turn learning (8, 9, 10) | **(c) non-Copilot provider** — run against Anthropic / Ollama (existing `LlmClient` factories still work for those). Learning is post-turn and not latency-sensitive, so a different model is acceptable. Zero Copilot premium spend on learning | Preserves the learning pipeline, costs 0 Copilot premium. Per-step prompt quality is already tolerant to model differences |

All three proposals preserve the 1-premium-per-user-prompt target.

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
  (a session-only regression signal, deliberately **not** on the
  shared attribution map) so grep / dashboards can flag any future
  change that quietly wires a billable subsystem back in.
- No change to chat-path attribution — it already tags every call site
  per `RequestSource` (verified above).

## Out of scope for PR A

- Actually implementing any of the (a)/(c)/(d) decisions.
- Rewriting the learning algorithms.
- Deciding the fate of the chat path — this audit and the decisions
  apply to session mode. Chat mode stays as-is (it's the default and
  the fallback).

## Closes nothing yet

Issue #6 stays open. PR B, informed by this audit, either changes the
code per the decision table above or closes it with a documented
"keep as-is" rationale. No production change until PR B.
