# Submitting Riffle to F-Droid

This is a one-time manual step. After it lands, `AutoUpdateMode: Version` in the
recipe causes F-Droid to pick up every subsequent `vX.Y.Z` tag automatically.

## Prerequisites

- A FOSS `LICENSE` file at the repo root. (F-Droid will not accept an app without one.)
- At least one signed tag pushed (CI green, APK attached to the GitHub Release).
- A GitLab account.

## Steps

1. Fork https://gitlab.com/fdroid/fdroiddata
2. Create `metadata/com.riffle.app.yml` with:

   ```yaml
   Categories:
     - Reading
   License: <SPDX id matching repo LICENSE>
   AuthorName: pkmetski
   SourceCode: https://github.com/pkmetski/riffle
   IssueTracker: https://github.com/pkmetski/riffle/issues

   AutoName: Riffle

   RepoType: git
   Repo: https://github.com/pkmetski/riffle.git

   Builds:
     - versionName: <X.Y.Z of first tag>
       versionCode: <NNN matching that tag>
       commit: v<X.Y.Z>
       subdir: app
       gradle:
         - yes
       prebuild:
         - make fonts

   AutoUpdateMode: Version
   UpdateCheckMode: Tags ^v[0-9.]+$
   CurrentVersion: <X.Y.Z>
   CurrentVersionCode: <NNN>
   ```

3. Open a merge request against `fdroiddata`. Address reviewer feedback (typical: lint nits, license clarification).
4. After merge, monitor https://f-droid.org/packages/com.riffle.app/ — the build server picks it up on its next scan.
