---
name: feedback-no-claude-coauthor
description: Never add Co-Authored-By Claude trailer to commit messages
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 93259887-f683-4980-bb8d-051dd5a9d33a
---

Do not add the `Co-Authored-By: Claude ...` trailer (or any Claude attribution) to commits I create. Plain commit messages only.

**Why:** User asked to remove Claude as a collaborator on every commit.

**How to apply:** When committing, omit the `Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>` line that the default workflow would otherwise append. Same for PR bodies — skip the "Generated with Claude Code" footer unless explicitly requested.
