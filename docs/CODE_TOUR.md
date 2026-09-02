# Conductor code tour

For a contributor who knows Java but not this codebase. About 1,800 lines of main code in five
packages; you can read all of it in an afternoon. This document gives the order to read it in and
what to notice. It refers to files and methods, never to line numbers.

## 30-second map

```
  MainGui (Swing)          Main --cli (terminal)
          \                      /
           v                    v
             Wiring  <-- Config (config.properties), Clients.build,
               |         Panelist.loadAll (panelists.json), loadContext (context.txt)
               v
          StageRunner --- REQUIREMENTS, DESIGN ------------> Panel (one AgentClient per seat)
               |  \------ PLAN, BUILD, VERIFY, "sharpen" ---> lead AgentClient (clients[0])
               \--------- BUILD hand-off (optional) --------> OpenClawClient

  AgentClient implementations: AnthropicClient | OpenAiClient | GeminiClient | OpenClawClient
        -> Http.postJson (timeout, retry, key redaction) + Json (Gson helpers) -> java.net.http

  Persistence, beside the flow:  ProjectStore <-> projects/<slug>.json
                                 Panelist <-> panelists.json      Wiring <-> context.txt
```

Everything above `AgentClient` is provider-blind: nothing in `sdlc`, `panel` or `ui` knows which
vendor is answering.

## Reading order

1. `agents/AgentClient.java` - the one interface. Providers implement `send`; the default `run` is
   the tool loop, written once for all of them.
2. `agents/AgentRequest`, `AgentResponse`, `ChatMessage`, `ToolSpec`, `ToolCall`, `ToolResult`,
   `ToolExecutor` - the vocabulary. Records (plus one functional interface) whose compact
   constructors normalise nulls to empty values.
3. `agents/Http.java`, `agents/Json.java` - retry policy, key redaction and the two JSON-schema
   rewrites, each in exactly one place.
4. `agents/OpenAiClient.java` first (plainest mapping; `OpenClawClient` reuses it), then
   `AnthropicClient`, `GeminiClient`, `OpenClawClient`. Each has a package-private `buildBody`
   so tests can assert the request without a network.
5. `config/Config.java`, `config/Clients.java` - where clients come from. Index 0 is the lead.
6. `panel/Panelist.java`, `panel/Panel.java` - `Panel.debate` is the whole algorithm.
7. `sdlc/Stage`, `Question`, `Questions` - the rails as data. `agentAssist` is the only place the
   "one agent call per question" rule lives.
8. `sdlc/Project`, `ProjectStore`, `ProjectContext` - the saved state, and the Markdown rendering
   of it that every agent call receives as system context.
9. `sdlc/StageRunner`, `PlanFormat` - what "Complete stage" does, per stage.
10. `Wiring`, `Main`, `ui/MainGui` - assembly, the CLI loop, the window. Then skim `src/test/java`.

## The contract layer (`conductor.agents`)

`AgentRequest(system, messages, tools, outputSchema, maxTokens)` rejects an empty message list and
a non-positive budget; `text(...)` and `json(...)` cover the common cases and `withMessages` gives
the tool loop a copy with a new history. `system` holds the stable instructions and **must be
byte-identical between calls at the same stage**: providers cache the system prefix only when it
matches exactly, and a timestamp or per-call id in it silently defeats that. `ProjectContext.render`
emits nothing that changes within a stage, and `Panel` appends the same `Panelist.briefing()` for a
given seat every time; `PanelTest` asserts the prefix is identical across a seat's calls.

`AgentResponse(text, toolCalls, stopReason, inputTokens, outputTokens, error)`. Check `ok()` first;
when `error` is set every other field is empty or zero. `stopReason` is normalised to `end_turn`,
`tool_use`, `max_tokens`, `refusal` or `error`, read through `wantsTools()`, `truncated()`,
`refused()`. `ChatMessage` has two ordinary shapes (`user`, `assistant`) and two that exist only
for the tool loop, `assistantToolCalls(text, calls)` and `toolResults(results)`; translating those
two to the wire format is the only tool-related work a provider does.

