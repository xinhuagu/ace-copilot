#!/bin/sh
# Attach to a running AceCopilot daemon, or start one if none is running.
# Unlike dev.sh, this never stops/restarts the daemon and never runs benchmarks.
#
# Can be run from any directory — paths resolve relative to the AceCopilot repo.
#
# Usage: ./tui.sh [profile-or-provider]
#   Any profile name from ~/.ace-copilot/config.json works:
#     claude, copilot, copilot-sonnet, copilot-haiku, ollama, ...
#   Bare provider names also work for backward compatibility.
#   Example: ./tui.sh copilot-sonnet
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

VALID_PROVIDERS="anthropic openai openai-codex ollama copilot groq"

# Auto-detect JAVA_HOME if not set — require exact Java 21
if [ -z "$JAVA_HOME" ]; then
    DETECTED_JDK="$(/usr/libexec/java_home -v 21 2>/dev/null || true)"
    if [ -n "$DETECTED_JDK" ] && [ -d "$DETECTED_JDK" ]; then
        export JAVA_HOME="$DETECTED_JDK"
    fi
fi

# Find CLI binary — release layout (bin/) or dev layout (ace-copilot-cli/build/install/...)
CLI_BIN="$SCRIPT_DIR/bin/ace-copilot-cli"
if [ ! -x "$CLI_BIN" ]; then
    CLI_BIN="$SCRIPT_DIR/ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli"
fi
if [ ! -x "$CLI_BIN" ]; then
    echo ">> First run: building CLI..."
    "$SCRIPT_DIR/gradlew" -p "$SCRIPT_DIR" :ace-copilot-cli:installDist -q
    CLI_BIN="$SCRIPT_DIR/ace-copilot-cli/build/install/ace-copilot-cli/bin/ace-copilot-cli"
fi

# Export ACE_COPILOT_PROFILE so the daemon's config precedence picks it
# up. If the arg is also a plain provider name, export
# ACE_COPILOT_PROVIDER too for backward compat.
if [ -n "$PROFILE" ]; then
    export ACE_COPILOT_PROFILE="$PROFILE"
    case " $VALID_PROVIDERS " in
        *" $PROFILE "*) export ACE_COPILOT_PROVIDER="$PROFILE" ;;
    esac
fi

# No bench mode for TUI sessions
export ACE_COPILOT_BENCH_MODE="none"

echo ">> Launching AceCopilot TUI..."
exec "$CLI_BIN"
