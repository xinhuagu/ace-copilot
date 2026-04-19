#!/bin/sh
# Quick daemon restart: rebuild CLI, stop old daemon, launch CLI.
# No benchmarks, no checks — just restart as fast as possible.
#
# Usage: ./restart.sh [profile-or-provider]
#   Any profile name from ~/.ace-copilot/config.json works:
#     claude, copilot, copilot-sonnet, copilot-haiku, ollama, ...
#   Bare provider names (anthropic, openai, ollama, copilot, ...) also work
#   for backward compatibility.
set -e

# Resolve symlinks to find the real script location (not the symlink dir)
SELF="$0"
while [ -L "$SELF" ]; do
    DIR="$(cd "$(dirname "$SELF")" && pwd)"
    SELF="$(readlink "$SELF")"
    case "$SELF" in /*) ;; *) SELF="$DIR/$SELF" ;; esac
done
SCRIPT_DIR="$(cd "$(dirname "$SELF")" && pwd)"

# Parse optional profile or provider name
PROFILE=""
for arg in "$@"; do
    case "$arg" in
        *) PROFILE="$arg" ;;
    esac
done

# Known provider keywords (for backward-compat with the old provider-only
# switch). If the arg matches one of these, we also export
# ACE_COPILOT_PROVIDER so config logic falls through to that provider's
# default profile. Otherwise we treat the arg as a profile name and let
# the daemon resolve it against config.json.
VALID_PROVIDERS="anthropic openai openai-codex ollama copilot groq"

# Auto-detect JAVA_HOME if not set — require exact Java 21
if [ -z "$JAVA_HOME" ]; then
    DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
        export JAVA_HOME="$DETECTED_JDK"
    fi
fi

# Build (only if dev layout with gradlew exists)
if [ -x "$SCRIPT_DIR/gradlew" ]; then
    "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :ace-copilot-cli:installDist -q
fi

# Find CLI binary — release layout (bin/) or dev layout
CLI_BIN="$SCRIPT_DIR/bin/ace-copilot-cli"
if [ ! -x "$CLI_BIN" ]; then
    CLI_BIN="$SCRIPT_DIR/ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli"
fi

# Stop old daemon (best-effort) — warn about active sessions
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
# up. If the arg also happens to be a plain provider name, export
# ACE_COPILOT_PROVIDER too so backward-compat users who passed a
# provider name keep working.
if [ -n "$PROFILE" ]; then
    export ACE_COPILOT_PROFILE="$PROFILE"
    case " $VALID_PROVIDERS " in
        *" $PROFILE "*) export ACE_COPILOT_PROVIDER="$PROFILE" ;;
    esac
fi

# No benchmarks
export ACE_COPILOT_BENCH_MODE="none"

exec "$CLI_BIN"
