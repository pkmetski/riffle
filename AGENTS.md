## Agent skills

### Issue tracker

Issues live in GitHub Issues (`github.com/pkmetski/riffle`). See `docs/agents/issue-tracker.md`.

### Triage labels

Default label vocabulary — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — one `CONTEXT.md` + `docs/adr/` at the root. See `docs/agents/domain.md`.

## Agent rules

### Tests are required before opening a PR

Do not open a PR without tests that cover the fix or new functionality. Every bug fix needs a regression test that fails before the change and passes after; every new feature needs unit and/or integration coverage for its behavior. "Manually verified" is not a substitute for an automated test.

### Validate before claiming done

Every fix or new feature must be validated as actually working before it is marked complete or sent for review. Acceptable validation is one of:

- **Integration / instrumentation tests** that exercise the real code path and pass.
- **Visual verification in an AVD** — install the build on an emulator and reproduce the user-visible behavior end-to-end.

When validating in an AVD, follow the same pattern as the Makefile's `harness-test` target: use the dedicated Harness AVD, run the filtered test/scenario against it exclusively, and shut it down when done. Do not target arbitrary connected devices, and do not interfere with other emulators the developer may have running.

JVM unit tests alone are not sufficient validation for anything that touches Readium, the WebView, the reader UI, or other device-layer code.
