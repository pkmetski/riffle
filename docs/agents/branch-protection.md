# Branch Protection — main

Branch protection on `main` is enforced via the GitHub Branches API. The settings below are
the source of truth; they were applied by the CI guard in issue #1246.

## Active rules

| Rule | Setting |
|------|---------|
| Required status checks | Unit Tests, Build, Database tests (core:database), Harness tests (phone), Harness tests (tablet), Integration tests, Lint |
| Require branches to be up to date before merging | **enabled** (`strict: true`) |
| Enforce admins | enabled |
| Required approving reviews | 0 |

## Rationale

`strict: true` means every PR branch must be rebased onto (or currently at) the HEAD of `main`
before it can be merged. Without this, parallel workspaces could squash-merge from a stale base
and silently delete lines that a sibling PR had already landed — the root cause of the
`1157d56af` regression that dropped 126 lines of `HomeScreen.kt`, handoff params in
`LibraryNav.kt`, and font/test wiring in `project.pbxproj` (issue #1246).

## Updating

To update the protection rules:

```bash
gh api -X PUT repos/pkmetski/riffle/branches/main/protection \
  --field 'required_status_checks[strict]=true' \
  --field 'required_status_checks[contexts][]=Unit Tests' \
  --field 'required_status_checks[contexts][]=Build' \
  --field 'required_status_checks[contexts][]=Database tests (core:database)' \
  --field 'required_status_checks[contexts][]=Harness tests (phone)' \
  --field 'required_status_checks[contexts][]=Harness tests (tablet)' \
  --field 'required_status_checks[contexts][]=Integration tests' \
  --field 'required_status_checks[contexts][]=Lint' \
  --field 'required_pull_request_reviews[required_approving_review_count]=0' \
  --field 'enforce_admins=true' \
  --field 'restrictions=null'
```
