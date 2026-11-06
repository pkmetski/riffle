---
name: verify-on-device-before-done
description: "Reader/UI fixes: run on AVD and verify the symptom is gone before announcing the fix is done. Unit tests + plausible hypothesis ≠ done."
metadata: 
  node_type: memory
  type: feedback
  originSessionId: 4220b064-99e2-4748-b0a3-33758485d274
---

For reader, highlight, decoration, scroll, navigation, layout — anything that ultimately renders through Readium or WebView — JVM unit tests on Kotlin helpers do NOT prove the user-visible bug is fixed. The actual failure usually lives in the JS/WebView/Readium layer, which JVM tests can't exercise.

**Rule:** Before saying "done" / "fixed" / asking the user to verify, install on the AVD and reproduce the original symptom — confirm it's gone. If you can't (or won't) install on a device, say so explicitly and frame the change as "plausible fix, not verified" rather than as a fix.

**Why:** Plamen called this out after I shipped a cssSelector change for the heading-highlight bug, claimed it was done, and asked him to try it. It didn't work. The hypothesis (Readium text-quote anchor failing inside `<h1>`) might have been wrong, OR the cssSelector format I built doesn't match what Readium's `querySelector` accepts. Either way, a 30-second device check would have caught it before bothering the user.

**How to apply:** Whenever the diff touches reader rendering / decorations / WebView injection — boot the cloned ephemeral AVD ([[feedback_ephemeral_avd_per_session]]), `adb install` the branch APK pinned to its serial ([[feedback_never_touch_other_emulators]]), repro the exact failing user gesture, screenshot the result. Only then report.
