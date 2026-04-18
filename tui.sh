#!/bin/sh
# Attach to a running AceCopilot daemon, or start one if none is running.
# Unlike dev.sh, this never stops/restarts the daemon and never runs benchmarks.
#
# Can be run from any directory — paths resolve relative to the AceCopilot repo.
#
# Usage: ./tui.sh [provider]
#   provider: anthropic (default), openai, openai-codex, ollama, copilot, groq
#   Example: ./tui.sh ollama
set -e

# Resolve symlinks to find the real script location (not the symlink dir)
SELF="$0"
while [ -L "$SELF" ]; do
    DIR="$(cd "$(dirname "$SELF")" && pwd)"
    SELF="$(readlink "$SELF")"
    case "$SELF" in /*) ;; *) SELF="$DIR/$SELF" ;; esac
done
SCRIPT_DIR="$(cd "$(dirname "$SELF")" && pwd)"

# Parse optional provider
PROVIDER=""
for arg in "$@"; do
    case "$arg" in
        *) PROVIDER="$arg" ;;
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

# Validate and set provider via env if specified
if [ -n "$PROVIDER" ]; then
    case " $VALID_PROVIDERS " in
        *" $PROVIDER "*) ;;
        *) echo "Invalid provider: $PROVIDER"; echo "Valid: $VALID_PROVIDERS"; exit 1 ;;
    esac
    export ACE_COPILOT_PROVIDER="$PROVIDER"
fi

# No bench mode for TUI sessions
export ACE_COPILOT_BENCH_MODE="none"

echo ">> Launching AceCopilot TUI..."
exec "$CLI_BIN"
