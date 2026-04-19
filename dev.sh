#!/bin/sh
# Quick rebuild + restart: build, stop old daemon, launch CLI
# Usage: ./dev.sh [--check | --baseline | --auto | --no-bench] [provider]
#   (no flag)  : auto-select benchmark mode on feature branches, skip on main
#   --check    : run local benchmark check before launching
#   --baseline : run benchmark check + baseline export before launching
#   --auto     : auto-select --check or --baseline based on changed files
#   --no-bench : skip benchmarks even on feature branches
#   profile    : any profile from ~/.ace-copilot/config.json (e.g. claude,
#                copilot, copilot-sonnet, copilot-haiku, ollama). Bare
#                provider names also accepted for backward compatibility.
#   Example: ./dev.sh copilot-sonnet  # use the copilot-sonnet profile
#   Example: ./dev.sh --no-bench      # quick restart, skip benchmarks
set -e

# ---------------------------------------------------------------------------
# Parse arguments: flags first, then positional profile/provider name
# ---------------------------------------------------------------------------
BENCH_MODE=""
PROFILE=""

for arg in "$@"; do
    case "$arg" in
        --check)    BENCH_MODE="check" ;;
        --baseline) BENCH_MODE="baseline" ;;
        --auto)     BENCH_MODE="auto" ;;
        --no-bench) BENCH_MODE="none" ;;
        *)          PROFILE="$arg" ;;
    esac
done

# Known provider keywords — if the arg matches one of these we also set
# ACE_COPILOT_PROVIDER for backward compatibility with older usage.
VALID_PROVIDERS="anthropic openai openai-codex ollama copilot groq"

# ---------------------------------------------------------------------------
# Detect current branch — auto-enable benchmark mode on feature branches
# ---------------------------------------------------------------------------
CURRENT_BRANCH="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "unknown")"

if [ -z "$BENCH_MODE" ] && [ "$CURRENT_BRANCH" != "main" ] && [ "$CURRENT_BRANCH" != "unknown" ]; then
    BENCH_MODE="auto"
fi

# Auto-detect JAVA_HOME if not set — require exact Java 21
if [ -z "$JAVA_HOME" ]; then
    # macOS: use java_home utility which correctly resolves registered JVMs
    DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
        export JAVA_HOME="$DETECTED_JDK"
    fi
fi

# ---------------------------------------------------------------------------
# Benchmark mode: --auto resolves to --check or --baseline
# ---------------------------------------------------------------------------
if [ "$BENCH_MODE" = "none" ]; then
    BENCH_MODE=""
elif [ "$BENCH_MODE" = "auto" ]; then
    if ! CHANGED_FILES="$(git diff --name-only origin/main...HEAD 2>/dev/null)"; then
        echo ">> --auto: unable to diff against origin/main, falling back to check mode"
        BENCH_MODE="check"
    elif [ -z "$CHANGED_FILES" ]; then
        echo ">> --auto: no diff against origin/main, skipping benchmarks"
        BENCH_MODE=""
    else
        # Patterns that upgrade to baseline mode
        BASELINE_MATCH=""
        while IFS= read -r f; do
            case "$f" in
                ace-copilot-memory/*)                                  BASELINE_MATCH=1 ;;
                scripts/generate-replay-report.sh)                 BASELINE_MATCH=1 ;;
                scripts/replay-quality-gate.sh)                    BASELINE_MATCH=1 ;;
                scripts/collect-continuous-learning-baseline.sh)   BASELINE_MATCH=1 ;;
                scripts/export-injection-audit-summary.sh)         BASELINE_MATCH=1 ;;
                *metrics*|*scorecard*|*rollout*|*candidate*|*replay*|*learning*) BASELINE_MATCH=1 ;;
            esac
        done <<EOF
$CHANGED_FILES
EOF

        if [ -n "$BASELINE_MATCH" ]; then
            BENCH_MODE="baseline"
            echo ">> --auto: detected learning/benchmark sensitive changes -> baseline mode"
        else
            BENCH_MODE="check"
            echo ">> --auto: normal feature changes -> check mode"
        fi
    fi
fi

# Export bench mode so the CLI can display it in the status line
export ACE_COPILOT_BENCH_MODE="${BENCH_MODE:-none}"

# Run benchmark steps if requested
if [ "$BENCH_MODE" = "check" ]; then
    echo ">> Running local benchmark check..."
    ./gradlew preMergeCheck -PreplayGateStrict=false
elif [ "$BENCH_MODE" = "baseline" ]; then
    echo ">> Running local benchmark check + baseline export..."
    ./gradlew preMergeCheck -PreplayGateStrict=false
    ./scripts/export-injection-audit-summary.sh
    ./scripts/collect-continuous-learning-baseline.sh --output .ace-copilot/metrics/continuous-learning/baseline.json
fi

# ---------------------------------------------------------------------------
# Build + restart
# ---------------------------------------------------------------------------
echo ">> Building CLI..."
./gradlew :ace-copilot-cli:installDist -q

# Stop old daemon (best-effort) — warn about active sessions
CLI_BIN=./ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli
if [ -S ~/.ace-copilot/ace-copilot.sock ]; then
    ACTIVE_SESSIONS=$("$CLI_BIN" daemon status 2>/dev/null | sed -n 's/.*Active Sessions: *//p' || echo "0")
    if [ "$ACTIVE_SESSIONS" -gt 0 ] 2>/dev/null; then
        echo ">> WARNING: Restarting daemon with $ACTIVE_SESSIONS active session(s)"
        echo ">> Other connected TUI windows will be disconnected."
    fi
    "$CLI_BIN" daemon stop 2>/dev/null || true
    sleep 0.5
fi
# Kill by PID if still alive
if [ -f ~/.ace-copilot/ace-copilot.pid ]; then
    kill "$(cat ~/.ace-copilot/ace-copilot.pid)" 2>/dev/null || true
    sleep 0.3
fi

# Export ACE_COPILOT_PROFILE so the daemon's config precedence picks it
# up. If the arg is also a plain provider name, export
# ACE_COPILOT_PROVIDER too for backward compat.
if [ -n "$PROFILE" ]; then
    export ACE_COPILOT_PROFILE="$PROFILE"
    case " $VALID_PROVIDERS " in
        *" $PROFILE "*) export ACE_COPILOT_PROVIDER="$PROFILE" ;;
    esac
    echo "Profile: $PROFILE"
fi

echo ">> Launching AceCopilot..."
exec ./ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli
