---
name: feedback-no-ios-in-readme
description: "Never edit README.md or mention iOS there until iOS has a public release channel — and don't codify this as a rule in AGENTS.md"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 5a9398f1-af19-4c69-b2c7-97b65c03d1f6
  modified: 2026-09-20T05:41:23.547Z
---

Do not edit `README.md`, and specifically do not mention iOS in it — no "Android and iOS" tagline,
no iOS build/prereq steps, no iOS screenshots — until the iOS app has an actual release mechanism
users can install from. During the 2026-09-20 parity pass I added iOS to the README and the user
said "Don't edit readme", then "Until ios has a release mechanism, don't mention it un public docs".

**Why:** the README is the public shop window. Advertising a platform nobody can obtain is worse
than saying nothing. Distribution today is GitHub Releases with a signed APK only
(see [[project_distribution_channels]]).

**How to apply:**
- "Public docs" means the README specifically. `AGENTS.md`, `CONTEXT.md` and `docs/adr/` are
  engineering docs; they already discuss iOS heavily and SHOULD describe it fully and accurately,
  since that is what keeps the platforms in sync. When I over-corrected and also stripped the iOS
  framing out of CONTEXT.md, the user pushed back — "Why undoing context" — and I restored it.
- **Do not write this rule into `AGENTS.md`.** I added a "Keep iOS out of the README" section there
  and the user said "Remove thr ios rule from agents". Keep it as a behavioural preference here,
  not as a repo-level instruction.

See [[feedback_ios_test_parity_means_ios_code_path]], [[feedback_no_readme_feature_checklist]].
