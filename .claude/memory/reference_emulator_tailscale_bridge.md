---
name: reference_emulator_tailscale_bridge
description: How to make an Android emulator reach the Tailscale-only media-server (ABS 13378 / Storyteller 8001)
metadata: 
  node_type: memory
  type: reference
  originSessionId: 5e8a79d0-eef8-43fa-931f-a22b951faccc
---

The harness/dev emulators cannot reach `media-server` (Tailscale, IP 100.102.186.26): the QEMU NAT doesn't carry the `100.64.0.0/10` CGNAT range and the AVD often boots with **no default route**. The host (Mac) resolves it fine via MagicDNS (`media-server.taild57d3d.ts.net`).

Working bridge (verified 2026-06-07):
1. Host TCP proxy `127.0.0.1:<port> → 100.102.186.26:<port>` for each of 8001 (Storyteller) and 13378 (ABS). A threaded Python proxy works; a naive serial one stalls. Script saved at `/tmp/tsproxy.py <listen> <target>`.
2. `adb -s <emu> reverse tcp:<port> tcp:<port>` so the device's `127.0.0.1:<port>` tunnels to the host proxy. This is more reliable than the `10.0.2.2` host-loopback alias on the wifi-stack emulator.
3. Configure the app's server URL as `http://127.0.0.1:<port>` (cleartext is allowed; `network_security_config.xml` has `base-config cleartextTrafficPermitted=true`).
4. Reverses and the default route are lost on emulator reboot; re-add. Add route if missing: `adb root; adb shell ip route add default via 192.168.232.1 dev wlan0`.

Gotchas that cost time: the app DB `servers.isActive` must be 1 or the library never loads ("Unable to connect"); the readaloud library 404s on a Storyteller-only setup because the library API always hits ABS endpoints (`AbsApiClient`, no server-type dispatch) — **ABS must be the primary server, Storyteller is only a readaloud peer**. See [[reference_readaloud_e2e_test_setup]].
