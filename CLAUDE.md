# Notes for Claude

## Commits

Never use `git push` in this repo. Steyn wants every commit to show as Verified on GitHub, and commits pushed from Claude's cloud environment are signed with a key GitHub doesn't recognise.

Push changes through the GitHub API instead (for example the GitHub MCP `push_files`, `create_or_update_file` and `delete_file` tools, or `gh api`). GitHub signs those commits. If the API can't do something (for example setting a file's executable bit), ask Steyn before falling back to `git push`.

`.claude/settings.json` has a hook that blocks `git push` to enforce this.

## Code

- The repo is or will be public. Never commit secrets, signing keys, personal data or built binaries.
- Keep both apps small and easy to audit, and avoid adding dependencies.
