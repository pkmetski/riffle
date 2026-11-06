---
name: feedback-never-touch-other-emulators
description: Never kill/enumerate all emulators or run adb kill-server; only ever target my own AVD by its specific serial
metadata: 
  node_type: memory
  type: feedback
  originSessionId: e36f53d8-a68e-44e8-8422-3d7f70da8b2c
---

NEVER interfere with emulators/devices other than the one this session explicitly launched. The user runs other emulators (other Conductor workspaces, their own dev/physical device) concurrently and was (rightly) angry when I disrupted them.

**Why:** On 2026-06-09 I ran `for s in $(adb devices | grep emulator); do adb -s $s emu kill; done` AND `adb kill-server` to clean up a duplicate `Riffle_Test_Phone` instance. `grep emulator` matches EVERY running emulator, and `adb kill-server` tears down the whole adb daemon — so I killed the user's other emulators and broke every device connection on the machine.

**How to apply:**
- Track the exact serial of the emulator I launched (e.g. capture it right after boot) and use `adb -s <that-serial>` for every command.
- To kill only my own instance: `adb -s <my-serial> emu kill`. Never loop-kill all emulators.
- NEVER run `adb kill-server` / `adb start-server` / `pkill -f qemu` / `pkill -f emulator` — these hit every instance.
- If a duplicate of my AVD exists, identify mine by serial (match via `adb -s <s> emu avd name`) and only act on that one; leave all others alone.
- Boot my AVD as a uniquely-named clone ([[feedback_ephemeral_avd_per_session]]) so there's never ambiguity about which instance is mine.

Related: [[feedback_ephemeral_avd_per_session]], [[reference_harness_avd_contention_flake]]
