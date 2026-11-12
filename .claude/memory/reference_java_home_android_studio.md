---
name: reference-java-home-android-studio
description: "JAVA_HOME path needed to run ./gradlew on this machine — no JDK is on PATH, but Android Studio bundles one"
metadata: 
  node_type: memory
  type: reference
  originSessionId: fb24b4ec-6377-459e-9740-946b103f2094
---

There is no JDK on PATH and no `JAVA_HOME` set in the shell. Running `./gradlew` directly will fail with "Unable to locate a Java Runtime."

Use the JetBrains Runtime bundled with Android Studio:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
```

Then `./gradlew` works (e.g. `./gradlew :core:data:testDebugUnitTest`).

**When dispatching subagents that need to run tests**, prepend this `export JAVA_HOME=...` to their Bash commands or include it in the prompt — they don't inherit env from the parent and may otherwise hit "Java Runtime not found."

Confirmed working on 2026-06-01 with `openjdk version "21.0.10"` (JBR 21).
