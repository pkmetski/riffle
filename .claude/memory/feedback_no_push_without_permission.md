---
name: feedback-no-push-without-permission
description: Never git push unless the user explicitly asks
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c1abc980-8de2-4348-b233-780a5655b873
---

Never run `git push` unless the user explicitly asks. Commit locally, then stop.

**Why:** User found unsolicited pushes intrusive and said "stop pushing unless told to" twice.

**How to apply:** After committing, do not push. If the task obviously implies pushing (e.g. "open a PR"), still confirm before pushing.
