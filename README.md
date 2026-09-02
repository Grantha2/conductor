# Conductor

Conductor is a desktop app that walks someone who is not a software engineer through the
software development lifecycle: Idea, Users & Goals, Requirements, Design, Plan, Build,
Verify, Ship. At each stage it asks three to five plain-language questions in an ordinary
form (no chat, no prompt writing). Where a second opinion is worth paying for, one button
asks an AI agent to sharpen a single answer; at the two stages where perspective matters
most (Requirements and Design) a panel of agents from different providers debates and one
of them writes the synthesis. What comes out is a set of stage artifacts - requirements,
a design, a task plan with machine-readable JSON, a build brief, a verification checklist,
release notes - precise enough to hand to an engineering agent (an OpenClaw agent, Claude
Code) or a human team. The user never writes a prompt and never sees an API key.

## Quick start

Prerequisites: Java 21 and Maven.

```bash
cd conductor
cp config.properties.example config.properties
# edit config.properties and fill in the three cloud keys
mvn compile exec:java                       # the GUI (default)
mvn compile exec:java -Dexec.args="--cli"   # the same rails in a terminal
mvn test                                    # unit tests; never touch the network
```

Required keys: `anthropic.key`, `openai.key`, `gemini.key`. All three must be real values
(`Config.hasAllKeys` rejects blanks and the `YOUR_...` placeholders); the app refuses to
start otherwise, because the debate panel needs one provider per seat. Everything else in
the file is optional and has a default: model names, endpoint overrides, `max.tokens`
(16000), `debate.rounds` (1), and the OpenClaw block (off unless `openclaw.base.url` is set).

`.mvn/jvm.config` turns on HiDPI scaling for the Maven JVM, which is where `exec:java`
runs the GUI. `Main` sets the same properties when `conductor.Main` is started outside Maven.

## How a session works

Pick or create a project. The eight stages are listed on the left; the centre shows the
current stage's questions; the right pane shows that stage's output. Answer the questions,
then press **Complete stage**. Most stages are pure Java over your answers. The status bar
tells you how many API calls completing the stage will make, and a dialog confirms it
before any call is made.

| Stage | What you are asked | What gets produced | Agent calls |
|---|---|---|---|
| Idea | what, why*, how people cope today, what it will NOT do | Markdown summary of your answers | 0 |
| Users & Goals | who, what they need, what success looks like*, where/when | Markdown summary of your answers | 0 |
| Requirements | must*, should, must never, hard constraints | Panel synthesis: MUST / SHOULD / NEVER lists | 7 |
| Design | main pieces*, data to remember, connections, biggest worry | Panel synthesis: pieces, how they talk, riskiest part, build first | 7 |
| Plan | smallest useful version*, deadline, people, risks | Task checklist grouped by size, plus the raw task JSON | 1 |
| Build | who builds it, tech constraints, definition of done*, what not to do | Build brief for an engineering agent | 1 (+1 for the OpenClaw hand-off) |
| Verify | how you will check*, who tries it, what sends it back | Checklist (one line per plan task) plus suggested acceptance checks | 1 |
| Ship | first audience, channel, announcement*, what you learned | Release notes assembled from your answers | 0 |

`*` marks the one question per stage with an **Ask an agent to sharpen this** button. It
makes at most one call to the lead agent (Anthropic, the first configured client), sends
your draft plus everything the project already knows, and puts the improved text back in
the field. It stays your answer; edit it freely. The CLI asks before replacing your text.

The 7 for Requirements and Design is `Panel.apiCallCount()`: one synthesis + one opening
answer per panelist + one reaction per panelist per round, so `1 + 3 + 3 x debate.rounds`.
Set `debate.rounds=0` for 4 calls. Verify makes its single call only when "How will you
check it" has an answer (`StageRunner.estimateCalls`); the GUI requires that answer, so in
the GUI it is always 1.

While the panel runs, each panelist's contribution streams into the output pane. When the
stage finishes, only the lead's synthesis is kept as the artifact; the transcript is not
saved. Completing a stage again replaces its artifact.

## Where your data lives

Everything is written in the working directory you launch from. All of it is gitignored.
Delete a file to reset that piece.

| File | Holds | Reset by |
|---|---|---|
| `config.properties` | API keys, model names, OpenClaw settings, debate tuning | re-copy from `config.properties.example` |
| `projects/<slug>.json` | one project: name, timestamps, current stage, every answer, every artifact | delete the file (or the folder) |
| `panelists.json` | the three panel seats: name, perspective, lens (Settings > Panelists...) | delete; built-in defaults return |
| `context.txt` | free text sent as the first part of the system context on every stage call (Settings > Context...) | delete or empty it |

