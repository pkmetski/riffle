## Summary

<!-- What does this PR change and why? 1–3 bullet points. -->

## Evidence

<!--
For every item closed by this PR, add one row below.
- "iOS test (iOS code path)" is mandatory — see AGENTS.md §Tests must mirror both platforms.
- Use `Closes #N` (not `Part of #N`) only when ALL sub-issues of issue N are covered.
- For user-visible changes, attach an Android + iOS screenshot pair showing the same state.

| Item | Android test | iOS test (iOS code path) | Simulator screenshot |
|------|-------------|--------------------------|---------------------|
|      |             |                          |                      |
-->

## Checklist

- [ ] Logic lives in `commonMain` (or documented reason it cannot)
- [ ] No private platform copy of a derivation already in `commonMain`
- [ ] New interactive controls have a `testTag` constant in `TestTags.kt`
- [ ] `./gradlew test jvmTest riffleChecks` green
- [ ] `./gradlew :app:compileDebugAndroidTestKotlin :core:data:compileAndroidHostTest` green
- [ ] `make harness-test` green (and `make harness-test-tablet` if `@TabletLayout` tests added)
- [ ] iOS unit tests green: `xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:iosAppUnitTests`
- [ ] iOS UI tests green: `xcodebuild test -scheme iosApp -destination 'platform=iOS Simulator,name=iPhone 16' -only-testing:iosAppTests`
- [ ] New `commonTest` modules listed in `.github/workflows/ios.yml`
- [ ] New Swift test files confirmed as members of the correct Xcode target in `project.pbxproj`
