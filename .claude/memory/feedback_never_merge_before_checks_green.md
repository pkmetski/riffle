---
name: never-merge-before-checks-green
description: "Never squash-merge a PR until gh pr checks shows every check explicitly passing — \"no checks reported\" is NOT passing"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 1ee8977f-e681-467a-8834-db49c357adf2
---

Never merge a PR until `gh pr checks <n>` shows every check with a passing status. This gate is absolute — no exceptions.

**Why:** PR #288 was merged immediately after seeing "no checks reported on the branch." That is not the same as checks passing. The finalize skill says "This gate is absolute — if any check is failing, pending, or still running, do NOT merge." There is no carve-out for "probably no CI configured."

**How to apply:** If `gh pr checks` returns "no checks reported," wait — checks may be delayed. Poll again after a minute or two. Only if checks remain absent AND you can confirm from the repo's Actions config that no CI runs on PRs should you consider proceeding — and even then, surface it to the user and ask before merging, never decide unilaterally.
