---
name: autonomous-mode-skip-approval-gates
description: "When user says \"fully autonomously\" or similar, skip brainstorming approval gates and proceed directly to implementation"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 6d6fced0-a8b9-4e0d-a152-0ff99c12fb09
  modified: 2026-09-15T05:17:40.068Z
---

When the user invokes a task with "fully autonomously", "autonomously", or similar phrasing, skip all skill approval gates (including the brainstorming hard-gate that requires explicit "yes" before proceeding). Go straight to implementation without stopping to ask for confirmation on the design.

**Why:** User explicitly authorized autonomous execution — asking for approval contradicts that instruction. Per skill preamble: "User instructions take precedence over skills."

**How to apply:** After any brainstorming/design thinking, proceed directly to `superpowers:writing-plans` and implementation without posting a "does this look right?" message.
