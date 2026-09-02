# Conductor — Future Directions

Ideas for contributors, in rough priority order. None of these are built.
Each is scoped so one person (or one agent) could pick it up cold.

## Vision in one paragraph

Conductor puts a non-engineer on rails through the software development
lifecycle: Idea → Users → Requirements → Design → Plan → Build → Verify →
Ship. Plain Java asks the questions; agents are called only where a second
opinion earns its cost; a panel of agents from different providers debates
the two stages where perspective matters most. The output is a set of
artifacts precise enough to hand to an engineering agent — an OpenClaw
agent, Claude Code, or a human team — to build. The user never writes a
prompt and never sees an API key.

## Near-term (each a weekend)

1. **Hand-off round-trip with OpenClaw.** Today BUILD can *send* the brief
   to an OpenClaw agent. Next: poll or subscribe for progress, show the
   agent's questions back to the user in the same form UI, and let the
   user answer without leaving Conductor. Files: `StageRunner.handOffToOpenClaw`,
   a new `HandoffPanel` in `conductor.ui`.
2. **Export artifacts.** One button that writes `projects/<slug>/` as a
   folder of Markdown files (`01-idea.md` … `08-ship.md`) plus a combined
   `BRIEF.md`. Pure Java, no agent.
3. **Cost meter.** Sum `AgentResponse.inputTokens/outputTokens` per stage
   and show an estimated dollar figure in the status bar using a small
   per-model price table in `config.properties`. Non-engineers should see
   what a stage costs before they run it.
4. **Panelist presets.** Ship 2–3 named panels ("Product trio", "Safety
   review", "Speed vs quality") users can switch between. `panelists.json`
   becomes `panels/<name>.json`.
5. **Tests against recorded responses.** Capture one real response per
   provider (redacted) into `src/test/resources/` and assert the parsers.
   Today's tests only check request *bodies*.

## Medium-term (a few weeks)

6. **Agents managing agents.** A "manager" panelist that, at PLAN, splits
   tasks across sub-agents (one per provider) and at BUILD reviews their
   output. Uses the existing tool loop (`AgentClient.run`) with a
   `delegate(task, provider)` tool. Start with one level of delegation.
7. **Structured artifacts everywhere.** REQUIREMENTS and DESIGN currently
   return synthesis text. Give them JSON schemas too (requirement id,
   priority, testable statement; component, responsibility, depends-on) so
   VERIFY can generate checks mechanically instead of asking an agent.
8. **Visual rail.** Replace the JList with a horizontal stage track that
   shows progress, artifact size, and cost per stage. Still Swing.
9. **Multiple users on one project.** Store projects in a shared folder or
   a tiny HTTP service; show who answered what. The Project model already
   has `updatedAt` — add `updatedBy`.
10. **Official Anthropic SDK.** `AnthropicClient` is raw `java.net.http` on
    purpose (teachability, one pattern across four providers). When the
    team is comfortable, swapping in `com.anthropic:anthropic-java` gets
    typed errors, automatic retries, and streaming for free. Keep the
    `AgentClient` interface; only the implementation changes.
11. **Refusal fallbacks.** Newer Anthropic models can return
    `stop_reason: "refusal"`. The API supports a server-side `fallbacks`
    parameter (beta) that re-runs on another model. Wire it in
    `AnthropicClient` behind a config flag once the team wants it.

## Long-term (needs a design conversation first)

12. **Web UI.** Swing was chosen because the team knows it. The
    `conductor.sdlc` package has no Swing dependency; a thin HTTP layer
    over `StageRunner` + `ProjectStore` would let a browser front-end
    reuse everything.
13. **Templates for kinds of projects.** "A website", "An internal tool",
    "A data pipeline" — each with tuned questions and panelist lenses.
14. **Scheduled check-ins.** A stage can register a follow-up ("ask me in
    3 days whether the Build stage is done"). Needs a tiny scheduler and
    a notification channel.

## Principles to keep

- **Ask the user first, the agent second.** If plain Java can produce the
  artifact from the user's answers, do that. Agents are for judgement,
  not formatting.
- **≤ 1 agent call per question.** The exception is the debate panel at
  Requirements and Design, and the user is told the call count before it
  runs.
- **Provider-blind above `AgentClient`.** Nothing in `sdlc` or `ui` knows
  which vendor is answering. Adding a provider is one file.
- **No secrets in code, logs, or artifacts.** Keys live in
  `config.properties` (gitignored). Error strings are redacted.
