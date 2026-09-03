# Debt Inventory

**Not yet generated.** This file is the output of a read-only audit session.

Run Template 1 from `docs/agentic-prompt-playbook.md` against this repo, on `main`, with a clean tree. The session writes only this file and fixes nothing.

Expected sections when filled: architecture map (`docs/CODE_TOUR.md` is a head start; the audit should confirm it still matches the code), debt items with file and line ranges, dead code, duplication, recommended order of attack.

For this repo the inventory is a record, not a to-do list. Conductor is parked until Cowork Phase 1 ships, and debt in a repo that is not being developed does not get fixed. The one exception worth noting in the audit: the `agents/stateful-tool-use-stage-runner` branch has unmerged work; the audit should say whether it is safe to merge or should be closed.
