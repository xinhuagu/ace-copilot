<h1 align="center">ace-copilot</h1>

<p align="center">A GitHub Copilot–focused agent harness. One premium request per user turn — on purpose.</p>

<p align="center">
  <a href="https://github.com/xinhuagu/ace-copilot/actions/workflows/ci.yml"><img src="https://github.com/xinhuagu/ace-copilot/actions/workflows/ci.yml/badge.svg" alt="CI"></a>
  <img src="https://img.shields.io/badge/Java-21-orange?logo=openjdk" alt="Java 21">
  <img src="https://img.shields.io/badge/Node.js-20%2B-339933?logo=node.js&logoColor=white" alt="Node.js 20+">
  <img src="https://img.shields.io/badge/Gradle-8.14-02303A?logo=gradle&logoColor=white" alt="Gradle 8.14">
</p>

<p align="center">
  <img src="docs/img/ace-copilot-hero-v2.png" alt="Left: GitHub Copilot's pay-per-request meter clicking up with every intermediate step. Right: ace-copilot's structured execution runtime — one controlled agent execution per user turn." width="700">
</p>

> GitHub Copilot bills by **premium request** on a hard monthly quota — a model unfriendly to agent harnesses. Per-iteration on `/chat/completions`, hidden session-endpoint multipliers, trimmed context windows, overage priced above direct API access. ace-copilot uses Copilot's own session SDK in a CLI/daemon so a multi-tool, multi-iteration agent turn costs **one** premium request, and surfaces the costs Copilot hides. Full analysis → [docs/copilot-session-runtime.md](docs/copilot-session-runtime.md).

## Quick Start

### Install

```bash
curl -fsSL https://raw.githubusercontent.com/xinhuagu/ace-copilot/main/install.sh | sh
```

Requires Java 21 runtime. Node.js 20+ is needed for the Copilot session runtime (without it the daemon falls back to the chat path).

### Run against Copilot

```bash
ace-copilot-restart copilot    # Start daemon in Copilot session mode
ace-copilot                    # Attach TUI
```

First-time login: `gh auth login` (preferred) or the device-code flow ace-copilot prints on first start. A GitHub Copilot subscription (Individual / Business / Enterprise) is required.

### Choose a model

Default is `claude-haiku-4.5` — the only Copilot model with a 1× session-mode multiplier; every other model is 3×. Switch mid-session with `/model <name>` or at launch with `ACE_COPILOT_MODEL=...`. See [docs/provider-configuration.md](docs/provider-configuration.md) for the full model list and per-model notes.

## Commands

| Command | What it does |
|---------|-------------|
| `ace-copilot` | Start TUI (auto-starts daemon if not running) |
| `ace-copilot-tui [profile]` | Open another TUI window — non-destructive, no daemon restart |
| `ace-copilot-restart [profile]` | Stop daemon + restart with fresh build |
| `ace-copilot-update` | Update to latest release |
| `ace-copilot daemon start\|stop\|status` | Daemon lifecycle |

## Docs

- **[Copilot session runtime](docs/copilot-session-runtime.md)** — savings table, billing facts with citations, Phase 4 locked decisions, honest billing UX, verification walkthrough.
- **[Phase 4 audit](docs/copilot-phase4-audit.md)** — LLM call-site inventory and per-site decisions (dev-facing).
- **[Provider configuration](docs/provider-configuration.md)** — available models, auth modes, env vars.
- **[Multi-session model](docs/multi-session.md)** — multiple TUI windows on one daemon.
- **[Design philosophy](docs/design-philosophy.md)** — why Java, why no AI framework.

## Build from Source

```bash
git clone https://github.com/xinhuagu/ace-copilot.git && cd ace-copilot
./gradlew clean build && ./gradlew :ace-copilot-cli:installDist
```

Dev scripts: `./dev.sh`, `./restart.sh`, `./tui.sh` (all accept `[profile]` arg).

## Platform Support

| Platform | Status |
|----------|--------|
| **Linux** | Fully supported |
| **macOS** | Fully supported |
| **Windows 10 1803+** | Experimental |

## Tech Stack

Java 21 · Gradle 8.14 · Node 20+ · Picocli · JLine3 · Jackson · JUnit 5

## License

[Apache License 2.0](LICENSE)
