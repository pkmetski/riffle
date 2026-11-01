---
name: reference-readaloud-highlight-missing-stale-apk
description: "Readaloud highlight missing on a shared AVD is usually a stale/cross-workspace APK, not a code regression — reinstall the branch's own build before debugging"
metadata: 
  node_type: memory
  type: reference
  originSessionId: 233c0a24-5e61-4fbb-b1e5-f4fbf3028083
---

Symptom: "readaloud highlights disappeared" on an AVD, and clearing app data did NOT fix it.

On 2026-06-11 this was reported against branch `phoenix-v1` (#133) and suspected as a regression. It was NOT. Driving the real app on the shared `Medium_Phone` AVD (emulator-5558) with The Martian, the whole pipeline was verified live and intact: bundle 315MB, `EpubContentExtractor` → 168 chapters, `ReadaloudTextQuotes.build` → 11688 quote keys, and the readaloud decoration effect fired on EVERY sentence advance with `quoteFound=true`, `decorable=true`, `activeFragmentRef` advancing. The blue highlight rendered and followed correctly.

What had happened: the shared AVD had a DIFFERENT build installed (sibling Conductor workspaces share these AVDs; the package was `com.riffle.app-1`). Clearing data can't fix a wrong APK — only a reinstall can. Installing a fresh `:app:assembleDebug` of the actual branch under test restored the highlight immediately.

Takeaway before debugging any "readaloud highlight gone" report: confirm WHICH build is installed (the nav-drawer shows the git SHA + `-dirty`, e.g. `93de8dd4-dirty`) and reinstall the branch's own APK first. The data pipeline being silent on the empty-quote case is a real blind spot, but on phoenix-v1 #133 it is NOT empty. Related: [[reference_shared_emulator_db_downgrade]], [[reference_readaloud_nosound_disk_fill]].
