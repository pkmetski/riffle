---
name: annotations-view-terminology
description: "The Annotations View is the \"elided view\" (elided publication of annotated passages), not \"elevated\". Distinct from the \"annotations list\"."
metadata: 
  node_type: memory
  type: reference
  originSessionId: dbb2f9cb-7d21-4d92-b899-122c07a52280
---

The Annotations View — the reader running in `ReaderSource.Highlights` on a synthesised publication built by `HighlightsPublicationFactory` — is called the **elided view** because annotated passages are elided together into one virtual publication. It is NOT "elevated". It is also distinct from the **annotations list** (the sheet listing annotations without opening the reader).

**Why:** User corrected me after I mis-transcribed "eladed" as "elevated" while discussing an Annotations View navigation bug (2026-07-09).

**How to apply:** When the user says "annotations view" / "elided view" / "eladed view", they mean the reader mode on the synthesised highlights publication. Never conflate with "annotations list" (bottom-sheet list UI).

Related: [[project_annotations_view_navigation_wrong_chapter]] if that memory is ever written.
