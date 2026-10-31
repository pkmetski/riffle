---
name: reference-fresh-workspace-gradle-setup
description: Fresh Conductor worktrees lack gradle-wrapper.jar and local.properties (both gitignored) — restore before building
metadata: 
  node_type: memory
  type: reference
  originSessionId: 707073c1-aa4b-4a9e-b184-d8706f31ddb1
---

A freshly-created Conductor workspace/worktree for riffle cannot run `./gradlew` until two gitignored files are restored:

- `gradle/wrapper/gradle-wrapper.jar` — copy from another workspace, e.g. `cp /Users/plamen.kmetski/conductor/workspaces/riffle/helsinki-v1/gradle/wrapper/gradle-wrapper.jar gradle/wrapper/`. Without it: "Could not find or load main class org.gradle.wrapper.GradleWrapperMain".
- `local.properties` with `sdk.dir=$HOME/Library/Android/sdk` — needed for any Android module task (`:app:*`, `:core:data:*`). Pure-JVM modules like `:core:domain:test` work without it. Without it: "SDK location not found".

Also remember `JAVA_HOME` per [[reference_java_home_android_studio]].
