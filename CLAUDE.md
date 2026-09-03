# CLAUDE.md

Build: `mvn compile`    Run: `mvn compile exec:java` (add `-Dexec.args="--cli"` for terminal)    Test: `mvn test` (no network, no keys)

Rules:
- Parked until Cowork Phase 1 ships. Audit + README only this cycle. No feature work. See `DECISIONS.md`.
- Read `DEBT-INVENTORY.md` before any code change. If empty, run the audit session first; never audit and change code in one session.
- `AgentClient` contract: one blocking round-trip per `send`, never throw on API errors, build JSON with Gson objects, never put a key in a string.
- `Panel` needs exactly one client per panelist seat. OpenClaw is the Build-stage hand-off target, not a panelist.
- Never commit `config.properties`, `projects/`, `panelists.json`, `context.txt`.
- One deliverable per session. Stop and report.
