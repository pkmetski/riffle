---
name: feedback_stash_before_rebase
description: "When rebasing with uncommitted working-tree changes, stash and reapply automatically — don't ask"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 9b706972-8ec5-42ef-b61d-a6ee2ec805b6
---

When asked to rebase (or do other git operations) and there are uncommitted working-tree changes, automatically `git stash push` them, run the rebase, then `git stash pop` to reapply. Do not ask the user whether to stash or whether the pending edits are intentional.

**Why:** The user finds the confirmation prompt redundant — stashing and reapplying is the obvious, safe default and they don't want to be asked each time.

**How to apply:** Stash → rebase → pop without prompting. Only escalate if the pop produces a real conflict that needs a judgment call. Relates to [[feedback_auto_resolve_trivial_conflicts]].
