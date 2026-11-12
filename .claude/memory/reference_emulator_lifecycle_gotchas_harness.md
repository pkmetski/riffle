---
name: reference-emulator-lifecycle-gotchas-harness
description: "Why session emulators die mid-work (Bash-call timeouts SIGTERM nohup'd children; Android Studio adopts/deletes AVDs) and the launch recipe that survives (setsid double-fork), plus API-37 AVD notes"
metadata:
  node_type: memory
  type: reference
  originSessionId: 803bec8c-467d-4bed-ae5b-08d69181869c
  modified: 2026-09-25T18:00:21.978Z
---

Observed 2026-09-25 while measuring reader open latency:

- **A Bash tool call that hits its timeout SIGTERMs processes started with `nohup … &` in EARLIER calls** (the emulator log shows "Wait for emulator … to shutdown gracefully"). `run_in_background` Bash tasks are capped at 10 min too. Launch the emulator fully detached instead:
  `python3 -c 'import os; ... os.fork(); os.setsid(); os.fork(); os.execv(emulator, [...])'` (double fork + setsid, stdout/err dup'd to a log). That instance survived every later timeout.
- Keep every emulator-driving call well under 120 s (one open/measure per call, poll loops ≤ 60 s); `sleep` in foreground calls is fine at short lengths but never let the call time out.
- **Android Studio's Device Manager (the user) may launch a newly-created AVD itself** (`-qt-hide-window -grpc-use-token` in `ps`), which shuts down the headless instance, and may delete AVDs from `~/.android/avd/` while a session runs (Medium_Phone_17 and two clones vanished). Re-check `ls ~/.android/avd` before assuming an AVD name still exists; a Studio-hosted instance of MY clone is still fine to use via `adb -s emulator-NNNN` (look it up with `adb -s <serial> emu avd name`).
- Studio-hosted instances can bind a console port already used by another session's emulator (5556/5557) and then never register with adb — kill only if it is your own AVD.
- Modern API-37 arm64 AVDs (`google_apis_playstore_ps16k`) boot headless in ~25 s and have a current WebView (Chrome 149): use them for WebView perf work; the API-25 harness AVD runs Chrome 55 and is unrepresentative.
- uiautomator dump returns "null root node" for ~10 s after boot; the add-source form fields (URL/username/password) carry no testTags — target them by label text.
- `adb reverse` and the media-server bridge (socat 127.0.0.1:13378 → 100.102.186.26:13378) must be re-added after every emulator restart.

Related: [[feedback_ephemeral_avd_per_session]], [[reference_emulator_tailscale_bridge]], [[reference_api25_avd_boot_incantation]].
