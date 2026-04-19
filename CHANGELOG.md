# Changelog

## [0.3.21] - 2026-04-19

### Documentation

- Add billing comparison hero image under tagline (#24)
- Swap GraalVM badge for npm, trim redundant pre-flight list (#26)

### Features

- Cascade GitHub token sources before device-code prompt (#25)
- Default to claude-haiku-4.5, document model selection (#27)
## [0.3.20] - 2026-04-19

### Documentation

- Back the "Copilot trims Claude context" claim with citations
- Link opening thesis to the billing-facts evidence section
## [0.3.19] - 2026-04-19

### Bug Fixes

- Fall through to defaultProfile when ACE_COPILOT_PROFILE is missing
- Session path is 3x published multiplier, not flat 1x — revert default to Haiku

### Documentation

- Replace broken /settings/copilot/usage URL with navigational reference
## [0.3.18] - 2026-04-19

### Documentation

- Add Copilot billing facts section
- Front-load the three operator-verified Copilot billing facts
- Use Copilot Enterprise (1000 req/month) as canonical billing example

### Features

- Default Copilot session model → claude-sonnet-4.6
- Accept any profile name from config.json (#profile-switch)
- Write starter config.json with 3 Copilot profiles on first install
## [0.3.17] - 2026-04-19

### Bug Fixes

- Fall back to /bin/sh on Alpine/BusyBox, harden test assertions
- Move path_instructions under reviews section in CodeRabbit config
- Add public unsubscribe() to prevent subscription leaks
- Drop-oldest queue policy, validate capacity, deterministic tests
- Resolve tech debt — permission modes, read-before-write, slash commands, tests (#4)
- Add missing onSubAgentStart/onSubAgentEnd to StreamEventHandler
- Resolve Java version mismatch and provider profile auto-selection
- Change copilot default model from gpt5.2 to claude-sonnet-4.5
- Fix Copilot runtime model switching and Responses API format
- Resolve sub-agent tech debt TD-1/2/5/6 (#28) (#37)
- Redraw prompt status panel after async output
- Prevent status line wrapping from stealing prompt
- Keep cursor on prompt after async status redraw
- Redisplay prompt after async status render
- Close tool status on early exits and clean panel redraw
- Avoid default profile override when ACECLAW_PROVIDER is set
- Omit unsupported Responses parameters
- Scope store=false to codex provider only
- Avoid null text in user-facing LLM errors
- Use backend-api endpoint paths in factory
- Address review feedback for planned outcomes and maintenance persistence
- Harden null guards and constructor validation per review
- Harden detectors and structured parsing
- Guard null toolName in intermediate-step detector
- Harden candidate store atomicity and anti-pattern gates (#82)
- Close Files.walk stream in validation engine
- Address review hardening in validation gate
- Serialize release state updates with controller lock
- Harden release RPC/config/state handling
- Harden observability panel parsing and null guards
- Require non-null paths in status parsers
- Resolve review feedback on bridge tests and docs link
- Fallback candidates status to ~/.aceclaw memory
- Address review feedback on baseline and anti-pattern metrics
- Harden cron tool input handling and tests
- Null-guard cron status job list
- Address phase2 review findings
- Persist and surface last cron output
- Show full completion output in foreground notifications
- Refresh prompt clock every second while idle
- Address CodeRabbit review — removeOnCancelPolicy, cumulative turns, budget break
- Route permission responses to correct thread via per-request futures (#151)
- Grep exit code 1 no longer triggers false circuit-breaker (#152) (#154)
- Remove status line refresh on every token delta (#156)
- Per-step and total-plan watchdog budget for multi-step plans (#158)
- Update OAuth user-agent to claude-cli/2.1.50 (#160)
- Rename .release-please-config.json to release-please-config.json
- Use java release-type for release-please
- Workaround release-please component undefined bug
- Switch to simple release-type with version.txt
- Add explicit package-name to fix componentNoSpace undefined
- Minimal release-please config with per-package release-type
- Set include-component-in-tag at package level
- Use node release-type with package.json for release-please
- Use v-prefixed initial_tag in cliff.toml to match tag_pattern
- Use PAT for release workflow to bypass branch protection
- Generate skill drafts for existing PROMOTED candidates (#177)
- Short write on non-blocking channel corrupts JSON-RPC stream (#182)
- Ensure tool_result is always added after tool_use in conversation history (#184)
- Strip trailing blank lines from cron/deferred output (#185) (#186)
- Align permission dialog box borders (#190) (#191)
- Strip trailing blanks at printAbove consumer to eliminate empty lines (#194) (#195)
- Grep ISO-8859-1 fallback and unified PathResolver (#204)
- Replace hardcoded timestamp in CandidatePromptAssemblerTest
- Skill draft cache write order, dead code, and case-insensitive release lookup
- Emit skill draft review events from daemon
- Harden skill metrics store
- Make skill memory feedback atomic
- Scope memory dedup by backing file
- Serialize per-skill outcome persistence
- Harden historical log index reads
- Lock historical index appends
- Harden cross-session pattern miner
- Remove invalid lambda underscore
- Harden trend detection window handling
- Enforce scoped trend persistence
- Harden maintenance scheduler concurrency
- Harden maintenance scheduler lifecycle
- Stabilize status panel width handling
- Sanitize status panel fields
- Harden dynamic runtime skill isolation
- Truncate status panel fields by display width, not char count (#220) (#222)
- Track dynamic runtime generation lifecycle
- Preserve runtime state until draft flush succeeds
- Suspend/restore JLine Status widget around foreground tasks (#221) (#223)
- Tighten runtime draft generation semantics
- Stabilize runtime skill prompt assembly
- Harden runtime skill post-processing
- Keep runtime generation independent of memory store
- Restore status panel after /fg command (#224) (#234)
- Refresh git branch in status bar instead of using startup value (#228) (#236)
- Reset Status widget scroll region after Ctrl+L clear screen (#240)
- Always show context usage bar in status when context window is known (#242)
- Tighten recovery retry handling
- Harden runtime governance races and planner hooks
- Address human review surface feedback
- Avoid partial human review writes
- CLI-side short write on non-blocking channel corrupts JSON-RPC stream
- ContextMonitor — single source of truth for context usage (#251)
- ContextMonitor — single source of truth for context usage (#251) (#255)
- ContextMonitor — single source of truth, remove BottomContextBar, fix debug log leak (#258)
- BashExecTool hangs on interactive commands, cancel can't kill process (#262)
- Add reschedule_check tool to eliminate sleep polling (#272)
- Add canaryDwellHours env var + update operations runbook defaults (#297)
- Race condition in RuntimeMetricsExporter + StatisticalTest input validation (#300)
- KeychainCredentialReader tests + null guards + warn logging (#301)
- Synchronize InjectionAuditLog.appendRecord to prevent JSONL corruption (#304)
- Close 3 metrics wiring gaps in StreamingAgentHandler (#307)
- Review findings from PRs 312-315 (#316)
- Address Greptile review findings from PRs 313-315 (#317)
- 401 token refresh silently skipped when refreshToken is null (#325)
- Use actual per-category count from prompts file for significance (#310)
- Move per-category sample_size computation into replay report (#310) (#328)
- Lifecycle metrics report no_data when no transitions exist (#329)
- Copilot ignores unsupported global model and falls back to default
- Banner shows actual model from client, not raw config value
- Change Copilot default model from gpt-5.2-codex to claude-sonnet-4.5
- Agent handler uses resolved model from client, not raw config
- Translate Anthropic model names to Copilot format automatically
- Copilot ignores Anthropic global model, uses own default
- Anthropic uses config model (opus), other providers use client default
- Context1m config now sets 1M context window in capabilities (#335)
- Wrap permission modal descriptions (#342)
- Resolve symlinks in update.sh, tui.sh, restart.sh (#360)
- AutoReleaseControllerTest uses fixed clock to prevent rolling window expiry (#364)
- HealthMonitorTest timing tolerance for slow CI, document Windows daemon skip (#380)
- Remove invalid OAuth scope from token refresh request (#382)
- Retry on Anthropic SSE overloaded/rate-limit errors instead of failing immediately
- Auto-bump to SNAPSHOT after release, fix stale version cache (#383)
- Replan test consumed by stream retry loop (#384)
- Register MCP tools incrementally per server
- Improve retry logic for Anthropic API rate limits (#391)
- Print plan summary and step progress in CLI console (#394)
- Prevent orphan tool_use blocks in conversation history (#397)
- Unblock concurrent API calls in AnthropicClient (#398)
- Always set apiKey from Keychain even when OAuth token is expired
- Read release version from gradle.properties instead of git-cliff
- Write current-state snapshot so TUI reflects live verdict (#408)
- Always call persist() so draft trigger fires on empty insights
- Show download progress bar for the release archive (#416)
- Two session-mode telemetry display bugs (#22)

### Documentation

- Update BashExecTool Javadoc to mention /bin/sh fallback
- Add provider configuration guide with Copilot, Claude, and Ollama setup
- Rewrite README — security-first task agent positioning
- Emphasize Java 21 + enterprise positioning
- Add continuous learning baseline plan and collector
- Close issue-52 baseline metric and replay definition gaps
- Clarify openai-codex request semantics
- Refresh README positioning copy
- Add demo GIF to README
- Replace demo GIF with 2x speed version
- Reposition AceClaw as enterprise agent harness
- Emphasize self-learning agent harness positioning in README
- Align learning docs with current implementation
- Align self-learning docs with implementation (#253) (#269)
- Emphasize long-running self-learning vision
- Align docs with current implementation state
- Sharpen learning manifesto in readme
- Add Plan-Execute-Replan as key differentiator in README
- Clarify that ReAct remains the single-step execution foundation
- Upgrade architecture diagram with Plan/Execute/Replan pipeline
- Restore clean architecture diagram, keep detailed version separate
- Add Plan/Execute/Replan row to original architecture diagram
- Regenerate architecture diagram PNG with white background and Plan/Replan row
- Add CHI 2025 citation link to Plan → Execute → Replan section
- Trim README — remove redundant Architecture, Security Details, Security Roadmap, and Roadmap sections
- Soften competitor comparisons in README — state structural facts without naming names
- Remove Inspired-by line; add competitor links to Plan/Replan section
- Close context engineering documentation-implementation gaps (#280)
- Codify benchmark-driven rollout policy (#290) (#305)
- Align learning_hit_rate definition to Java implementation (#308)
- Document both Anthropic auth modes — API key and OAuth
- Update Copilot model list, name format, and default behavior (#334)
- Define session/workspace/global memory ownership model (#348)
- Windows UDS spike — AF_UNIX works on Windows 10 1803+, no transport abstraction needed (#372)
- Add philosophy article link to README header
- Add design philosophy document and link from README
- Add platform support matrix, clarify Windows as experimental (#377)
- Platform-smoke is now a required check for all PRs (#378)
- Document all CLI commands, provider switching, and daemon management (#385)
- Phase 4 B finalize — lock the decision table (#6) (#19)
- Refocus on Copilot request optimization + locked tradeoffs

### Features

- Add Windows shell support to BashExecTool
- Add type-safe event bus with sealed event hierarchy
- Integrate EventBus, permissions, and auto-memory into agent loop (#5)
- Add SSE/HTTP transport, resource bridge, health checks, and tests (#6)
- Add provider selection to dev.sh (#7)
- Add skill system, dynamic prompt budget, and Ollama fixes
- Add OpenAI Responses API client for Copilot Codex models (#11)
- Add /model command for runtime model switching (#12)
- Add Insight type hierarchy, DetectedPattern model, and ToolMetricsCollector (#13) (#19)
- Add ErrorDetector for error-correction pattern detection (#14) (#20)
- Add PatternDetector for recurring tool sequences and workflows (#15) (#21)
- Add SelfImprovementEngine for post-turn learning (#16) (#22)
- Add StrategyRefiner for insight consolidation (#17) (#23)
- Self-learning gaps P0-P3 (#25) (#26)
- Provider-aware context window auto-detection (#30) (#38)
- Add HealthMonitor + CircuitBreaker foundation (#31) (#40)
- Phase 2 gaps — background sub-agents, transcripts, tool descriptions (#27) (#39)
- Implement command hook system (#32) (#41)
- Implement BOOT.md execution at daemon startup (#33) (#43)
- Implement persistent cron scheduler (#34) (#44)
- Add web tools DDG fallback + dynamic tool guidance in system prompt (#42) (#45)
- Implement HEARTBEAT.md runner on scheduler (#35) (#46)
- Implement task planner with complexity estimation and sequential execution (#36) (#47)
- Implement dual-channel architecture for concurrent task streaming (#48) (#49)
- Add real-time streaming progress status
- Show running task list under prompt status
- Surface pending permission requests for bg/sub-agent tasks
- Popup permission prompt immediately while typing
- Show dynamic runtime status for background tasks
- Wire ToolMetrics into self-learning engine (#53) (#71)
- Add openai-codex oauth provider and auth CLI
- Align openai-codex provider with Codex backend endpoint
- Normalize failure signals into continuous learning (#75)
- Add standalone pyautogui+opencv vision click CLI
- Issue-78 unified candidate pipeline with replay quality gates (#79)
- Close outcome enforcement loop with writeback, clocked gates, and maintenance
- Generate skill drafts from promoted candidates (#84)
- Add autonomous draft validation gate engine
- Add automated skill release controller with guardrails
- Add continuous-learning status panel in CLI
- Close governance protocol and permission interaction tests
- Add compact tool trace logs with single-row status reuse
- Add hard quality gates and baseline-driven metrics
- Add cron tool and scheduling self-awareness
- Make agent max turns configurable in runtime
- Add adaptive continuation segments (phase 2)
- Default adaptive continuation and expose ops metrics
- Stream scheduler completion notices to active cli
- Add defer_check tool with scheduler and CLI notifications (#141)
- Add adaptive replanning on step failure (#121)
- Add WatchdogTimer for turn budget and time budget enforcement (#120)
- Add pre-flight context budget check (#161)
- Doom loop defense — argument-aware dedup and progress detection (#164)
- Budget warning + progress-gated auto-extension (#165)
- Parallel execution — remove turn lock dependency (#166)
- Configurable sub-agent auto-approve tool whitelist (#167) (#168)
- Cross-session preference boosting for self-learning (#169) (#170)
- Auto-trigger skill draft generation on candidate promotion (#174) (#175)
- Support Claude Sonnet 4.5 via Copilot provider (#178) (#179)
- Package successful session into reusable skill draft (#180) (#183)
- Surface generated skill drafts in repl
- Track skill outcomes and metrics
- Feed skill outcomes back into memory
- Auto-refine underperforming skills (#209)
- Add retrospective session analyzer (#210)
- Add historical log index
- Mine cross-session patterns
- Detect historical trends across sessions
- Schedule consolidation maintenance
- Harden maintenance follow-up pipeline (#216)
- Generate runtime skills within active sessions
- Add explainability for learned actions
- Add validation semantics for learned behaviors (#237)
- Add operator learning summary view (#238)
- Reduce maintenance signal noise
- Harden rebuild recovery semantics
- Harden runtime skill lifecycle governance
- Real-time context usage updates during streaming (#244)
- Add human review surface for learned signals
- Auto-promote repeated corrections to ACECLAW.md rules (#259)
- Adopt OpenClaw Anthropic connection strategy (#283)
- Harden auto-release guardrails (#289) (#296)
- Add StatisticalTest utility and RuntimeMetricsExporter (#285) (#298)
- Replay A/B fairness + benchmark pack validation (#287) (#299)
- Add InjectionAuditLog for per-turn learning attribution (#286) (#302)
- Add BenchmarkScorecard for self-learning CI gate (#288) (#303)
- Wire RuntimeMetricsExporter + InjectionAuditLog into production path (#284) (#306)
- Enforce fresh replay artifacts in preMergeCheck (#311) (#312)
- Unify replay taxonomy to canonical benchmark categories (#310) (#313)
- Wire BenchmarkScorecard into CI via Gradle task (#309) (#314)
- Baseline collector auto-reads runtime-latest.json (#308) (#315)
- Derive version from gradle.properties at build time (#318)
- Align coverage rules across script, Gradle, CI, and Java (#310) (#319)
- Complete scorecard metric coverage for all 8 metrics (#320)
- Scorecard as CI verdict + lifecycle formula fixes (partial #309) (#322)
- Auto-read replay, lifecycle, and injection artifacts in baseline collector (#326)
- Unify to two-layer threshold model (#327)
- Proactive OAuth token refresh before expiry (#332)
- Auto-benchmark mode in dev.sh and token estimation tuning (#343)
- Explicit multi-session support for multiple CLI windows on one daemon (#346)
- Workspace-first retrieval priority in AutoMemoryStore (#352)
- One-line install script for cross-platform setup (#358)
- Add aceclaw-update command for easy pull + rebuild (#359)
- Binary distribution via GitHub Releases (#362)
- Make scheduled jobs workspace-scoped instead of daemon-global (#367)
- Windows runtime bring-up — charset detection and platform audit (#374)
- Fix Windows .cmd wrappers — proper path expansion, add restart, fix update (#376)
- MCP server health monitoring with auto-repair (#395)
- Add per-server timeout config for MCP requests (#399)
- Auto-scroll MCP server status bar when >2 servers (#403)
- Surface cumulative LLM request count in /status (#412)
- Soften workspace-level HOLD reasons in /status drafts bracket (#414)
- Foundation for LLM request attribution by execution path (#419)
- Tag PLANNER, REPLAN, COMPACTION_SUMMARY, FALLBACK, CONTINUATION sources (#419)
- Surface per-source breakdown on JSON-RPC + /status (PR B, #419)
- Per-turn breakdown on turn summary + runtime metrics export (PR C, #419)
- Tag runtime export with provider + model + Copilot multiplier
- Phase 1 sessionful runtime skeleton (#3)
- Phase 2 tools + permissions bridge (#4)
- Harden session runtime edges before Phase 3 (#12)
- Phase 3 c1+c2 — user_input.request plumbing + daemon pending state (#5)
- C3a — UserInputBridge + TaskStreamReader routing (#5)
- C3b — TUI clarification flow + /new escape (#5)
- C5+c6 — clarification timeouts, user_input smoke test, doc (#5)
- C4 steering + premium-turn UX + acceptance doc (#14)
- Session-total premium line alongside per-turn line (#20) (#21)
## [0.3.16] - 2026-04-17

### Features

- Foundation for LLM request attribution by execution path (#419)
- Tag PLANNER, REPLAN, COMPACTION_SUMMARY, FALLBACK, CONTINUATION sources (#419)
- Surface per-source breakdown on JSON-RPC + /status (PR B, #419)
- Per-turn breakdown on turn summary + runtime metrics export (PR C, #419)
- Tag runtime export with provider + model + Copilot multiplier
## [0.3.15] - 2026-04-17

### Bug Fixes

- Show download progress bar for the release archive (#416)

### Features

- Surface cumulative LLM request count in /status (#412)
- Soften workspace-level HOLD reasons in /status drafts bracket (#414)
## [0.3.14] - 2026-04-17

### Bug Fixes

- Write current-state snapshot so TUI reflects live verdict (#408)
- Always call persist() so draft trigger fires on empty insights
## [0.3.13] - 2026-04-12

### Bug Fixes

- Read release version from gradle.properties instead of git-cliff
## [0.3.12] - 2026-04-11

### Bug Fixes

- Always set apiKey from Keychain even when OAuth token is expired
## [0.3.11] - 2026-04-09

### Features

- Auto-scroll MCP server status bar when >2 servers (#403)
## [0.3.10] - 2026-04-06

### Features

- Add per-server timeout config for MCP requests (#399)
## [0.3.9] - 2026-04-05

### Bug Fixes

- Unblock concurrent API calls in AnthropicClient (#398)
## [0.3.8] - 2026-04-05

### Bug Fixes

- Prevent orphan tool_use blocks in conversation history (#397)
## [0.3.7] - 2026-04-05

### Features

- MCP server health monitoring with auto-repair (#395)
## [0.3.6] - 2026-04-04

### Bug Fixes

- Improve retry logic for Anthropic API rate limits (#391)
- Print plan summary and step progress in CLI console (#394)
## [0.3.5] - 2026-03-31

### Bug Fixes

- Register MCP tools incrementally per server
## [0.3.4] - 2026-03-31

### Documentation

- Document all CLI commands, provider switching, and daemon management (#385)
## [0.3.3] - 2026-03-31

### Bug Fixes

- Replan test consumed by stream retry loop (#384)
## [0.3.2] - 2026-03-31

### Bug Fixes

- Retry on Anthropic SSE overloaded/rate-limit errors instead of failing immediately
- Auto-bump to SNAPSHOT after release, fix stale version cache (#383)
## [0.3.1] - 2026-03-31

### Bug Fixes

- Remove invalid OAuth scope from token refresh request (#382)
## [0.3.0] - 2026-03-29

### Bug Fixes

- HealthMonitorTest timing tolerance for slow CI, document Windows daemon skip (#380)

### Documentation

- Windows UDS spike — AF_UNIX works on Windows 10 1803+, no transport abstraction needed (#372)
- Add philosophy article link to README header
- Add design philosophy document and link from README
- Add platform support matrix, clarify Windows as experimental (#377)
- Platform-smoke is now a required check for all PRs (#378)

### Features

- Make scheduled jobs workspace-scoped instead of daemon-global (#367)
- Windows runtime bring-up — charset detection and platform audit (#374)
- Fix Windows .cmd wrappers — proper path expansion, add restart, fix update (#376)
## [0.2.2] - 2026-03-28

### Bug Fixes

- AutoReleaseControllerTest uses fixed clock to prevent rolling window expiry (#364)
## [0.2.1] - 2026-03-28

### Bug Fixes

- Add canaryDwellHours env var + update operations runbook defaults (#297)
- Race condition in RuntimeMetricsExporter + StatisticalTest input validation (#300)
- KeychainCredentialReader tests + null guards + warn logging (#301)
- Synchronize InjectionAuditLog.appendRecord to prevent JSONL corruption (#304)
- Close 3 metrics wiring gaps in StreamingAgentHandler (#307)
- Review findings from PRs 312-315 (#316)
- Address Greptile review findings from PRs 313-315 (#317)
- 401 token refresh silently skipped when refreshToken is null (#325)
- Use actual per-category count from prompts file for significance (#310)
- Move per-category sample_size computation into replay report (#310) (#328)
- Lifecycle metrics report no_data when no transitions exist (#329)
- Copilot ignores unsupported global model and falls back to default
- Banner shows actual model from client, not raw config value
- Change Copilot default model from gpt-5.2-codex to claude-sonnet-4.5
- Agent handler uses resolved model from client, not raw config
- Translate Anthropic model names to Copilot format automatically
- Copilot ignores Anthropic global model, uses own default
- Anthropic uses config model (opus), other providers use client default
- Context1m config now sets 1M context window in capabilities (#335)
- Wrap permission modal descriptions (#342)
- Resolve symlinks in update.sh, tui.sh, restart.sh (#360)

### Documentation

- Add Plan-Execute-Replan as key differentiator in README
- Clarify that ReAct remains the single-step execution foundation
- Upgrade architecture diagram with Plan/Execute/Replan pipeline
- Restore clean architecture diagram, keep detailed version separate
- Add Plan/Execute/Replan row to original architecture diagram
- Regenerate architecture diagram PNG with white background and Plan/Replan row
- Add CHI 2025 citation link to Plan → Execute → Replan section
- Trim README — remove redundant Architecture, Security Details, Security Roadmap, and Roadmap sections
- Soften competitor comparisons in README — state structural facts without naming names
- Remove Inspired-by line; add competitor links to Plan/Replan section
- Close context engineering documentation-implementation gaps (#280)
- Codify benchmark-driven rollout policy (#290) (#305)
- Align learning_hit_rate definition to Java implementation (#308)
- Document both Anthropic auth modes — API key and OAuth
- Update Copilot model list, name format, and default behavior (#334)
- Define session/workspace/global memory ownership model (#348)

### Features

- Adopt OpenClaw Anthropic connection strategy (#283)
- Harden auto-release guardrails (#289) (#296)
- Add StatisticalTest utility and RuntimeMetricsExporter (#285) (#298)
- Replay A/B fairness + benchmark pack validation (#287) (#299)
- Add InjectionAuditLog for per-turn learning attribution (#286) (#302)
- Add BenchmarkScorecard for self-learning CI gate (#288) (#303)
- Wire RuntimeMetricsExporter + InjectionAuditLog into production path (#284) (#306)
- Enforce fresh replay artifacts in preMergeCheck (#311) (#312)
- Unify replay taxonomy to canonical benchmark categories (#310) (#313)
- Wire BenchmarkScorecard into CI via Gradle task (#309) (#314)
- Baseline collector auto-reads runtime-latest.json (#308) (#315)
- Derive version from gradle.properties at build time (#318)
- Align coverage rules across script, Gradle, CI, and Java (#310) (#319)
- Complete scorecard metric coverage for all 8 metrics (#320)
- Scorecard as CI verdict + lifecycle formula fixes (partial #309) (#322)
- Auto-read replay, lifecycle, and injection artifacts in baseline collector (#326)
- Unify to two-layer threshold model (#327)
- Proactive OAuth token refresh before expiry (#332)
- Auto-benchmark mode in dev.sh and token estimation tuning (#343)
- Explicit multi-session support for multiple CLI windows on one daemon (#346)
- Workspace-first retrieval priority in AutoMemoryStore (#352)
- One-line install script for cross-platform setup (#358)
- Add ace-copilot-update command for easy pull + rebuild (#359)
- Binary distribution via GitHub Releases (#362)
## [0.1.0] - 2026-03-05

### Bug Fixes

- Fall back to /bin/sh on Alpine/BusyBox, harden test assertions
- Move path_instructions under reviews section in CodeRabbit config
- Add public unsubscribe() to prevent subscription leaks
- Drop-oldest queue policy, validate capacity, deterministic tests
- Resolve tech debt — permission modes, read-before-write, slash commands, tests (#4)
- Add missing onSubAgentStart/onSubAgentEnd to StreamEventHandler
- Resolve Java version mismatch and provider profile auto-selection
- Change copilot default model from gpt5.2 to claude-sonnet-4.5
- Fix Copilot runtime model switching and Responses API format
- Resolve sub-agent tech debt TD-1/2/5/6 (#28) (#37)
- Redraw prompt status panel after async output
- Prevent status line wrapping from stealing prompt
- Keep cursor on prompt after async status redraw
- Redisplay prompt after async status render
- Close tool status on early exits and clean panel redraw
- Avoid default profile override when ACE_COPILOT_PROVIDER is set
- Omit unsupported Responses parameters
- Scope store=false to codex provider only
- Avoid null text in user-facing LLM errors
- Use backend-api endpoint paths in factory
- Address review feedback for planned outcomes and maintenance persistence
- Harden null guards and constructor validation per review
- Harden detectors and structured parsing
- Guard null toolName in intermediate-step detector
- Harden candidate store atomicity and anti-pattern gates (#82)
- Close Files.walk stream in validation engine
- Address review hardening in validation gate
- Serialize release state updates with controller lock
- Harden release RPC/config/state handling
- Harden observability panel parsing and null guards
- Require non-null paths in status parsers
- Resolve review feedback on bridge tests and docs link
- Fallback candidates status to ~/.ace-copilot memory
- Address review feedback on baseline and anti-pattern metrics
- Harden cron tool input handling and tests
- Null-guard cron status job list
- Address phase2 review findings
- Persist and surface last cron output
- Show full completion output in foreground notifications
- Refresh prompt clock every second while idle
- Address CodeRabbit review — removeOnCancelPolicy, cumulative turns, budget break
- Route permission responses to correct thread via per-request futures (#151)
- Grep exit code 1 no longer triggers false circuit-breaker (#152) (#154)
- Remove status line refresh on every token delta (#156)
- Per-step and total-plan watchdog budget for multi-step plans (#158)
- Update OAuth user-agent to claude-cli/2.1.50 (#160)
- Rename .release-please-config.json to release-please-config.json
- Use java release-type for release-please
- Workaround release-please component undefined bug
- Switch to simple release-type with version.txt
- Add explicit package-name to fix componentNoSpace undefined
- Minimal release-please config with per-package release-type
- Set include-component-in-tag at package level
- Use node release-type with package.json for release-please
- Use v-prefixed initial_tag in cliff.toml to match tag_pattern
- Use PAT for release workflow to bypass branch protection

### Documentation

- Update BashExecTool Javadoc to mention /bin/sh fallback
- Add provider configuration guide with Copilot, Claude, and Ollama setup
- Rewrite README — security-first task agent positioning
- Emphasize Java 21 + enterprise positioning
- Add continuous learning baseline plan and collector
- Close issue-52 baseline metric and replay definition gaps
- Clarify openai-codex request semantics
- Refresh README positioning copy
- Add demo GIF to README
- Replace demo GIF with 2x speed version
- Reposition ace-copilot as enterprise agent harness

### Features

- Add Windows shell support to BashExecTool
- Add type-safe event bus with sealed event hierarchy
- Integrate EventBus, permissions, and auto-memory into agent loop (#5)
- Add SSE/HTTP transport, resource bridge, health checks, and tests (#6)
- Add provider selection to dev.sh (#7)
- Add skill system, dynamic prompt budget, and Ollama fixes
- Add OpenAI Responses API client for Copilot Codex models (#11)
- Add /model command for runtime model switching (#12)
- Add Insight type hierarchy, DetectedPattern model, and ToolMetricsCollector (#13) (#19)
- Add ErrorDetector for error-correction pattern detection (#14) (#20)
- Add PatternDetector for recurring tool sequences and workflows (#15) (#21)
- Add SelfImprovementEngine for post-turn learning (#16) (#22)
- Add StrategyRefiner for insight consolidation (#17) (#23)
- Self-learning gaps P0-P3 (#25) (#26)
- Provider-aware context window auto-detection (#30) (#38)
- Add HealthMonitor + CircuitBreaker foundation (#31) (#40)
- Phase 2 gaps — background sub-agents, transcripts, tool descriptions (#27) (#39)
- Implement command hook system (#32) (#41)
- Implement BOOT.md execution at daemon startup (#33) (#43)
- Implement persistent cron scheduler (#34) (#44)
- Add web tools DDG fallback + dynamic tool guidance in system prompt (#42) (#45)
- Implement HEARTBEAT.md runner on scheduler (#35) (#46)
- Implement task planner with complexity estimation and sequential execution (#36) (#47)
- Implement dual-channel architecture for concurrent task streaming (#48) (#49)
- Add real-time streaming progress status
- Show running task list under prompt status
- Surface pending permission requests for bg/sub-agent tasks
- Popup permission prompt immediately while typing
- Show dynamic runtime status for background tasks
- Wire ToolMetrics into self-learning engine (#53) (#71)
- Add openai-codex oauth provider and auth CLI
- Align openai-codex provider with Codex backend endpoint
- Normalize failure signals into continuous learning (#75)
- Add standalone pyautogui+opencv vision click CLI
- Issue-78 unified candidate pipeline with replay quality gates (#79)
- Close outcome enforcement loop with writeback, clocked gates, and maintenance
- Generate skill drafts from promoted candidates (#84)
- Add autonomous draft validation gate engine
- Add automated skill release controller with guardrails
- Add continuous-learning status panel in CLI
- Close governance protocol and permission interaction tests
- Add compact tool trace logs with single-row status reuse
- Add hard quality gates and baseline-driven metrics
- Add cron tool and scheduling self-awareness
- Make agent max turns configurable in runtime
- Add adaptive continuation segments (phase 2)
- Default adaptive continuation and expose ops metrics
- Stream scheduler completion notices to active cli
- Add defer_check tool with scheduler and CLI notifications (#141)
- Add adaptive replanning on step failure (#121)
- Add WatchdogTimer for turn budget and time budget enforcement (#120)
- Add pre-flight context budget check (#161)
- Doom loop defense — argument-aware dedup and progress detection (#164)
- Budget warning + progress-gated auto-extension (#165)
- Parallel execution — remove turn lock dependency (#166)
- Configurable sub-agent auto-approve tool whitelist (#167) (#168)
- Cross-session preference boosting for self-learning (#169) (#170)
# Changelog
