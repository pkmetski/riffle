---
name: feedback_no_readme_feature_checklist
description: "Do NOT mark features complete in README, and do not create issues/PR steps that do"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: bd3ef887-7e6f-403e-b0ec-6da95e0a097c
---

There is no "mark the feature complete in README" step. Do not add a `- [ ] → - [x]` README Features-list update to any PR, issue, or plan, and do not create an issue for it.

**Why:** The user explicitly retired this practice and removed the "Feature progress" section from `CLAUDE.md` (warsaw-v1 workspace). They were emphatic about it.

**How to apply:** When slicing work into issues (e.g. via [[project_riffle_status]] or to-issues), never include a "flip README checkbox" issue/step. If older instructions resurface, treat them as stale.