**The tool loop.** `AgentClient.run(request, executor, maxIterations)`: without tools or an
executor it is just `send`. Otherwise, per iteration: `send`; return if the response is an error or
asks for no tools; append an `assistantToolCalls` turn; run every call through the executor (a
`RuntimeException` becomes `ToolResult.error`); append one `toolResults` turn. Exhausting
`maxIterations` returns `AgentResponse.error("Tool loop exceeded ...")`. Nothing in the SDLC flow
uses tools yet (`FUTURE.md` item 6 will); `AgentClientRunTest` covers the loop.

**Never throw; return `error()`.** Every `send` catches `IOException | RuntimeException` and
returns `AgentResponse.error`. A non-200 status becomes `"[<provider> HTTP <code>] <redacted body>"`;
an exception becomes `"[<provider>] <redacted message>"`. This is why one failed panelist cannot
abort a stage. `StageRunner.send` catches again in case a client breaks the rule.

## The four clients

All four share a skeleton: the constructor fixes endpoint, headers, key and model; `send` posts via
`Http.postJson`, maps non-200 to an error and hands the parsed body to a private `parse`.

**`Http.postJson`** is the only HTTP path. Five-minute per-attempt timeout. Retries `IOException`s
and statuses 408, 409, 429 and 5xx up to three times (four attempts) with 1 s / 2 s / 4 s backoff,
preferring a numeric `retry-after` (seconds, capped at 60; the HTTP-date form is ignored). Other 4xx
return immediately. A key containing a character illegal in a header would make the JDK quote the
key in its exception; `postJson` substitutes a message that does not. `redactKeys(text)` masks
`(sk-|AIza)[A-Za-z0-9_-]{8,}`; `redactKeys(text, secret)` also replaces the exact configured secret,
which covers OpenClaw tokens of any shape.

**`Json`**: `GSON` (HTML escaping off); `of(k, v, ...)` one-line objects; `arrayOf`; null-safe
`str` / `num` / `obj` / `arr` (providers omit `usage` on errors and `content` on refusals);
`parseObject` turns anything that is not an object into `{}`. Two deep-copy schema rewrites:
`closedObjects` adds `additionalProperties:false` to every object node lacking it (Anthropic and
OpenAI strict modes require it); `withoutAdditionalProperties` strips it everywhere (Gemini rejects it).

### `AnthropicClient`

- `POST {anthropic.url}/v1/messages`; headers `x-api-key` and `anthropic-version: 2023-06-01`.
- Body: `model`, `max_tokens`, `system` as an array of one text block carrying `cache_control:
  {type: ephemeral}`, `messages`, optional `tools`, optional `output_config.format = {type:
  json_schema, schema: closedObjects(...)}`. Deliberately no `temperature`, `top_p`, `top_k`,
  `thinking` or `tool_choice`.
- Tools: `{name, description, input_schema: closedObjects(...), strict: true}`, with one
  `cache_control` breakpoint on the **last** tool only - tools precede system in the cache prefix,
  so one breakpoint covers them all.
- Tool-call turn: assistant content of a text block (only when non-blank; an empty text block is a
  400) plus `tool_use` blocks. Tool-result turn: one user message holding all `tool_result` blocks
  with `tool_use_id` and `is_error` when set.
- Parse: text blocks concatenated, `tool_use` blocks collected; `stop_reason` maps `tool_use` /
  `max_tokens` / `refusal`, else `end_turn`; a refusal's text is prefixed "The model declined this
  request" plus `stop_details.explanation`. Usage from `usage.input_tokens` / `output_tokens`.

### `OpenAiClient`

- `POST {openai.url}` (default `https://api.openai.com/v1/chat/completions`); header
  `Authorization: Bearer <key>`, omitted when the key is blank. The provider label in error strings
  is injectable through a package-private constructor - the seam `OpenClawClient` uses.
- Body: `model`, `max_completion_tokens` (not `max_tokens`), `messages` with the system prompt as a
  leading `role: system` message, optional `tools` as `{type: function, function: {name,
  description, parameters: closedObjects(...), strict: true}}`, optional `response_format = {type:
  json_schema, json_schema: {name: "result", schema: closedObjects(...), strict: true}}`.
- Tool-call turn: assistant `tool_calls[]` where `function.arguments` is a JSON **string**
  (`Json.GSON.toJson(arguments)`). Tool results: one separate `role: "tool"` message **per result**
  with `tool_call_id`; error results are prefixed `ERROR: `.
- Parse: `choices[0].message`; each `function.arguments` string is `parseObject`ed (unparseable
  becomes `{}`). `finish_reason` maps `tool_calls` to `tool_use`, `length` to `max_tokens`,
  `content_filter` to `refusal`; otherwise `refusal` if `message.refusal` is non-blank, else
  `end_turn`. A refusal with no text gets readable text. Usage from `usage.prompt_tokens` /
  `completion_tokens`. Empty `choices` is an error response.

### `GeminiClient`

- `POST https://generativelanguage.googleapis.com/v1beta/models/{model}:generateContent`; key in
  the `x-goog-api-key` header, never the URL, so it cannot leak into logs.
- Body: `system_instruction.parts[{text}]`; `contents[]` with roles `user` / `model`;
  `tools[{functionDeclarations[...]}]` with `parameters: withoutAdditionalProperties(...)`;
  `generationConfig` with `maxOutputTokens` and, in JSON mode, `responseMimeType:
  application/json` plus a stripped `responseSchema`.
- Gemini keys tool results by function **name** and assigns no call ids. `buildBody` keeps a
  `callId -> name` map while replaying history so each `functionResponse` is emitted under its
  name; `parse` mints `<name>-<8 hex chars>` ids when the response has none.
- Parse: `usageMetadata.promptTokenCount` / `candidatesTokenCount`. No `candidates` is a refusal
  carrying `promptFeedback.blockReason`. Parts with `thought: true` are skipped. `finishReason`
  `MAX_TOKENS` maps to `max_tokens`; `SAFETY`, `RECITATION`, `BLOCKLIST`, `PROHIBITED_CONTENT`,
  `SPII` to `refusal`; otherwise `tool_use` if calls were collected, else `end_turn`. There are
  no prompt-caching controls for this API.

### `OpenClawClient`

Wraps an `OpenAiClient` on `{openclaw.base.url}/v1/chat/completions` with the token as bearer,
model `openclaw/<agentId>` (`default` when blank) and provider label `openclaw`. `send` calls the
inner client; if the result is an error starting `[openclaw HTTP 400]` **and** the request carried
tools or a schema, it retries once with both removed and, on success, prefixes `DEGRADED_NOTE` to
the text. Any other outcome is returned unchanged.

## The panel (`conductor.panel`)

`Panelist(name, perspective, lens)` is one seat; `briefing()` renders the identity block appended
to the system context. `loadAll(file)` returns `defaults()` (Claude / GPT / Gemini with
architecture, ideas and delivery lenses) on any problem: missing file, bad JSON, empty array, a null
seat, a nameless seat. `saveAll` throws `UncheckedIOException`; losing an edit silently is worse.

`Panel(clients, panelists, rounds, maxTokens)` insists on one client per panelist and at least two
seats. `debate(systemContext, question, listener)` runs, for N seats and R rounds:

```
phase 1     for i in 0..N-1:   send(i, context + briefing(i), question)                 -> phase1[i]
rounds      for r in 1..R, for i in 0..N-1:
                               send(i, ..., question + every OTHER seat's latest answer)  -> reactions[i]
            latest = reactions
synthesis   send(0, ..., question + all phase1 + (R > 0 ? all latest : nothing))        -> synthesis
```

`apiCallCount() = 1 + N + N * R`: 7 with three seats and one round, 4 with `debate.rounds=0`. The
lead (index 0) writes the synthesis under four headings: Agreements; Disagreements; Insights someone
missed; Concrete recommendation.

A failed seat never stops the debate: `ask` turns an error response into
`[<name> unavailable: <error>]`, which is what the other seats and the synthesis see. A
`RuntimeException` escaping `debate` is caught by `StageRunner.debate` and becomes the artifact
`[panel unavailable: ...]`.

The listener receives `(kind, who, text)` with `kind` in `status`, `phase1`, `phase2`, `synthesis`.
A status line precedes each send and one closes the run ("Debate complete."). The GUI appends
non-status events to the output pane and puts status in the status bar; the CLI prints first lines.

## The rails (`conductor.sdlc`)

**`Stage`**: the eight stages in order, each with `title` and `goal`; `next()` / `previous()` clamp
at the ends.

**`Question(id, stage, prompt, help, required, agentAssist)`**. `agentAssist` is the one knob that
lets an agent near a question: the UI offers "Ask an agent to sharpen this" and `StageRunner.assist`
makes at most one lead call. `of` (required) and `optional` default it to false; `withAgentAssist()`
flips it.

**`Questions`** is the catalogue, pure data. `QuestionsTest` enforces three to five questions per
stage, unique lowercase `stage.name` ids, and exactly one assisted question per stage.
`*` = assisted, `(opt)` = optional:

| Stage | Question ids |
|---|---|
| IDEA | `idea.what`, `idea.why`*, `idea.today` (opt), `idea.not` (opt) |
| USERS | `users.who`, `users.need`, `users.success`*, `users.context` (opt) |
| REQUIREMENTS | `req.must`*, `req.should` (opt), `req.never`, `req.constraints` (opt) |
| DESIGN | `design.pieces`*, `design.data`, `design.connect` (opt), `design.worry` (opt) |
| PLAN | `plan.first`*, `plan.deadline`, `plan.people` (opt), `plan.risks` (opt) |
| BUILD | `build.target`, `build.tech` (opt), `build.done`*, `build.avoid` (opt) |
| VERIFY | `verify.how`*, `verify.who` (opt), `verify.reject` |
| SHIP | `ship.audience`, `ship.channel`, `ship.message`*, `ship.learned` (opt) |

**`Project`**: `name`, `slug`, `createdAt`, `updatedAt`, `current` stage, `answers` (question id
to text), `artifacts` (stage to text). A mutable Gson-friendly class, not a record, because the UI
edits it in place; every setter bumps `updatedAt`. `isComplete(stage)` means a non-blank artifact.
`slugify("My Cool App!")` is `my-cool-app`; anything empty becomes `project`.

**`ProjectContext.render(project)`** is what agents see: a `# Project:` heading and, per stage with
content, the answered questions as `**prompt**` / answer pairs and the artifact under an "agreed
output" sub-heading. Nothing in it changes within a stage, so it caches. `renderStage` gives one
stage's answers and feeds the IDEA / USERS summaries.

**`StageRunner`** is what "Complete stage" does. The system context for every call is `context.txt`
(if any) followed by `ProjectContext.render`.

| Stage | Input | Processing | Artifact | Calls |
|---|---|---|---|---|
| IDEA, USERS | this stage's answers | `summary`, pure Java | `# <Title>` + rendered answers | 0 |
| REQUIREMENTS | full context | `Panel.debate` with `REQ_Q` (MUST / SHOULD / NEVER, testable) | the synthesis | `apiCallCount()` |
| DESIGN | full context | `Panel.debate` with `DESIGN_Q` (pieces, how they talk, riskiest, build first) | the synthesis | `apiCallCount()` |
| PLAN | full context | lead call with `PlanFormat.SCHEMA` (JSON mode) | `PlanFormat.artifact`: checklist + fenced JSON | 1 |
| BUILD | full context | lead call with `BUILD_Q` (brief for an agent that never met the user) | the brief | 1 |
| VERIFY | PLAN artifact + answers | checklist of task titles via `PlanFormat.titles`; lead call with `VERIFY_Q` only if `verify.how` is answered | `# Verify`: Checklist, How we will check, Suggested checks | 0 or 1 |
| SHIP | six answers | `releaseNotes`, a template | `# Release notes:` with `_(not answered)_` gaps | 0 |

`estimateCalls` returns the same numbers so the UI can show them first. `assist(project, question,
draft)` asks the lead for a sharper answer in the user's voice, at most 120 words; on failure it
returns the draft plus `[assist unavailable: ...]`. `handOffToOpenClaw(project)` sends the BUILD
artifact as one user message under the fixed `HANDOFF_SYSTEM` prompt (not the project context) and
returns the reply, or an explanation when OpenClaw is not configured or BUILD is incomplete.
`complete` never throws.

**`PlanFormat`**: `SCHEMA` asks for `{tasks: [{id, title, description, size: S|M|L, dependsOn?}]}`.
`artifact(raw)` renders Small / Medium / Large / Unsized groups of `- [ ] **T2** Title _(after T1)_`
lines with indented descriptions, then appends the raw JSON in a fenced block; invalid input yields
`# Plan (could not parse as JSON)` plus the raw text. `titles(artifact)` reads the task titles back
out of the fence so VERIFY never parses prose.

**`ProjectStore`**: one pretty-printed JSON file per project at `<dir>/<slug>.json`. `listSlugs`
sorts the `.json` names; `load` returns null plus one stderr line for a missing file, bad JSON, or a
file lacking slug / answers / artifacts (it stays listed so the user can delete it); `save` creates
the directory and throws `UncheckedIOException` on failure. The file shape:

```json
{ "name": "Garden Buddy", "slug": "garden-buddy", "createdAt": "...", "updatedAt": "...",
  "current": "REQUIREMENTS",
  "answers":   { "idea.what": "An app that reminds me to water my plants", "idea.why": "..." },
  "artifacts": { "IDEA": "# Idea\n\n**What do you want to build?**\n...", "USERS": "..." } }
```

## UI and CLI

**`MainGui`** layout: toolbar (project name, **New project...**, **Open...**, **Hand off to
OpenClaw** - visible only on BUILD, enabled only when OpenClaw is configured); a `JList<Stage>` rail
on the left with `✓` for completed and `▶` for the current stage; a `JSplitPane` whose left half is
the form (one wrapped `JTextArea` per question, a "sharpen" button under the assisted one,
**Complete stage** below) and whose right half is the read-only monospaced "Stage output" viewer; a
`Settings` menu (**Panelists...**, **Context...**); a status label at the bottom. `showStage`
rebuilds the form, loads the artifact, relabels the button "Complete stage again (replaces output)"
when an artifact exists, and writes the call estimate to the status bar.

Threading: `runInBackground(message, work, onDone)` wraps every agent action in a `SwingWorker` -
the supplier runs off the EDT, the consumer runs in `done()` on the EDT, and any exception becomes a
status message. `setBusy` disables all buttons meanwhile. The `Panel.Listener` from `listener()`
wraps each event in `SwingUtilities.invokeLater`.

Saving: `commitFields` copies changed text areas into the in-memory `Project` on every stage switch,
complete, sharpen or hand-off. `saveQuietly` writes the file when you switch project (the outgoing
one is saved), when a stage completes, and on window close - a stage switch alone does not hit disk.
The OpenClaw reply and the debate transcript are shown but not stored. `completeStage` refuses while
a required answer is blank and confirms the call count when it is non-zero.

**`Main`** sets the HiDPI properties before any Swing class loads, then dispatches `--cli` to
`cli()` or else `MainGui.launch()`. The CLI: load `Config` (exit 1 if the file is missing or a key
is a placeholder), build `Wiring`, pick or create a project, then from `project.current()` onward:
print title and goal, ask each question (multi-line, blank line ends; Enter keeps the current
answer; the assisted question offers `a` to sharpen then `y` to accept), save, show the call count
and ask to complete, run `complete` printing one line per listener event, print the artifact, save,
advance. Anything but `y` to "Complete stage?" ends the session with everything saved. The CLI does
not enforce required answers.

**`Wiring`** holds the decisions GUI and CLI must share: the lead is `clients.get(0)` (Anthropic, by
`Clients.build` order); `openclawOrNull()` is the last client when `openclaw.base.url` is set;
`panelClients()` is every client except OpenClaw, so the panel is always the three cloud seats;
`loadPanelists()` accepts `panelists.json` only when its seat count matches, otherwise logs to
stderr and uses `Panelist.defaults()`; `runner(panelists, context)` builds the `Panel` with
`debate.rounds` and `max.tokens`.

## Tests

`mvn test` needs no keys and opens no sockets. Three fakes make that possible: `agents/FakeAgentClient`
(scripted `AgentClient`: `reply`, `replyToolCalls`, `replyError`; records `requests`; answers
`"<name> #<n>"` when the script runs dry), `agents/StubHttpClient` (a real `HttpClient` subclass
returning queued status/body/headers or throwing a queued `IOException`; running out of script
throws), and `sdlc/FakeClient` (replies in order, last repeats forever; `failing` always errors).

| Test | Proves |
|---|---|
| `AgentClientRunTest` | no tools = one send; calls then results appended in order; executor exception becomes an error result; iteration cap returns an error |
| `AnthropicClientBodyTest` | cached system array; strict tools with one breakpoint on the last; `output_config` shape; no sampling params; tool turns as content blocks; nested schemas closed without mutating the caller's; tool_use / refusal / malformed bodies never throw |
| `OpenAiClientBodyTest` | system leads messages; `max_completion_tokens`; strict function tools; `response_format`; arguments as a JSON string; one `tool` message per result with `ERROR:` prefix; null content on tool turns; malformed responses never throw |
| `GeminiClientBodyTest` | key in header not URL; role mapping; `system_instruction`; declarations and JSON mode; results keyed by name; `additionalProperties` stripped at every depth; empty candidates is a refusal; thought parts skipped; ids minted |
| `OpenClawClientTest` | model routes to `openclaw/<agent>`; blank agent falls back to `default` |
| `HttpRetryTest` | 4xx not retried; 429 / 5xx retried then parsed; four attempts max; HTTP-date `retry-after` falls back to 1 s; IOExceptions retried; illegal header character reported without the key; configured token scrubbed; Gemini key only in header |
| `HttpRedactTest` | `sk-` / `AIza` masking; ordinary text untouched; exact-secret replacement |
| `ConfigTest` | placeholders are not real keys; defaults; overrides; numeric typos fall back; missing-file message names the example |
| `PanelTest` | 3 seats x 1 round = 7 calls in order; each seat sees only the others; failed seat becomes a placeholder; 0 rounds = 3 calls without "Final positions"; byte-identical system prefix; size mismatch rejected |
| `PanelistTest` | briefing format; JSON round trip; defaults on missing / empty / garbage / null / nameless seat; missing fields never null |
| `StageRunnerTest` | IDEA is pure Java; PLAN renders and falls back on garbage; REQUIREMENTS debates once with org context first; lead failure is readable text; VERIFY calls only when `verify.how` is answered; SHIP is a template; hand-off only when configured and BUILD complete |
| `ProjectStoreTest`, `ProjectTest`, `QuestionsTest` | round trip with unicode; overwrite and sorted listing; corrupt files load as null but stay listed; slug edge cases; the catalogue's own rules |

Not covered directly: `MainGui`, `Main`, `Wiring`, `Clients`, `Json`. `FUTURE.md` item 5 proposes
recorded real responses; today's parser tests use hand-written stub bodies.

To write a new test above `AgentClient`, build a `FakeAgentClient` (or `FakeClient`) per seat,
construct `Panel` or `StageRunner` directly, and assert on the recorded requests: `system()` for
context, `messages().get(0).content()` for the prompt, `wantsJson()` for the mode. For a client,
assert `buildBody` for the request and pass `new StubHttpClient().reply(status, body)` to the
constructor to drive `send` and the retry path.

## Extending

**Add a provider.** One class in `conductor.agents` implementing `send` (copy `OpenAiClient`'s
skeleton) and one line in `Clients.build`. If it is a debate seat, also add a seat to
`Panelist.defaults()`: `Panel` demands one panelist per client, and `Wiring.loadPanelists` falls back
to the three defaults when counts differ, so four clients with three defaults throws at startup. If
it is not a seat, exclude it in `Wiring.panelClients()` as OpenClaw is. Add a `<Name>ClientBodyTest`.

**Add a stage question.** Append a `Question` to `Questions.ALL` with a `stage.name` id; keep three
to five per stage and exactly one `agentAssist`, or `QuestionsTest` fails. The answer is stored and
rendered into `ProjectContext` automatically; reference the id in `StageRunner` only if a template
should use it (`releaseNotes` and `verify` show the pattern).

**Change a stage's processing.** Edit the case in `StageRunner.complete` and keep `estimateCalls`
truthful. Return text; never throw. For structured output follow `PlanFormat`: a schema constant, a
renderer that embeds the raw JSON, a reader that gets it back out.

**Add a panelist seat.** Seats and clients pair by position. Rename or re-aim the three existing
seats freely (Settings > Panelists..., or edit `panelists.json`); a fourth seat needs a fourth client
in `Clients.build` and a fourth entry in `Panelist.defaults()`.

## Glossary

- **Stage** - one of the eight `Stage` values; the rails.
- **Question / agentAssist** - one form field; the flag marking the one per stage with a "sharpen" button.
- **Artifact** - the text a stage produces on completion, stored on the `Project`.
- **Project / slug** - the saved state and its file-safe name.
- **Lead** - `clients.get(0)`, Anthropic by construction; writes the synthesis and answers every single-call stage.
- **Panelist / seat** - one debate participant: name, perspective, lens.
- **Panel / debate** - the three-phase run in `Panel.debate`.
- **Phase 1 / phase 2 / synthesis** - independent answers, cross-reactions, the lead's summary.
- **Round** - one pass of phase 2; `debate.rounds` in config.
- **System context** - `context.txt` plus `ProjectContext.render`, sent as `system`.
- **Stop reason** - the normalised `AgentResponse.stopReason`.
- **Tool loop** - `AgentClient.run`.
- **Degraded mode** - `OpenClawClient`'s plain-text retry after HTTP 400.
- **Hand-off** - sending the BUILD artifact to OpenClaw via `StageRunner.handOffToOpenClaw`.

## FAQ

**Why raw `java.net.http` and not provider SDKs?** One pattern across four providers is easier to
teach and keep consistent; the cloud clients are 125-140 lines each. `FUTURE.md` item 10 covers
swapping in the official Anthropic SDK behind the same interface.

**Why no streaming?** `send` is one blocking round-trip by contract; the panel listener already
gives per-panelist progress, and `max.tokens` stays at or below 16000 so responses fit one call.

**Why no persistence beyond `projects/`?** Every call is stateless and carries the whole project as
system context, so there is no conversation to save; provider caching absorbs the repeated prefix.

**Why is OpenClaw not a panelist?** `Panel` needs one client per seat and the default panel has
three; the gateway's tool and JSON support is optional (hence degraded mode); and in the product's
model OpenClaw is the builder that receives the brief, not a reviewer. `Wiring.panelClients()`
excludes it. The `config.properties.example` comment saying it joins as a fourth seat is out of date.

**What happens on a 429?** `Http.postJson` retries up to three more times, sleeping for
`retry-after` (capped at 60 s) or 1 s / 2 s / 4 s. If it still fails, the last response becomes
`[<provider> HTTP 429] ...`, shown as `[<Name> unavailable: ...]` in a debate or
`[lead unavailable: ...]` for a single-call stage. Re-run the stage later.

**Where do errors show up?** Never as exceptions to the user. Provider errors land in the artifact as
placeholders; UI problems in the status bar; file problems (`ProjectStore`, `Wiring`) as one stderr
line each; a missing or placeholder `config.properties` stops startup with a dialog (GUI) or message
(CLI) and exit code 1.

**Why Gson, not Jackson?** One small dependency whose tree model matches hand-built wire shapes; no
annotations; `Project` serialises unaided. `Json.of` makes request bodies one-liners.

**Why Swing?** The team knows it, it ships with the JDK, and FlatLaf handles HiDPI. `conductor.sdlc`
has no Swing dependency, so a web front end (`FUTURE.md` item 12) can reuse `StageRunner` and
`ProjectStore` unchanged.

**Why are all three cloud keys required?** `Config.hasAllKeys` gates startup and the panel needs
three clients for three default seats. There is no single-provider mode today.

**Does `context.txt` go with every call?** Every `StageRunner` call except the OpenClaw hand-off,
which sends only `HANDOFF_SYSTEM` and the brief; the Settings dialog title ("every agent call")
overstates it slightly.

## Conventions

- **Error strings** keep a fixed shape: `[<provider> HTTP <code>] <redacted body>`,
  `[<provider>] <redacted message>`, and above that `[<Name> unavailable: ...]`,
  `[lead unavailable: ...]`, `[assist unavailable: ...]`, `[panel unavailable: ...]`,
  `[openclaw unavailable: ...]`. `OpenClawClient` relies on the first form to spot its 400.
- **No secrets in strings.** Keys travel only in headers; anything that may contain a provider
  response goes through `Http.redactKeys` with the configured secret; never log a request body or
  put a key in an exception message.
- **Gson only.** Build JSON with `Json.of` / `JsonObject`, never concatenation; parse with
  `Json.parseObject` and the null-safe readers. The one JSON literal is `PlanFormat.SCHEMA`.
- **Never throw across `AgentClient.send`**; return `AgentResponse.error`. `StageRunner` and `Panel`
  assume it and add a last-line catch anyway.
- **Byte-stable system prompts.** Anything that varies per call belongs in `messages`.
- **Comments explain why** - a constraint the next reader could not infer (an API that rejects a
  field, a cache boundary, a Gson quirk). The code already says what.
- **Package-private test seams** (`buildBody`, `endpoint()`, `headers()`) are fine; tests live in
  the same package as the class they exercise.
