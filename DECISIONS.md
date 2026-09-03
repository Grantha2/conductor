# Decisions

One line per decision, with date and reasoning. Newest at the bottom.

- 2026-09-02 `[DECIDED]` — Conductor is a real product repo, not a plumbing source for the other repos. It is a Replit-style app for SDLC-principled development: deterministic rails for non-engineers, agents called only where they add value. Competes with Claude Code, Cursor, Replit, and McKinsey's ARK.
- 2026-09-02 `[DECIDED]` — Extracted from `aicollab` when that codebase split into `debate-engine`, `conductor`, and `cowork-suite`. The debate panel in `conductor/panel` is a sibling of `debate-engine`'s `Maestro`, not a dependency on it; the two evolve separately.
- 2026-09-02 `[DECIDED]` — Parked this cycle. Audit and README only. No feature work until Cowork Phase 1 ships. Reason: it competes with well-funded teams, the differentiating premise (non-engineers, not engineers) needs design bandwidth that does not exist before December graduation, and the flagship has a real user and real distribution where this does not. Revisit scope after Cowork Phase 1.
- 2026-09-02 `[DECIDED]` — Do not fix debt in a repo that is not being developed. The audit session fills `DEBT-INVENTORY.md`; nothing gets executed from it this cycle.
- 2026-09-02 — This repo keeps its own provider clients (Anthropic, OpenAI, Gemini, OpenClaw) for now. The other repos adopt OpenRouter as the model layer; migrating Conductor is a post-park decision, and only worth it if it costs less than maintaining four clients. Note that OpenRouter's per-request fallback would replace `Http`'s retry loop and the `[Name unavailable]` panelist degradation.
- 2026-09-02 — The OpenClaw connector here (`agents/OpenClawClient`) is one of the candidates for reuse by `cowork-suite`'s check-in agent, possibly via `cowork-shared`. If it is lifted out, keep a copy here; Conductor's Build-stage hand-off depends on it.
- 2026-09-02 — Revisit triggers: Cowork Phase 1 shipped; a contributor with front-end design skill wants a project; post-graduation.
