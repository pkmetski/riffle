---
name: reference-api25-avd-boot-incantation
description: How to actually boot an Android 7.1.1 (API 25) emulator on this Apple-Silicon Mac when AVDs hang QEMU
metadata: 
  node_type: memory
  type: reference
  originSessionId: 70e80f6c-ef7b-40d9-839c-4c495a0bbaf3
---

On this Apple-Silicon Mac, most API-25 AVDs crash on boot with `detected a hanging thread 'QEMU2 main loop/CPUn thread'` — this hit `Harness_Medium_Phone_2`, `Medium_Phone`, and persisted across `-cores 2`, `-gpu swiftshader_indirect`, cold boot, etc. The **canonical `Harness_Medium_Phone` AVD boots reliably** with the Makefile's exact CI flags:

```
$ANDROID_HOME/emulator/emulator -avd Harness_Medium_Phone -no-window -no-audio -no-boot-anim -no-snapshot-load
```

(default GPU, no `-cores` override, headless). Drive it via `adb shell input tap` + `adb exec-out screencap` + `uiautomator dump`. The `_2` suffixed AVD is corrupt — don't use it.

UPDATE (2026-06-08): a fresh `avdmanager create`d API-25 AVD boots but ABORTS (exit 134, SIGABRT) on GPU snapshot-SAVE at exit. The reliable way to get a NEW stable API-25 device is to CLONE the known-good harness AVD, not create from scratch: APFS COW clone `cp -c -R ~/.android/avd/Harness_Medium_Phone.avd New.avd`, copy+retarget the `.ini` (`path=`/`path.rel=`) and `config.ini` (`AvdId`/`displayname`), then `rm -rf New.avd/snapshots New.avd/*.lock snapshot.trace read-snapshot.txt` so it cold-boots. Boot with the harness flags PLUS `-no-snapshot-save` to dodge the save-time abort. I created `Riffle_Test_Phone` this way and it boots/runs androidTest reliably. To run a FILTERED androidTest set on a specific AVD safely (mirrors the Makefile), find the serial by AVD name and pin gradle: `ANDROID_SERIAL=$SERIAL ./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=<FQN>` — only that emulator is targeted, so a physical device / sibling-workspace emulator is untouched.

Audio still stalls on this emulator (see [[reference-readaloud-no-audio-headless-emulator]]): the readaloud highlight advances a few sentences then the audio clock freezes. Enough to test UI/tap behavior, not continuous playback.

**WebView DevTools works on Riffle debug builds** — invaluable for diagnosing Readium reader JS:
```
adb forward tcp:9222 localabstract:webview_devtools_remote_$(adb shell pidof com.riffle.app)
curl http://localhost:9222/json   # lists the pre-rendered spread pages
```
Then drive the DevTools protocol (Runtime.evaluate) over the per-page `webSocketDebuggerUrl` to read/inject JS (e.g. `window.getSelection().isCollapsed`, install click recorders, wrap `Android.onTap`). A minimal stdlib-only Python WS client is enough; no node/websocket libs are installed here.
