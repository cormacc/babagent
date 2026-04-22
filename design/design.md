# babagent High-Level Design

## 1. Overview

`babagent` is a terminal-based coding agent inspired by pi's design philosophy:

- Keep the core small and understandable
- Make extension a first-class concept
- Keep core agent logic independent of any single UI
- Support multiple concurrent clients against shared in-process runtime state

This initial version focuses on one provider only: Anthropic.
It supports:

- Global API key configuration (`/auth`) and environment-based auth (`ANTHROPIC_API_KEY`)
- Per-session model selection and transcript state
- Authentication verification (`/test-auth`)
- Sending prompts to Anthropic models
- A lightweight slash-command extension mechanism
- An in-process session server with the Charm TUI as one client adapter

## 2. Design Goals

1. Minimal core with clear boundaries
2. Extensible command system (agent can evolve without rewriting core)
3. UI-agnostic session logic reusable by future HTML and Emacs clients
4. Provider integration isolated behind a small API module
5. Safe, practical local configuration behavior

## 3. Non-Goals (Initial Version)

- Multi-provider support
- Remote access or network transport for clients
- Conversation persistence/history database
- Multi-pane/complex TUI layouts
- Sandboxed extension runtime

## 4. Technology Choices

- Runtime: Babashka
- Current UI client: `charm.clj` (Model/Update/View)
- HTTP: babashka built-in HTTP client
- JSON: Cheshire
- Config storage: EDN file in user config dir
- Server model: in-process multi-session runtime

Rationale: this stack gives fast startup, simple deployment, and a clean path from one local TUI client to multiple client adapters without moving core agent logic again.

## 5. System Architecture

### 5.1 Module Map

- `src/babagent/app.clj`
  - Pure-ish session reducer and command behavior
  - Translates semantic events into updated session state and effect descriptors

- `src/babagent/server.clj`
  - In-process multi-session runtime
  - Owns global auth, session allocation, and session lookup
  - Executes effects via nexus dispatch; effect descriptors are `[:effect-type args-map]` vectors
  - Exposes `submit!` as the primary client entry point; returns `:client-effects` synchronously and runs server effects in a future
  - `register-effect-handler!` allows extensions to add new effect types at runtime

- `src/babagent/core.clj`
  - Entry point and startup orchestration
  - Starts the optional web server, registers a TUI client session if web is running, then launches the TUI

- `src/babagent/ui/terminal.clj`
  - Charm TUI client adapter
  - Owns local text-input widget state
  - Submits events to the server via `server/submit!` and renders one session snapshot
  - Subscribes to session updates reactively via `add-watch` on `server/!server-state`, bridged to Charm's cmd loop through a `LinkedBlockingQueue`
  - Applies Markdown-style transcript formatting (role headings, separators, and heading color highlighting)
  - Auto-labels unlabeled fenced blocks with syntax languages inferred from nearby file paths/extensions
  - Anchors the input line near the bottom of the terminal using window-height-aware spacer rendering and shows a boxed status bar under the input
  - Applies theme-configured colors for markdown headings, user-message background, and status bar

- `src/babagent/ui/web/`
  - ClojureScript browser UI served by `babagent.web`
  - Uses Replicant (hiccup render) and nexus (action/effect dispatch)
  - Receives session state via WebSocket JSON push; submits prompts via WebSocket

- `src/babagent/config.clj`
  - Config loading/saving
  - Merges defaults, file config, and environment overrides
  - Resolves XDG config/data roots for config files and session log storage

- `src/babagent/anthropic.clj`
  - Anthropic API requests (`/models`, `/messages`)
  - Response parsing and error helpers

- `src/babagent/extensions.clj`
  - Command registry
  - Dynamic extension loading from `extensions.edn`

- `bag` (repo root executable)
  - Babashka launcher script

### 5.2 Core State Model

The current runtime has two distinct state layers:

- Global server state
  - Shared Anthropic API key
  - Session registry
  - Session id allocation

- Per-session state
  - `:messages` - rendered conversation/system log messages (displayed with Markdown-style role sections in the TUI)
  - `:model` - active model for that session
  - `:available-models` - fetched model list for that session
  - `:status` - short status line for async operations
  - `:waiting?` - prevents overlapping API requests in that session

The TUI adapter (`ui/terminal.clj`) also owns local UI state containing:

- `:input` - current text-input component state
- `:spinner` - local waiting indicator state
- `:window-size` - latest terminal dimensions (used to keep input near the bottom of the shell)
- `:theme` - TUI color theme loaded from config (`:heading`, `:user-message`, `:status-bar`)

This separation keeps session behavior reusable across future clients.

## 6. Interaction Flow

### 6.1 Startup

