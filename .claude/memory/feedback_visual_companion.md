---
name: feedback-visual-companion
description: "User opted in to the brainstorming visual companion (browser mockups) — don't ask again, just offer/use it for visual questions"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: c6d8fc11-b5c5-4131-ae46-ee0440f3971c
---

For brainstorming sessions in this project, the user has accepted the visual companion (browser-based mockups via `superpowers:brainstorming`). Skip the consent offer and use it directly for visual questions (mockups, layouts, side-by-side comparisons). Still decide per-question whether the browser or terminal is the right medium — conceptual/text questions stay in the terminal.

**Why:** User confirmed on 2026-05-31 when brainstorming the reader chapter-title-at-top feature and asked the preference be remembered.

**How to apply:** When a brainstorming session has visual content ahead, start the companion server (`scripts/start-server.sh --project-dir ...` from the superpowers brainstorming skill) without re-asking for consent. Still respect per-question medium choice.