Project files are pretty-printed JSON you can read and edit. A corrupt or missing project
file is reported on stderr and skipped; it never crashes the app. Nothing is sent anywhere
except the configured providers, and there is no telemetry.

## Connecting OpenClaw

OpenClaw is a self-hosted agent gateway that speaks the OpenAI wire format. Three keys:

```properties
openclaw.base.url=http://localhost:18789   # setting this turns OpenClaw on
openclaw.token=                            # bearer token, if your gateway needs one
openclaw.agent.id=default                  # which agent answers
```

Conductor posts to `{openclaw.base.url}/v1/chat/completions`. That OpenAI-compatible
endpoint is off by default in OpenClaw; enable it in the gateway's own configuration first.
The request's `model` field carries the agent route, `openclaw/<agent id>`.

In the code as it stands, OpenClaw is the **Build-stage hand-off target, not a debate
panelist**. When it is configured, the Build stage shows a **Hand off to OpenClaw** button
that sends the finished build brief to the agent as one message and shows the reply under
the brief (the reply is displayed, not saved). `Wiring.panelClients()` deliberately excludes
the gateway from the panel: `Panel` requires exactly one client per panelist seat, the
default panel has three seats, and the gateway's support for tools and JSON schemas is
optional. Note: the comment in `config.properties.example` still says OpenClaw "joins the
panel as a fourth seat"; the code does not do that.

Degraded mode: if the gateway answers HTTP 400 to a request that carried tools or a JSON
schema, `OpenClawClient` retries once as plain text and prefixes the reply with
`[note: OpenClaw gateway rejected tools/JSON schema; answered as plain text]`. Other errors
are returned as-is.

## Cost and safety

- Calls per stage are listed above: 0, 0, 7, 7, 1, 1, 1, 0 with defaults, plus at most one
  per "sharpen" click and one per hand-off. The status bar shows the count when you select a
  stage; a dialog repeats it before running; the sharpen button's tooltip names the model.
- API keys are read from `config.properties` and used only as HTTP headers. They never
  appear in the UI, in project files, or in artifacts.
- Every error string that could contain a provider response is passed through
  `Http.redactKeys`, which masks `sk-...` and `AIza...` keys and the exact configured
  OpenClaw token before the text can reach the screen.
- Provider failures never become exceptions. A failed panelist becomes a
  `[Name unavailable: ...]` line and the debate continues; a failed lead call becomes a
  readable placeholder artifact you can re-run.
- Transient failures (408, 409, 429, 5xx, connection errors) are retried up to three times
  with backoff, honouring `retry-after`. Other 4xx errors are returned immediately.
- No analytics, no crash reporting, no network traffic other than the configured providers.

## Project layout

```
conductor/
  pom.xml                       Java 21, Gson, FlatLaf, JUnit 5; exec:java runs conductor.Main
  config.properties.example     copy to config.properties (gitignored)
  FUTURE.md                     roadmap and principles
  docs/CODE_TOUR.md             contributor walkthrough
  .github/workflows/ci.yml      mvn verify on push and PR (active once this is its own repo)
  src/main/java/conductor/
    Main.java                   entry point: GUI by default, --cli for the terminal loop
    Wiring.java                 config + clients + panelists + context -> one StageRunner
    agents/                     AgentClient (the one interface), request/response records,
                                Http and Json helpers, Anthropic/OpenAI/Gemini/OpenClaw clients
    config/                     Config (typed view of config.properties), Clients (builds the list)
    panel/                      Panelist (a seat) and Panel (the three-phase debate)
    sdlc/                       Stage, Question, Questions, Project, ProjectStore,
                                ProjectContext, StageRunner, PlanFormat
    ui/                         MainGui (Swing)
  src/test/java/conductor/      JUnit 5 with scripted fakes; no network access
```

## Contributing

Read [`docs/CODE_TOUR.md`](docs/CODE_TOUR.md) for the architecture and
[`FUTURE.md`](FUTURE.md) for what is worth building next and the principles to keep.

- Branch names: `feat/...`, `fix/...`, `docs/...`, `chore/...`.
- Run `mvn test` before opening a pull request. The suite needs no API keys and makes no
  network calls; the fakes in `src/test/java` stand in for providers and HTTP.
- CI (`.github/workflows/ci.yml`) runs `mvn -B -q verify` on every push to `main` and every
  pull request once this directory is a standalone repository (see the parent repo's
  `MIGRATION.md`).
- Keep the rules in `AgentClient`'s Javadoc: one blocking round-trip per `send`, never throw
  on API errors, build JSON with Gson objects, never put a key in a string.
