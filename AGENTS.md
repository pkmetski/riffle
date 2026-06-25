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

### Do not blindly update existing tests to make them pass

When a fix causes existing tests to fail, the tests are usually protecting a prior invariant or regression scenario — updating them mechanically risks silently re-opening the bug they were added to catch.

Before changing any existing test:

1. Trace each failing test back to the commit/PR that introduced it. The commit message + the test's own comments name the invariant.
2. Confirm the new code still upholds that invariant. If it does, the test inputs/assertions should still pass — re-examine the fix, not the test.
3. Only adjust a test when the invariant itself has *deliberately* changed, and call that out explicitly in the PR description.

A fix that requires "updating" several pre-existing regression tests to pass is a warning sign — prefer landing the change at a different layer that leaves the prior guarantees untouched.
