# Agentic Prompt Playbook

For running Claude Code sessions that resolve technical debt without unscoped token burn.

---

## The core principle

The expensive failure mode is asking an agent to **discover** and **execute** in the same session. Discovery is expensive and repeatable. Execution is cheap when the discovery is already written down.

So: run one read-only audit per repo, produce a document, then scope every future session against that document.

| Session type | Writes code? | Output | Run how often |
|---|---|---|---|
| Audit | No | A markdown document | Once per repo, refresh quarterly |
| Execution | Yes | One branch, one deliverable | As many as needed |

If a session ever needs to explore *and* change, stop. Split it.

---

## Template 1: The audit session (read-only)

Run this first, once per repo. It should cost a few dollars, not sixty.

```
REPO: [name]
MODE: Read-only. Do not modify, create, or delete any file except the
single output document named below.

TASK: Produce a technical debt inventory.

Before anything else, run:
  git status
  git log --oneline -15
  git branch -a
Report what you find. If the working tree is dirty or the branch is not
what I named, stop and tell me.

Then explore the codebase and write DEBT-INVENTORY.md containing:

1. Architecture map. What are the main packages/modules and what does
   each one do? One paragraph each.
2. Debt items. For each, give:
   - What it is, in one sentence
   - The specific files and line ranges
   - Why it is a problem (bug risk, blocks future work, duplicated, dead)
   - Estimated blast radius: how many other files change if I fix this
   - Independent or blocked: does fixing this require something else first
3. Dead code. Anything unreferenced.
4. Duplication. Logic that exists in more than one place.
5. Your recommended order of attack, with reasoning.

Do not fix anything. Do not refactor while you explore. Write the
document and stop.

NO SUBAGENTS.
```

Then read the document yourself. You pick what gets fixed. That decision is yours, not the agent's, and it is the cheapest decision in the whole process.

---

## Template 2: The execution session

One item from the inventory. One branch. One deliverable.

```
REPO: [name]
BRANCH: [exact branch name, e.g. fix/provider-retry-dedup]
CONTEXT: Read DEBT-INVENTORY.md first. We are doing item [N] only.

Before anything else, run:
  git status
  git log --oneline -10
Confirm the branch and that the tree is clean. If not, stop.

GOAL: [one sentence, from the inventory]

FILES IN SCOPE:
  [list them explicitly, from the inventory]

REUSE, DO NOT REBUILD:
  [name the existing classes/methods that already do part of this]

OUT OF SCOPE:
  - Any file not listed above
  - Adding tests unless I named them in the deliverable
  - Formatting or style changes unrelated to the goal
  - "While I was in there" improvements

DELIVERABLE:
  [what exists at the end, e.g. "a BaseProvider class that the three
  provider clients extend, with retry logic removed from each"]

DONE WHEN: [the compile/test/behavior condition]

If you finish early, stop and report. Do not start the next item.
If you hit something that blocks the goal, stop and describe it. Do not
work around it by expanding scope.

NO SUBAGENTS unless I say otherwise.
```

The "out of scope" block is the line that saves you money. Agents expand scope by default because expanding scope looks like helpfulness.

---

## Template 3: The migration session

For moving toward the new vision (Spring, database, the memory layer). Same shape, but the reuse block matters more.

```
REPO: [name]
BRANCH: [exact branch name]
CONTEXT: Read DEBT-INVENTORY.md and ARCHITECTURE.md first.

GOAL: [one migration step, e.g. "move config loading from properties
files to Spring @ConfigurationProperties"]

WHAT ALREADY EXISTS AND MUST BE PRESERVED:
  - [class/method]: does [X]. Keep the behavior, change only the wiring.
  - [class/method]: does [Y]. Do not touch.

WHAT CHANGES:
  [the specific transformation]

WHAT DOES NOT CHANGE:
  - Public method signatures used by [caller]
  - The provider client layer
  - [anything else]

DELIVERABLE: [one sentence]
DONE WHEN: [the app still starts and X still works]

Do not migrate anything I did not name, even if it is obviously next.
```

---

## Session discipline checklist

Run through this before you hit enter.

- [ ] Does this session write code? If yes, is there an audit document it can read?
- [ ] Did I name the branch explicitly?
- [ ] Did I list the files in scope?
- [ ] Did I write an out-of-scope block?
- [ ] Did I name what already exists that should be reused?
- [ ] Is there exactly one deliverable?
- [ ] Did I say "stop and report" instead of leaving the end open?
- [ ] Did I disable subagents, or consciously decide to allow them?

During the session:

- [ ] If the agent starts exploring branches or searching broadly, interrupt. That means discovery leaked into execution.
- [ ] If it says "I also noticed" and starts a second thing, interrupt.
- [ ] Check `/cost` at the halfway mark, not at the end.

---

## Documents that pay for themselves

Keep these in each repo. Every session reads them instead of rediscovering.

| File | What it holds | Who writes it |
|---|---|---|
| `DEBT-INVENTORY.md` | The audit output | Agent, once |
| `ARCHITECTURE.md` | Module map, what talks to what | Agent during audit, you maintain |
| `CLAUDE.md` | Conventions, build commands, what not to touch | You, by hand, short |
| `DECISIONS.md` | Why things are the way they are | You, one line per decision |

`CLAUDE.md` is the highest-leverage file you are not currently writing. Ten lines. Build command, test command, the two or three rules an agent keeps violating.

---

## Per-repo starting point

**Repo 1, Debate Platform.** Audit only. You decided to port clean and stop. One audit session tells you what "clean" costs. If the number is high, ship it as-is with a good README and move on.

**Repo 2, Student Org Operations Suite.** This is where audit-then-execute matters most because it is the repo that keeps growing. Audit the Cowork repo first. Then the Spring/DB migration as its own bounded session, schema and persistence only, nothing else bundled in.

**Repo 3, SDLC Platform.** Audit, README, park. Do not fix debt in a repo you are not developing.

---

## The one-line version

Make the agent write down what it learned before you let it change anything, then never pay to learn it again.
