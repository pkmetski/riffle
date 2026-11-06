# Memory Index

## Credentials & Servers

- [ABS server credentials](reference_abs_server.md) — http://media-server:13378, test/test
- [Komga server credentials](reference_komga_server.md) — http://media-server:25600, test@test.test/test
- [Storyteller server credentials](reference_storyteller_server.md) — http://media-server:8001, test/test, peer Server type ADR 0020
- [WebDAV Synology test server](reference_webdav_synology_server.md) — https://pkmetski.synology.me:5006 webdav; DSM quirks
- [Read credential memories before asking](feedback_check_credential_memories.md) — never ask for ABS/Storyteller passwords
- [ABS playlist API shape](reference_abs_playlist_api.md) — verified endpoints (singular /item), empty auto-delete
- [Storyteller v2 + zip range-extraction](reference_storyteller_v2_and_range_extraction.md) — /api/v2/books/{id} + /synced range fetches
- [ABS /status for version](reference_abs_status_endpoint.md) — GET /status (unauth) returns serverVersion
- [O'Reilly audiobook = Kaltura API](reference_oreilly_audiobook_kaltura_api.md) — videotocs→videoclips→kaltura session→playManifest HLS; cookies plaintext

## Infrastructure & CI

- [Fresh workspace gradle setup](reference_fresh_workspace_gradle_setup.md) — new worktrees need gradle-wrapper.jar + local.properties
- [Concurrent gradle KSP corruption](reference_concurrent_gradle_ksp_corruption.md) — parallel test+lint corrupts :app kspCaches; run sequentially
- [MigrationTest pre-existing fails](reference_migration_tests_api25_preexisting_fails.md) — 7 core:database step tests fail on main (schema drift)
- [CI false-green 2026-08](reference_ci_false_green_2026_08.md) — emulator-runner per-line sh -c loses rc; KMP needs `test jvmTest`
- [Use ./gradlew test jvmTest](feedback_gradle_test_command.md) — KMP modules have no `test` task; CI runs `test jvmTest`
- [JAVA_HOME for ./gradlew](reference_java_home_android_studio.md) — export JAVA_HOME=Android Studio jbr
- [DB migration version collision](feedback_db_migration_version_collision.md) — branch+main both add vN → renumber to N+1 inline
- [Koin Map bindings need a qualifier](reference_koin_generic_map_bindings_need_qualifier.md) — unqualified single<Map> overrides; allowlist ServerType files
- [Koin missing-bindings postmortem](reference_koin_missing_bindings_postmortem.md) — #840 bug shapes; KoinModuleVerificationTest; cancelled-not-failed
- [advanceUntilIdle skips backgroundScope](reference_kn_test_scheduler_background_scope.md) — use testScheduler.runCurrent(); iosTest DB needs -lsqlite3
- [No bigger CI runners](feedback_no_bigger_ci_runners.md) — fix iOS harness flakiness at test/code level, not by paying for bigger runners
- [Never touch Android CI timeout for iOS fixes](feedback_never_touch_android_ci_timeout.md) — only edit ios.yml timeouts; never touch android.yml when chasing iOS failures
- [GitHub issue relationships](feedback_github_issue_relationships.md) — use addBlockedBy mutation, never comments or sub-issues, for blocking relationships

## Emulator & AVD

- [Emulator lifecycle gotchas](reference_emulator_lifecycle_gotchas_harness.md) — Bash timeouts SIGTERM emulators (setsid double-fork); API-37 for WebView
- [API-25 AVD boot + WebView DevTools](reference_api25_avd_boot_incantation.md) — only Harness_Medium_Phone boots; DevTools via adb forward
- [Harness run AVD gotchas](reference_harness_run_avd_gotchas.md) — exact `Harness_Medium_Phone`; fresh AVDs need KEYCODE_WAKEUP; WebView=API-25
- [Harness macro only runs :app](reference_harness_macro_only_app_module.md) — core:database MigrationTest needs manual ANDROID_SERIAL run
- [Test AVD runs Chrome 55 WebView](reference_test_avd_chrome55_webview.md) — volume page-turn always fails; use real device
- [Shared emulator DB downgrade crash](reference_shared_emulator_db_downgrade.md) — sibling workspace's higher-version DB; `pm clear`
- [Harness AVD contention flake](reference_harness_avd_contention_flake.md) — "Process crashed" = parallel workspace; re-run
- [Harness silent-tap gotchas](reference_harness_ui_tap_gotchas.md) — ribbon under TopAppBar; continuous INVISIBLE container; detent clear
- [Hilt androidTest SingletonC failure](reference_stale_hilt_androidtest_codegen.md) — usually REAL missing test binding, not stale codegen
- [Emulator↔Tailscale bridge](reference_emulator_tailscale_bridge.md) — CGNAT unroutable; host proxy + adb reverse + 127.0.0.1
- [Ephemeral AVD per session](feedback_ephemeral_avd_per_session.md) — clone from 7.1.1 base, use all session, delete on finalize
- [Never touch other emulators](feedback_never_touch_other_emulators.md) — only my own AVD by serial; never kill-all / adb kill-server

## iOS

- [iOS app won't launch = Swift overlay dyld abort](reference_ios_app_wont_launch_swift_overlay_dyld.md) — "no process ID" = libswiftWebKit absent on iOS 18; smoke-launch + check_swift_overlays in ios.yml
- [iOS harness flakiness root causes](reference_ios_harness_flakiness_root_causes.md) — runner variance + heavy tests; sim-crash retry; use .xcresult
- [iOS CI harness slow = single clone](reference_ios_ci_harness_slow_single_clone.md) — Clone 2 launch failure serialises; parse per-clone seconds first
- [iOS Xcode stale-framework + K/N gotchas](reference_ios_xcode_stale_framework_and_kn_gotchas.md) — Kotlin edits don't relink dylib; rm Products before xcodebuild
- [Unsigned sim app = Keychain fails → empty library](reference_ios_unsigned_sim_keychain_fails.md) — never CODE_SIGNING_ALLOWED=NO for harness
- [Compose iOS IME inset clips forms](reference_compose_ios_ime_inset_clips_forms.md) — safeDrawingPadding includes ime; verticalScroll + dismissKeyboard
- [Don't edit the README / no iOS there](feedback_no_ios_in_readme.md) — no iOS in public README until it has a release channel
- [No ios-scenarios docs folder](feedback_no_ios_scenarios_docs.md) — never recreate docs/testing/ios-scenarios/

## Reader & Content

- [Annotations View = "elided view"](reference_annotations_view_terminology.md) — NOT "elevated"; distinct from "annotations list"
- [Annotations text↔graph symmetry REVERSED](feedback_annotations_text_graph_symmetric.md) — figure inherits only via single-selection across it
- [Continuous annotation-focus reflow race](reference_continuous_annotation_focus_reflow_race.md) — off-screen land = pre-reflow scroll; re-land until stable
- [AnnotationFocusHarness had dead sleeps](reference_annotation_focus_harness_dead_sleeps.md) — sleep+marker scaffolding, no assertion; grep `writeMarker`
- [Continuous empty-pages root cause](reference_continuous_empty_pages_scrollheight_clamp.md) — scrollHeight viewport-clamp; measure body-bottom+root-padding
- [Continuous chapter-boundary blank flash](reference_continuous_chapter_boundary_blank_flash.md) — off-screen tile de-raster; setOffscreenPreRaster(true)
- [Continuous WebView 16384-px raster cap](reference_continuous_webview_16384_raster_cap.md) — deep-chapter blank = GPU max texture; capped sliding window
- [Continuous cold-open latency root cause](reference_continuous_cold_open_latency.md) — target-only gate, deferred neighbours, DOM-ready measure
- [Continuous backward-boundary bug family](reference_continuous_backward_prepend_evict_pingpong.md) — evict ping-pong, fling overshoot, placeholder stacking
- [Flaky replaceAllForLibrary emission tests](reference_flaky_replaceall_emission_tests.md) — 3 core:database tests flake; pre-existing
- [AutoFollowJsTest paginated-snap pre-existing fail](reference_autofollowjs_paginated_snap_preexisting_fail.md) — fails on main too
- [Continuous annotation mark anchored landing](reference_continuous_annotation_mark_anchored_landing.md) — anchor to mark, not scroll position
- [activeSource.id as position key = bug](reference_active_source_position_key_bug.md) — keyed on activeSource.id not item.sourceId; add sourceId param
- [Page-fall on chapter start](reference_page_turn_fall_chapter_start.md) — margins override injects :root padding-top after paint; remove it
- [Progress stuck at 100% + library≠detail bar](reference_progress_stuck_100_close_scope.md) — onClose readingProgress race → survivable-scope write; ABS lag → progressServerUpdatedAt; isFinished via derive-from-ebookProgress
- [ProgressFlushScope teardown fix](reference_progress_flush_scope_teardown.md) — close/pause ABS sync dropped on viewModelScope cancel; survivable app-scope
- [Blank-screen nav root fix](reference_blank_screen_nav_root_fix.md) — popUpTo(HOME) stacks dup roots; navigateAsRoot popUpTo(graph.id)
- [Finished-book reopen position](reference_finished_book_reopen_position.md) — finished must reopen at beginning; 3 root causes; resumePositionFor
- [Progress ping-pong = divergent fraction](reference_progress_pingpong_unified_fraction.md) — all writers use CatalogProgress.unifiedLibraryFraction()
- [Burger-menu freeze fix](reference_burger_menu_freeze_fix.md) — #602: shouldInterceptBackForDrawer(currentOpen, targetOpen)
- [Reader "no right margin" = snap-pitch mismatch](reference_reader_right_margin_is_column_snap_bug.md) — viewportWidth/dpr ≠ CSS column pitch
- [Reader internal-link navigation fix](reference_reader_internal_link_navigation.md) — non-footnote #anchor taps via Readium go()
- [ReadiumCSS night nukes background-color](reference_readiumcss_night_nukes_background.md) — dark/sepia erase painted elements unless inline !important
- [Readium 3.2.0+ multi-column decoration misposition](reference_readium_33_highlight_regression.md) — on 3.3.0 with single-column pin
- [Readium search-decoration positioning](reference_readium_search_decoration_positioning.md) — CLEAR+re-apply on spaced settle
- [TOC nav reflow race](reference_toc_nav_reflow_race.md) — snap before post-load reflow; element-anchored rAF snap
- [Backward-chapter overshoot fix](reference_backward_chapter_overshoot.md) — Readium ViewPager turn + onPageLoaded reflow; snapToEnd
- [Side-edge swipe exits immersive](reference_side_edge_gesture_exclusion_200dp_cap.md) — #148 can't fix (200dp cap); bounce-back fix verified
- [Offline sweep on Main = page-turn freeze](reference_offline_sweep_main_thread_page_turn.md) — isAvailableOffline sweep on Main; flowOn(Default)+memo
- [ScrollBoundaryNavigationContainer is authoritative](feedback_scroll_boundary_container_authority.md) — failing tests are wrong tests

## Readaloud & Audiobook

- [Readaloud e2e test setup](reference_readaloud_e2e_test_setup.md) — recipe to stand up readaloud book on emulator
- [Readaloud chapter nav is audio-domain](reference_readaloud_chapter_nav_uses_reader_toc.md) — prev/next = audio seek; TOC approach REJECTED
- [Readaloud chapter-flash root cause](reference_readaloud_chapter_flash_dateline_collision.md) — autoFollowSnapJs 12-char key false-matches recurring dateline
- [Readaloud highlight = text-anchored](reference_readaloud_highlight_text_anchored.md) — anchor decoration to sentence TEXT from bundle
- [Readaloud highlight wrong after rotation](reference_readaloud_highlight_rotation_reflow.md) — decoration before onPageLoaded; pageLoadGeneration re-key
- [Readaloud no-sound = emulator audio-HAL stall](reference_readaloud_no_audio_headless_emulator.md) — wedged AudioTrack; `adb reboot` fixes
- [Readaloud no-sound has TWO causes](reference_readaloud_nosound_disk_fill.md) — audio-HAL stall (reboot) OR truncated bundle from disk-fill
- [Readaloud degenerate boundary-clip skip](reference_readaloud_degenerate_boundary_clip_skip.md) — ~50ms SMIL clip absorbed; bundle defect
- [Audiobook position double-count](reference_audiobook_position_double_count.md) — scrubber past duration = double startOffsetSec; use currentPosition/1000
- [PendingSeekGate optimistic discontinuity](reference_pending_seek_gate_optimistic_discontinuity.md) — use maybeConfirm (epsilon 0.5s), not onDiscontinuity
- [Audiobook process-death autoplay fix](reference_audiobook_process_death_autoplay_fix.md) — root cause + design for branch pkmetski/audiobook-auto-resume
- [Storyteller /synced slow-to-first-byte](reference_storyteller_synced_slow_first_byte.md) — full bundle before any byte; bounded sidecar + fail-fast

## Comics & Panel Detection

- [Panel-detection fidelity chain](reference_panel_detector_path_invariant.md) — reproduce "Detected panels" first; both-sides boundary tests
- [Splash-panel split pan](reference_panel_splash_split_pan.md) — overflow SPLIT with zero zoom gain; gain≥1.5 gate
- [Comics streaming page-turn latency](reference_comics_streaming_page_turn_latency.md) — uncached streaming, never OS ver; read-ahead + spinner
- [MuPDF no builtin selection](reference_mupdf_no_builtin_selection.md) — PDF text selection is custom; not MuPDF's own widget

## Workflow Preferences

- [Autonomous mode: skip approval gates](feedback_autonomous_mode_skip_approval_gates.md) — "fully autonomously" = skip brainstorming gate
- [Auto-resolve trivial rebase conflicts](feedback_auto_resolve_trivial_conflicts.md) — mechanical unions resolve without asking
- [Stash before rebase, don't ask](feedback_stash_before_rebase.md) — auto stash → rebase → pop
- [Bundle when asked](feedback_bundle_when_asked.md) — user says bundle → one PR
- [No pushing without permission](feedback_no_push_without_permission.md) — never git push unless asked; commits are fine
- [No Claude co-author trailer](feedback_no_claude_coauthor.md) — omit Co-Authored-By and Generated-with footer
- [PR titles use Conventional Commits](feedback_pr_title_convention.md) — prefix with `type(scope):`
- [Poll background test runs](feedback_poll_background_tests.md) — harness runs fail silently; poll, never idle-wait
- [PR-watch fallback = 240–300s](feedback_pr_watch_cadence.md) — /finalize Phase 6 + /loop watchers use short heartbeats
- ["Reduce cadence" = SHORTER + re-schedule](feedback_cadence_word_direction.md) — shorter on "reduce"; always re-ScheduleWakeup if non-green
- [Never merge before checks green](feedback_never_merge_before_checks_green.md) — "no checks reported" ≠ passing; absolute gate
- [Visual companion opted-in](feedback_visual_companion.md) — skip visual-companion consent prompt
- [Extract video frames with ffmpeg](feedback_video_frames_via_ffmpeg.md) — `ffmpeg -vf fps=2` for .webm/.mp4
- [No README feature checklist](feedback_no_readme_feature_checklist.md) — never add "mark complete in README" step
- [Verify reader/UI fixes on AVD before "done"](feedback_verify_on_device_before_done.md) — JVM tests don't cover Readium/WebView; instrumentation ≠ real gesture; capture frames
- [Verify every consumer surface before PR](feedback_verify_every_consumer_before_pr.md) — drive EVERY reader/library/round-trip, not just one
- [Landed CONTENT, not stability](feedback_verify_landed_content_not_stability.md) — stable-but-wrong position is still the bug; capture 1/3/6/12s, match the target figure
- [Never claim a fix without tracing user trigger](feedback_no_overconfident_fix_claims.md) — "compiles + green" ≠ fixed; trace trigger→change→UI
- [Verify reader/UI fixes on AVD before "done"](feedback_verify_in_avd_before_done.md) — JVM tests don't cover Readium/WebView; AVD is required for visual changes
