# NearbyIM Agent Skill

Official agent-facing operating instructions for **NearbyIM Terminal** (`nearbyim`), following the [Agent Skills format](https://agentskills.io/specification). Main entry point: [SKILL.md](SKILL.md).

## Installation

Copy this **whole `nearbyim` directory** into a skills directory recognized by your chosen agent, keeping `SKILL.md` and `references/` together. This repository stores it at `.agents/skills/nearbyim/` for compatible repository-aware agents. Each agent may use different discovery or installation rules; refer to its own documentation.

The Skill does **not** install NearbyIM, provide shell permissions, connect to a user's other machine, launch an MCP server or supply a background service. Install the terminal executable separately on the machine where the agent can execute shell commands. Check `nearbyim --help` on that machine before acting.

## Design

- CLI-first, no MCP requirement or runtime dependencies added.
- Human approval for actual sends, scans, trust changes, file actions and listener exposure.
- Explicit distinction between saved receipts and reads, TOFU and manual verification, a foreground listener and a daemon.
- References map to the actual commands in [Terminal manual](../../../terminal/README.md), not hypothetical future APIs.

Tracked in [Issue #50](https://github.com/GHOST-AKU/wozai/issues/50); terminal CLI implementation and hardware validation remain tracked separately in [Issue #38](https://github.com/GHOST-AKU/wozai/issues/38).
