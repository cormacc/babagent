# Repository Guidelines

## Project Structure & Module Organization
Core source lives in `src/babagent/`. `app.clj` holds the UI-agnostic session reducer, `server.clj` owns the in-process multi-session runtime with nexus-based effect dispatch, and `core.clj` is the entry point that starts the optional web server and launches the TUI. `ui/terminal.clj` is the Charm TUI adapter; `ui/web/` holds the ClojureScript browser UI (Replicant + nexus). `tools.clj` implements built-in tool execution and nREPL helpers, and `extensions.clj` plus `ext/` handle extension registration and Chromium-related integrations. Browser-injected ClojureScript code lives in `src/babagent/ext/chromium_pick.cljs` and compiles to `resources/public/js/`. Project configuration is split across `bb.edn`, `shadow-cljs.edn`, `package.json`, and `extensions.edn`. Design notes and work tracking live in `design/` and `TASKS.org`.

## Build, Test, and Development Commands
Run the agent locally with `bb -m babagent.core` or `./bag`. Build the browser helper with `npm run build:pick`; use `npm run watch:pick` while iterating on the CLJS helper. Dependency installation is `npm install` for the CLJS toolchain and Babashka/Clojure deps resolve from `bb.edn` at runtime. For quick load checks, use `bb -e "(require 'babagent.core)"` or target the namespace you changed.

## Coding Style & Naming Conventions
Follow idiomatic Clojure style: 2-space indentation, aligned bindings, and small pure helper functions where practical. Namespace names use `babagent.*`, file names use `snake_case`, and private helpers are commonly marked with `defn-`. Prefer descriptive keyword keys such as `:timeout_seconds` and `:available-models`. Keep generated assets out of `src/`; compiled JS belongs under `resources/public/js/`.

## Testing Guidelines
This repository currently relies on REPL-driven verification rather than a formal `test/` tree. If `.nrepl-port` is present, use that session to exercise every changed function, reload affected namespaces, and check edge cases such as `nil`, empty input, and invalid paths. For repo-specific smoke checks, load the touched namespaces with `bb -e "(require 'babagent.server 'babagent.app 'babagent.tools 'babagent.ui.terminal)"` and verify changed user flows such as global `/auth`, per-session `/model`, `/models`, extension loading, or Chromium helper integration as applicable. When adding tests later, place them under `test/` with namespaces mirroring `src/`, for example `test/babagent/tools_test.clj`.

## Commit & Pull Request Guidelines
Recent history follows concise Conventional Commit-style subjects such as `feat(chromium): ...` and `feat: ...`; keep using that pattern with imperative wording. Pull requests should explain the user-visible change, note validation performed, and link related tasks or issues. Include screenshots or terminal snippets only when UI output or extension behavior changed.

## Agent Workflow Notes
Read `APPEND_SYSTEM.md` before changing core behavior. Project-specific validation should cite the exact proof used: the nREPL eval or the `bb -e "(require ...)"` command that loaded the affected namespaces cleanly. Changes to Anthropic model IDs or other provider-facing defaults should be checked against a live provider response when credentials are available; otherwise call out that the value was not live-validated.

After each code change, review `README.md` and `design/design.md` and update them when behavior, architecture, naming, commands, or configuration have changed.
