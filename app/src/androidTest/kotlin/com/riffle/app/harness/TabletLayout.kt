package com.riffle.app.harness

/**
 * Opt-in marker for instrumentation tests that exercise the Tablet Layout
 * (Material 3 Expanded window size class, ≥ 840dp — see ADR 0019).
 *
 * Tests bearing this annotation run only on the "Harness Medium Tablet" AVD
 * via `make harness-test-tablet`. The default `make harness-test` target runs
 * on the "Harness Medium Phone" AVD with this annotation excluded, so the
 * full suite does not double-run.
 *
 * The Makefile filters via `com.riffle.app.harness.TabletLayout` — keep this
 * package in sync with the filter constant if either is renamed.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS)
annotation class TabletLayout
