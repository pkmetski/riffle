---
name: github-issue-relationships
description: "Always use GitHub's native GraphQL mutations for issue relationships; never use comments or sub-issues as a substitute"
metadata:
  node_type: memory
  type: feedback
  originSessionId: 1344ee1a-9de8-47d8-b2bf-e8c569dd9dfd
  modified: 2026-10-13T14:55:11.184Z
---

Use `addBlockedBy` (not comments, not sub-issues) to set blocking relationships between GitHub issues.

**Why:** I reached for a `/blocked-by #N` comment and an `addSubIssue` mutation before checking the actual schema — both are wrong. Comments are not machine-readable by the `next-multi-platform` skill's `blockedBy` GraphQL query. Sub-issues express parent/child hierarchy, not blocking. The user had to correct this twice in one session.

**How to apply:** Before setting any issue relationship, introspect first if unsure:
```bash
gh api graphql -f query='{ __schema { mutationType { fields { name } } } }' | python3 -c "..." | grep -i "block\|relat\|link"
```
The correct mutations are:
- `addBlockedBy(input: { issueId, blockingIssueId })` — #N is blocked by #M
- `removeBlockedBy(input: { issueId, blockingIssueId })` — remove the relationship
- `addRelatesTo` / `removeRelatesTo` — for non-blocking "relates to" links
- `addSubIssue` / `removeSubIssue` — only for genuine parent/child task hierarchies

Never use a comment (`/blocked-by #N`) as a substitute for a real relationship field.
