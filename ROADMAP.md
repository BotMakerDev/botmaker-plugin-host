# ROADMAP — botmaker-plugin-host

Completed work up to 2026-10-05: see CHANGELOG.md, docs/refactor/, and `git show 2025a52:ROADMAP.md`.

## Open

- **Studio's `PluginHost` stays in Studio.** It is the bundled-fallback policy and the swap-on-project-change
  lifecycle of Studio's open project. If the CLI wants the fallback rule, lift **that rule** with a named
  argument for the bundled set, not the class.
- **No `module-info.java`.** Revisit only if a consumer goes modular; JPMS would make the child-first arm much
  harder to state.