1. Load default config from disk
2. Apply environment override (`ANTHROPIC_API_KEY`) if present
3. Initialize the in-process server
4. Register built-in commands
5. Load optional extensions from `extensions.edn`
6. Create a session for the current TUI client
7. Start the Charm program loop

### 6.2 User Input Handling

- `Enter` submits current input
- The TUI transcript renders role-separated Markdown-style blocks so user prompts, assistant replies, and system/status output are visually distinct (user prompts use a different background style)
- The input line is rendered near the bottom, with a boxed status bar directly beneath it showing current provider and selected model
- If input starts with `/`, dispatch as command
- Otherwise treat as model prompt for the current session
- While `:waiting?` is true for that session, new submits are rejected with a system message

### 6.3 Async Request Pattern

The TUI submits semantic events to the server via `server/submit!`. The server reducer (`app.clj`) returns effect descriptors as `[:effect-type args-map]` vectors. `submit!` returns `:client-effects` immediately to the caller and fires server-side effects asynchronously via nexus dispatch. Effect handlers are registered on a global `!server-system` atom and may call `server-dispatch!` recursively to deliver provider responses back into the session.

Examples:

- `/models` yields an Anthropic list-models effect for one session
- `/test-auth` yields an Anthropic list-models effect interpreted as auth validation
- `/reload` yields a runtime reload effect that refreshes configuration, extensions, and theme inputs
- Prompt submission yields an Anthropic message effect plus tool-call execution loop
- If tool use fails to converge, the runtime makes one final no-tools completion request instead of surfacing a raw tool-limit error to the user
- If tool calls themselves fail, the runtime includes those tool failures in the returned error message

This keeps core behavior out of the UI adapter and makes alternate clients practical.

## 7. Session Model

### 7.1 Global vs Session State

- Auth is global
- Model selection is session-local
- Message history and waiting status are session-local

This matches the intended usage: one local runtime, many possible clients, many independent conversations, one shared provider credential.

### 7.2 Multiple Clients

The current implementation is in-process only, but the server/client split is deliberate. Future HTML or Emacs clients should attach to the same server abstraction instead of duplicating command, prompt, and provider logic.

## 8. Extensibility Model

### 8.1 Command Registry

Extensions register commands by calling `register-command!` with:

- `:name`
- `:description`
- `:handler` function

Handlers receive session and shared context and return updated session state plus effect descriptors.

### 8.2 Extension Loading

`extensions.edn` contains a list of namespaces.
For each namespace, the system:

1. `require`s the namespace
2. Resolves and invokes `register!`

If `register!` is missing, load fails with explicit error feedback.

This model enables incremental self-extension by adding new namespaces and commands.

## 9. Configuration and Auth

Config precedence is:

1. Built-in defaults
2. `$XDG_CONFIG_HOME/babagent/config.edn` when `XDG_CONFIG_HOME` is set, otherwise `~/.config/babagent/config.edn` (or explicit `BABAGENT_CONFIG` path)
3. `ANTHROPIC_API_KEY` environment variable

Session logs are persisted as EDN under `$XDG_DATA_HOME/babagent/sessions/` (fallback: `~/.local/share/babagent/sessions/`), with per-project directories derived from session CWD by replacing `/` with `-`, and filenames based on session start timestamp. TUI theme config is loaded from config EDN and supports ANSI, ANSI256, hex, and RGB color specifications.

Implications:

- Local saved config works by default
- CI/shell sessions can inject keys without touching disk config
- Environment key cleanly overrides persisted key
- Global auth is shared by all sessions in the current process

## 10. Error Handling Strategy

- HTTP requests return structured `{ :status :body }`
- Non-2xx responses are mapped to user-friendly error messages
- Exceptions are caught and surfaced as system messages
- Unknown slash commands produce guidance (`try /help`)

## 11. Security Considerations (Initial)

- API key is never rendered in full; status output masks key
- Key may still be persisted in local config by `/auth`
- Environment-based key usage avoids writing secrets to disk

Future improvements could include keyring integration and opt-out persistence modes.

## 12. Known Limitations

- Single active provider (Anthropic only)
- Server is in-process only; no remote client transport yet
- No token accounting or context-window management
- No request cancellation once in-flight
- No built-in retry/backoff policies
- Extension loading is trusted-code execution

## 13. Future Evolution Directions

1. Add provider abstraction for OpenAI/local models
2. Add HTML and Emacs clients on top of the existing server boundary
3. Add conversation/session persistence options
4. Add richer TUI panes (history, logs, inspector)
5. Add extension metadata/versioning and safer loading controls
6. Introduce an optional transport layer for remote clients

## 14. Summary

The current design prioritizes a small local runtime with a cleaner architectural split than the original single-state TUI. `babagent` now has a reusable session server, session-local model state, global auth, and a thin TUI adapter that can be joined by future HTML or Emacs clients.
