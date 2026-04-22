# babagent

`babagent` is a small coding agent built with Babashka and `charm.clj`. It runs in the terminal, talks to Anthropic models, and is designed to grow through local extensions instead of a large built-in core. The current implementation uses an in-process session server with the TUI acting as one client adapter, leaving room for future HTML or Emacs clients.

## What It Does

- Provides a TUI for prompt/response interaction
- Renders conversation history in Markdown-style sections with heading lines highlighted in a distinct color
- Renders user prompts with a distinct background style and encourages Markdown-formatted assistant replies (including fenced file/command snippets)
- Auto-labels unlabeled fenced blocks in transcript rendering when a nearby file path/extension is available (e.g. `.clj` -> `clojure`)
- Pins the input line near the bottom of the terminal and reserves a boxed status bar beneath it
- Shows a spinner in the TUI while a session is waiting for a response
- Maintains independent server-side sessions with per-session model state
- Uses a global Anthropic API key shared across sessions
- Supports Anthropic API authentication and model selection
- Exposes a slash-command system for built-in and external commands
- Includes a tool layer for file reads, shell commands, and nREPL-backed Clojure evaluation
- Supports a browser-injected Chromium helper compiled from ClojureScript
- Bounds tool-use loops and falls back to a final no-tools answer when the model fails to converge
- Reports which tool calls failed when tool-based execution breaks down
- Persists per-session EDN logs under `$XDG_DATA_HOME/babagent/sessions/` (or `~/.local/share/babagent/sessions/`) using a CWD-derived project folder name and start timestamp filename

## Project Layout

- `src/babagent/core.clj`: entry point; starts the web server if needed and launches the TUI
- `src/babagent/app.clj`: UI-agnostic session reducer and command behavior
- `src/babagent/server.clj`: in-process multi-session server/runtime; nexus-based effect dispatch
- `src/babagent/ui/terminal.clj`: Charm TUI adapter; local widget state, rendering, reactive session subscription
- `src/babagent/ui/web/`: ClojureScript browser UI (Replicant + nexus)
- `src/babagent/tools.clj`: built-in tool definitions and execution
- `src/babagent/extensions.clj`: command and extension registration
- `src/babagent/ext/`: Chromium-related integrations
- `resources/public/js/`: compiled browser helper assets
- `design/design.md`: high-level design notes

## Getting Started

Requirements:

- Babashka
- Node.js and npm for the ClojureScript build
- An Anthropic API key

Install CLJS tooling:

```sh
npm install
```

Run the agent:

```sh
bb -m babagent.core
```

or:

```sh
./bag
```

Build the Chromium pick helper:

```sh
npm run build:pick
```

During development:

```sh
npm run watch:pick
```

## Configuration

`babagent` follows XDG conventions. It reads config from `$XDG_CONFIG_HOME/babagent/config.edn` when `XDG_CONFIG_HOME` is set, otherwise from `~/.config/babagent/config.edn`. Session logs are written to `$XDG_DATA_HOME/babagent/sessions/` when `XDG_DATA_HOME` is set, otherwise to `~/.local/share/babagent/sessions/`. You can still override the exact config file path with `BABAGENT_CONFIG`. `ANTHROPIC_API_KEY` overrides the global API key at runtime.

TUI colors are themeable via `:theme` in config. Supported color forms include:
- `{:ansi :bright-cyan}`
- `{:ansi256 238}`
- `{:hex "#1f2937"}`
- `{:rgb [31 41 55]}`

Theme sections currently used by the TUI are `:heading`, `:user-message`, and `:status-bar`.

Common commands inside the TUI:

- `/auth <key>`: set the global Anthropic API key
- `/models`: fetch selectable Anthropic models
- `/model <name>` or `/model <number>`: choose the active model for the current session
- `/status`: show current auth and model state
- `/reload`: reload configuration, extensions, and theme state
- `/reload-ext`: reload namespaces listed in `extensions.edn`
- `/quit`: exit

Each TUI client gets its own server session. Session state includes the active model, transcript, and request status. Auth is global across sessions. The transcript view now uses Markdown-style structure for clearer separation of roles and technical content, and the input area is anchored near the bottom with a boxed status bar showing provider/model and key controls.

## Development Notes

The project is intentionally REPL-first. If `.nrepl-port` is available, validate changed functions through nREPL and reload affected namespaces after edits. For a fast non-interactive smoke check, load touched namespaces with:

```sh
bb -e "(require 'babagent.server 'babagent.app 'babagent.tools)"
```

See `AGENTS.md` for repository-specific contributor guidance and `APPEND_SYSTEM.md` for the generic Clojure agent workflow rules used in this repo.
