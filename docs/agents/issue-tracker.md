# Issue tracker: Codeberg

Issues and PRDs for this repo live as Codeberg issues (`codeberg.org/pkmetski/riffle`). Use the `tea` CLI (Gitea/Forgejo-compatible) for all operations. If `tea` is unavailable, fall back to the Forgejo HTTP API at `https://codeberg.org/api/v1/` with a token in `$CODEBERG_TOKEN`.

## Conventions

- **Create an issue**: `tea issues create --title "..." --body "..."`. Use a heredoc for multi-line bodies.
- **Read an issue**: `tea issues <number>` plus `tea comments <number>` for the thread.
- **List issues**: `tea issues list --state open --output json` with appropriate `--labels` and `--state` filters; pipe through `jq` for shaping.
- **Comment on an issue**: `tea comments create <number> --body "..."`
- **Apply / remove labels**: `tea issues labels <number> --add "..."` / `--remove "..."`
- **Close**: `tea issues close <number>` followed by a comment if context is needed.

Infer the repo from `git remote -v` — `tea` does this automatically when run inside a clone with a configured login (`tea login add`).

## When a skill says "publish to the issue tracker"

Create a Codeberg issue.

## When a skill says "fetch the relevant ticket"

Run `tea issues <number>` and `tea comments <number>`.
