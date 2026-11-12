---
name: check-credential-memories-before-asking
description: "When the user mentions ABS or Storyteller test servers, read [[reference-abs-server]] and [[reference-storyteller-server]] from memory before asking for credentials"
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 977045be-75f1-4b4f-87f0-38a1a66385b4
---

When the work involves hitting the local ABS or Storyteller dev servers, read the credential memories first — never ask the user for a password.

**Why:** The user has explicitly told me twice that the creds live in memory. Asking again wastes a round-trip and reads as not paying attention to the context I already have.

**How to apply:** As soon as I need to call `media-server:13378` (ABS) or `media-server:8001` (Storyteller), Read the matching memory file — `reference_abs_server.md` (test/test) or `reference_storyteller_server.md` (plamen + strong password) — and pull the credentials from there directly. The MEMORY.md index already points to both.
